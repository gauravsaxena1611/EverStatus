package com.automations.everstatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.function.Function;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;

/**
 * Prevents the system from sleeping while EverStatus is active.
 *
 * macOS — three-layer strategy
 * ─────────────────────────────
 * Layer 1  Direct caffeinate child process  (immediate, covers startup gap)
 * Layer 2  LaunchAgent in ~/Library/LaunchAgents/  (persists across reboots, zero admin)
 * Layer 3  pmset -b disablesleep 1  (battery + lid-close, one-time admin prompt via Keychain)
 *
 * Windows — SetThreadExecutionState loop
 * ────────────────────────────────────────
 * 0x80000041 = ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_AWAYMODE_REQUIRED
 */
@Service
public class SleepPreventionService {

    private static final Logger log = LoggerFactory.getLogger(SleepPreventionService.class);

    private static final String OS = System.getProperty("os.name", "").toLowerCase();

    // macOS paths — all under ~/Library, user-writable, zero admin
    private static final String HOME             = System.getProperty("user.home");
    private static final String MAC_CONTROL_FILE = HOME + "/.everstatus.active";
    private static final String MAC_SUPPORT_DIR  = HOME + "/Library/Application Support/EverStatus";
    private static final String MAC_AGENT_SCRIPT = MAC_SUPPORT_DIR + "/everstatus-agent.sh";
    private static final String MAC_AGENTS_DIR   = HOME + "/Library/LaunchAgents";
    private static final String MAC_PLIST        = MAC_AGENTS_DIR + "/com.everstatus.agent.plist";
    private static final String MAC_LOG          = HOME + "/Library/Logs/everstatus-agent.log";
    private static final String MAC_DAEMON_LABEL = "com.everstatus.agent";

    public enum Coverage {
        /** All sleep types prevented */
        FULL,
        /** Idle sleep prevented; lid-close on battery may not be fully prevented */
        PARTIAL,
        /** Platform not recognised; key simulation only */
        KEY_ONLY
    }

    private Process  sleepProcess;
    private Thread   heartbeatThread;
    private Thread   powerMonitorThread;
    private Coverage coverage         = Coverage.KEY_ONLY;
    private boolean  shutdownHookAdded = false;
    private boolean  lidSleepDisabled  = false;

    // Credential store — lazily created, macOS only
    private MacAdminCredentialStore credentialStore = null;

    // Supplied by KeepActiveApp after Display is created.
    // Shows a native SWT password dialog on the main thread — avoids the osascript
    // display dialog SIGKILL that occurs when launching AppleScript UI from a background thread.
    private Function<String, String> passwordDialogProvider = null;

    // Called whenever coverage changes due to a mid-session power source change.
    // KeepActiveApp sets this to update the status label via display.asyncExec().
    private Runnable coverageChangedCallback = null;

    /**
     * Must be called from KeepActiveApp after the SWT Display is created, before START is pressed.
     * The supplier shows a native SWT password input dialog and returns the typed text (or null).
     */
    public void setPasswordDialogProvider(Function<String, String> provider) {
        log.debug("setPasswordDialogProvider() — provider={}", provider != null ? "set" : "null");
        this.passwordDialogProvider = provider;
    }

    /**
     * Called by KeepActiveApp to receive coverage-change notifications from the power monitor.
     * The callback is always invoked on the sleep-prevention-power-monitor background thread;
     * KeepActiveApp must use display.asyncExec() inside the callback to update SWT widgets.
     */
    public void setCoverageChangedCallback(Runnable callback) {
        log.debug("setCoverageChangedCallback() — callback={}", callback != null ? "set" : "null");
        this.coverageChangedCallback = callback;
    }

    private MacAdminCredentialStore credentialStore() {
        if (credentialStore == null) {
            log.debug("Creating MacAdminCredentialStore with passwordDialogProvider={}",
                passwordDialogProvider != null ? "set" : "NOT SET (dialogs will fail)");
            credentialStore = new MacAdminCredentialStore(passwordDialogProvider);
        }
        return credentialStore;
    }

    // ─── Public API ──────────────────────────────────────────────────────────────

    public synchronized void enable() {
        Runtime rt = Runtime.getRuntime();
        log.info("enable() called on thread='{}' os='{}'  memory={}MB free / {}MB max",
            Thread.currentThread().getName(), OS,
            rt.freeMemory() / 1_048_576, rt.maxMemory() / 1_048_576);

        if (sleepProcess != null && sleepProcess.isAlive()) {
            log.debug("enable() — sleep process already running (pid alive), skipping");
            return;
        }

        if (!shutdownHookAdded) {
            Runtime.getRuntime().addShutdownHook(
                new Thread(this::disable, "sleep-prevention-shutdown"));
            shutdownHookAdded = true;
            log.debug("Shutdown hook registered");
        }

        try {
            if (OS.contains("win")) {
                log.info("Platform: Windows — starting SetThreadExecutionState loop");
                sleepProcess = startWindowsSleepPrevention();
            } else if (OS.contains("mac") || OS.contains("darwin")) {
                log.info("Platform: macOS — starting multi-layer sleep prevention");
                sleepProcess = startMacSleepPrevention();
            } else {
                log.warn("Platform '{}' not recognised — key simulation only, no OS-level prevention", OS);
            }
        } catch (Exception e) {
            log.error("Failed to start sleep prevention on thread='{}'", Thread.currentThread().getName(), e);
        }

        log.info("enable() finished — coverage={}", coverage);
    }

    public synchronized void disable() {
        log.info("disable() called on thread='{}'", Thread.currentThread().getName());
        stopHeartbeat();

        if (sleepProcess != null) {
            log.debug("Destroying sleep prevention process");
            sleepProcess.destroy();
            sleepProcess = null;
        }

        stopPowerMonitor();

        if (OS.contains("mac") || OS.contains("darwin")) {
            log.debug("Removing heartbeat file: {}", MAC_CONTROL_FILE);
            silentDelete(Paths.get(MAC_CONTROL_FILE));
            restoreLidSleep();
        }

        coverage = Coverage.KEY_ONLY;
        log.info("disable() complete — coverage reset to KEY_ONLY");
    }

    public Coverage getCoverage() { return coverage; }

    // ─── macOS ───────────────────────────────────────────────────────────────────

    private Process startMacSleepPrevention() throws Exception {
        log.debug("Detecting macOS power source...");
        long t0 = System.currentTimeMillis();
        boolean onAC = isMacOnACPower();
        log.debug("Power source detected in {}ms — onAC={}", System.currentTimeMillis() - t0, onAC);

        log.debug("Detecting external displays...");
        t0 = System.currentTimeMillis();
        boolean hasExtDisplay = hasExternalDisplay();
        log.debug("Display detection took {}ms — hasExternalDisplay={}", System.currentTimeMillis() - t0, hasExtDisplay);

        // ── Scenario decision ─────────────────────────────────────────────────
        if (onAC) {
            log.info("━━ SCENARIO: AC Power ━━ caffeinate covers all sleep types including lid-close → coverage=FULL");
            coverage = Coverage.FULL;
        } else if (hasExtDisplay) {
            log.info("━━ SCENARIO: Battery + External Display ━━ macOS clamshell mode keeps system awake → coverage=FULL");
            coverage = Coverage.FULL;
        } else {
            log.info("━━ SCENARIO: Battery + No External Display ━━ lid-close sleep active by default; applying pmset -b disablesleep 1 via Keychain credential...");
            boolean lidFixed = tryDisableLidSleep();
            coverage = lidFixed ? Coverage.FULL : Coverage.PARTIAL;
            log.info("━━ SCENARIO RESULT: pmset applied={} → coverage={} ━━", lidFixed, coverage);
        }

        // ── Layer 2: LaunchAgent ───────────────────────────────────────────────
        installMacAgentIfNeeded();

        // ── Heartbeat file ─────────────────────────────────────────────────────
        touchFile(MAC_CONTROL_FILE);
        startHeartbeat(MAC_CONTROL_FILE);

        log.info("macOS sleep prevention started | power={} extDisplay={} coverage={} launchAgent={}",
            onAC ? "AC" : "battery",
            hasExtDisplay,
            coverage,
            Files.exists(Paths.get(MAC_PLIST)) ? "installed" : "pending");

        // ── Layer 1: Direct caffeinate (belt-and-suspenders until agent picks up) ──
        log.debug("Starting direct caffeinate -d -i -m -s as fallback process");
        Process p = new ProcessBuilder("caffeinate", "-d", "-i", "-m", "-s").start();
        log.debug("caffeinate started (pid lookup not available via Java ProcessBuilder)");

        // ── Power monitor: re-evaluates scenario every 30s while active ──────────
        startPowerMonitor(onAC, hasExtDisplay);

        return p;
    }

    /**
     * Starts a daemon thread that polls power-source and display state every 30 seconds.
     * When the scenario changes (e.g. AC unplugged, external display disconnected) the
     * sleep-prevention strategy is adjusted immediately without restarting the app.
     *
     * @param initialOnAC         power state at the time enable() was called
     * @param initialHasExtDisplay display state at the time enable() was called
     */
    private void startPowerMonitor(boolean initialOnAC, boolean initialHasExtDisplay) {
        log.info("Power monitor started — checking every 30s | initial state: AC={} extDisplay={}",
            initialOnAC, initialHasExtDisplay);

        final boolean[] lastState = { initialOnAC, initialHasExtDisplay };

        powerMonitorThread = new Thread(() -> {
            log.debug("Power monitor thread running");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30_000);

                    boolean onAC        = isMacOnACPower();
                    boolean hasExtDisp  = hasExternalDisplay();
                    boolean acChanged   = onAC      != lastState[0];
                    boolean dispChanged = hasExtDisp != lastState[1];

                    if (acChanged || dispChanged) {
                        log.info("━━ POWER STATE CHANGE ━━ AC: {} → {}  |  extDisplay: {} → {}",
                            lastState[0], onAC, lastState[1], hasExtDisp);
                        lastState[0] = onAC;
                        lastState[1] = hasExtDisp;
                        adjustForPowerChange(onAC, hasExtDisp);
                    }
                } catch (InterruptedException e) {
                    log.debug("Power monitor thread interrupted — stopping");
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.warn("Power monitor error (will retry next tick): {}", e.getMessage());
                }
            }
            log.debug("Power monitor thread exited");
        }, "sleep-prevention-power-monitor");
        powerMonitorThread.setDaemon(true);
        powerMonitorThread.start();
    }

    /**
     * Called by the power monitor whenever AC / display state changes.
     * Applies or removes {@code pmset -b disablesleep} as needed and fires the coverage callback.
     */
    private synchronized void adjustForPowerChange(boolean onAC, boolean hasExtDisplay) {
        if (onAC) {
            if (lidSleepDisabled) {
                log.info("━━ NEW SCENARIO: AC Power ━━ restoring default lid-close sleep (caffeinate -s handles it)");
                restoreLidSleep();
            } else {
                log.info("━━ NEW SCENARIO: AC Power ━━ caffeinate covers lid-close → coverage=FULL");
            }
            coverage = Coverage.FULL;
        } else if (hasExtDisplay) {
            if (lidSleepDisabled) {
                log.info("━━ NEW SCENARIO: Battery + External Display ━━ clamshell mode active; restoring default lid-close sleep");
                restoreLidSleep();
            } else {
                log.info("━━ NEW SCENARIO: Battery + External Display ━━ clamshell mode keeps system awake → coverage=FULL");
            }
            coverage = Coverage.FULL;
        } else {
            // Battery + no external display
            if (!lidSleepDisabled) {
                log.info("━━ NEW SCENARIO: Battery + No External Display ━━ applying pmset -b disablesleep 1...");
                boolean ok = tryDisableLidSleep();
                coverage = ok ? Coverage.FULL : Coverage.PARTIAL;
                log.info("━━ SCENARIO RESULT: pmset applied={} → coverage={} ━━", ok, coverage);
            } else {
                log.info("━━ NEW SCENARIO: Battery + No External Display ━━ pmset already active → coverage=FULL");
                coverage = Coverage.FULL;
            }
        }

        if (coverageChangedCallback != null) {
            try {
                coverageChangedCallback.run();
            } catch (Exception e) {
                log.warn("coverageChangedCallback threw: {}", e.getMessage());
            }
        }
    }

    private void stopPowerMonitor() {
        if (powerMonitorThread != null) {
            log.debug("Stopping power monitor thread");
            powerMonitorThread.interrupt();
            powerMonitorThread = null;
        }
    }

    /**
     * Installs the LaunchAgent to ~/Library/LaunchAgents/ — zero admin, user-writable paths.
     * Safe to call on every launch; exits early if already loaded.
     */
    private void installMacAgentIfNeeded() {
        log.debug("Checking if LaunchAgent '{}' is already loaded...", MAC_DAEMON_LABEL);

        if (isAgentLoaded()) {
            log.info("LaunchAgent '{}' already loaded — skipping install", MAC_DAEMON_LABEL);
            return;
        }

        log.info("LaunchAgent not loaded — installing to {}", MAC_PLIST);
        try {
            Files.createDirectories(Paths.get(MAC_SUPPORT_DIR));
            Files.createDirectories(Paths.get(MAC_AGENTS_DIR));
            log.debug("Directories ensured: {} and {}", MAC_SUPPORT_DIR, MAC_AGENTS_DIR);

            Path scriptTmp  = extractResource("everstatus-agent.sh");
            Path scriptDest = Paths.get(MAC_AGENT_SCRIPT);
            Files.copy(scriptTmp, scriptDest, StandardCopyOption.REPLACE_EXISTING);
            silentDelete(scriptTmp);
            scriptDest.toFile().setExecutable(true, false);
            log.debug("Agent script deployed: {}", scriptDest);

            Files.writeString(Paths.get(MAC_PLIST), buildAgentPlist());
            log.debug("Plist written: {}", MAC_PLIST);

            int rc = loadAgent();
            if (rc == 0) {
                log.info("LaunchAgent loaded successfully (~/Library/LaunchAgents/{}.plist) — no admin used",
                    MAC_DAEMON_LABEL);
            } else {
                log.warn("launchctl load returned rc={} — direct caffeinate still active as fallback", rc);
            }
        } catch (Exception e) {
            log.error("LaunchAgent install failed — direct caffeinate still active as fallback", e);
        }
    }

    /** Loads the plist using modern bootstrap (macOS 11+) then falls back to legacy load. */
    private int loadAgent() throws Exception {
        String uid = getUnixUID();
        log.debug("Loading agent — uid={}", uid);

        if (uid != null) {
            log.debug("Trying: launchctl bootstrap gui/{} {}", uid, MAC_PLIST);
            String[] result = runCapture("launchctl", "bootstrap", "gui/" + uid, MAC_PLIST);
            int rc = Integer.parseInt(result[0]);
            log.debug("launchctl bootstrap rc={} output='{}'", rc, result[1]);
            if (rc == 0) return 0;
            log.warn("launchctl bootstrap failed (rc={} output='{}') — falling back to launchctl load", rc, result[1]);
        }

        log.debug("Trying: launchctl load -w {}", MAC_PLIST);
        String[] result = runCapture("launchctl", "load", "-w", MAC_PLIST);
        int rc = Integer.parseInt(result[0]);
        log.debug("launchctl load rc={} output='{}'", rc, result[1]);
        if (rc != 0) {
            log.warn("launchctl load failed (rc={} output='{}') — direct caffeinate remains active as fallback", rc, result[1]);
        }
        return rc;
    }

    private boolean isAgentLoaded() {
        try {
            int rc = runSilent("launchctl", "list", MAC_DAEMON_LABEL);
            log.debug("launchctl list '{}' → rc={}", MAC_DAEMON_LABEL, rc);
            return rc == 0;
        } catch (Exception e) {
            log.debug("isAgentLoaded check failed: {}", e.getMessage());
            return false;
        }
    }

    private String getUnixUID() {
        try {
            Process p = new ProcessBuilder("id", "-u").start();
            String uid = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            boolean valid = uid.matches("\\d+");
            log.debug("Unix UID: '{}' valid={}", uid, valid);
            return valid ? uid : null;
        } catch (Exception e) {
            log.warn("Could not get Unix UID: {}", e.getMessage());
            return null;
        }
    }

    /** Builds the LaunchAgent plist with the actual user-home paths embedded. */
    private String buildAgentPlist() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\""
            + " \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
            + "<plist version=\"1.0\">\n"
            + "<dict>\n"
            + "    <key>Label</key>\n"
            + "    <string>" + MAC_DAEMON_LABEL + "</string>\n"
            + "\n"
            + "    <key>ProgramArguments</key>\n"
            + "    <array>\n"
            + "        <string>/bin/bash</string>\n"
            + "        <string>" + MAC_AGENT_SCRIPT + "</string>\n"
            + "    </array>\n"
            + "\n"
            + "    <key>RunAtLoad</key>\n"
            + "    <true/>\n"
            + "    <key>KeepAlive</key>\n"
            + "    <dict>\n"
            + "        <key>SuccessfulExit</key>\n"
            + "        <false/>\n"
            + "    </dict>\n"
            + "\n"
            + "    <key>StandardOutPath</key>\n"
            + "    <string>" + MAC_LOG + "</string>\n"
            + "    <key>StandardErrorPath</key>\n"
            + "    <string>" + MAC_LOG + "</string>\n"
            + "</dict>\n"
            + "</plist>\n";
    }

    /**
     * Returns true when at least one external (non-built-in) display is connected.
     * Counts "Resolution:" entries from system_profiler; >1 means external display present.
     */
    private boolean hasExternalDisplay() {
        try {
            Process p = new ProcessBuilder("system_profiler", "SPDisplaysDataType")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            int rc = p.waitFor();
            long displayCount = out.lines()
                .filter(l -> l.trim().startsWith("Resolution:"))
                .count();
            // Log each detected display's resolution line for traceability
            out.lines()
               .filter(l -> l.trim().startsWith("Resolution:") || l.trim().startsWith("Display Type:"))
               .forEach(l -> log.debug("  display info: {}", l.trim()));
            log.debug("system_profiler SPDisplaysDataType rc={} — found {} display(s) with Resolution entry",
                rc, displayCount);
            return displayCount > 1;
        } catch (Exception e) {
            log.warn("hasExternalDisplay() failed — assuming no external display: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Disables lid-close sleep on battery using the Keychain credential store.
     * First call: password dialog. Subsequent calls: silent Keychain lookup.
     */
    private boolean tryDisableLidSleep() {
        log.info("tryDisableLidSleep() — calling credentialStore.runPmset(\"1\") on thread='{}'",
            Thread.currentThread().getName());
        long t0 = System.currentTimeMillis();
        boolean ok = credentialStore().runPmset("1");
        log.info("tryDisableLidSleep() completed in {}ms — success={}", System.currentTimeMillis() - t0, ok);

        if (ok) {
            lidSleepDisabled = true;
            log.info("pmset -b disablesleep 1 applied — lid-close sleep blocked on battery");
        } else {
            log.warn("pmset disablesleep NOT applied (prompt cancelled, wrong password, or command failed)");
        }
        return ok;
    }

    /**
     * Restores default lid-close sleep behaviour — uses stored credential, no dialog.
     */
    private void restoreLidSleep() {
        if (!lidSleepDisabled) {
            log.debug("restoreLidSleep() — lidSleepDisabled=false, nothing to restore");
            return;
        }
        log.info("restoreLidSleep() — running pmset -b disablesleep 0 on thread='{}'",
            Thread.currentThread().getName());
        long t0 = System.currentTimeMillis();
        boolean ok = credentialStore().runPmset("0");
        log.info("restoreLidSleep() completed in {}ms — success={}", System.currentTimeMillis() - t0, ok);

        if (ok) {
            lidSleepDisabled = false;
            log.info("pmset -b disablesleep 0 restored — lid-close sleep behaviour back to default");
        } else {
            log.error("restoreLidSleep failed — run 'sudo pmset -b disablesleep 0' manually if needed");
        }
    }

    private boolean isMacOnACPower() {
        try {
            Process p = new ProcessBuilder("pmset", "-g", "batt")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            int rc = p.waitFor();
            boolean ac = out.contains("AC Power");
            log.debug("pmset -g batt rc={} onAC={} output: '{}'",
                rc, ac, out.trim().replace("\n", " | "));
            return ac;
        } catch (Exception e) {
            log.warn("isMacOnACPower() failed — defaulting to AC=true: {}", e.getMessage(), e);
            return true;
        }
    }

    // ─── Windows ─────────────────────────────────────────────────────────────────

    private Process startWindowsSleepPrevention() throws Exception {
        log.debug("Detecting Windows sleep mode (powercfg /a)...");
        boolean modernStandby = isWindowsModernStandby();
        coverage = modernStandby ? Coverage.PARTIAL : Coverage.FULL;

        if (modernStandby) {
            log.warn("Windows Modern Standby (S0) detected — idle sleep prevented but lid-close on battery is OS-terminated (no user-space fix)");
        } else {
            log.info("Windows S3 sleep detected — Away Mode active; lid-close keeps system running");
        }

        // 0x80000041 = ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_AWAYMODE_REQUIRED
        String script =
            "Add-Type -MemberDefinition '[DllImport(\"kernel32.dll\")] " +
            "public static extern uint SetThreadExecutionState(uint esFlags);' " +
            "-Name 'Power' -Namespace 'Win32' -PassThru | Out-Null; " +
            "[Win32.Power]::SetThreadExecutionState(0x80000041) | Out-Null; " +
            "while ($true) { Start-Sleep -Seconds 30; " +
            "[Win32.Power]::SetThreadExecutionState(0x80000041) | Out-Null }";

        log.debug("Starting PowerShell SetThreadExecutionState loop (0x80000041)");
        return new ProcessBuilder("powershell", "-NonInteractive", "-NoProfile", "-Command", script)
            .redirectErrorStream(true).start();
    }

    private boolean isWindowsModernStandby() {
        try {
            Process p = new ProcessBuilder("powercfg", "/a")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            boolean modern = out.contains("S0 Low Power Idle");
            log.debug("powercfg /a → modernStandby={}", modern);
            return modern;
        } catch (Exception e) {
            log.warn("isWindowsModernStandby() failed — assuming S3: {}", e.getMessage());
            return false;
        }
    }

    // ─── Heartbeat ───────────────────────────────────────────────────────────────

    private void startHeartbeat(String path) {
        log.debug("Starting heartbeat thread for: {}", path);
        heartbeatThread = new Thread(() -> {
            log.debug("Heartbeat thread started — touching '{}' every 60s", path);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Path p = Paths.get(path);
                    if (Files.exists(p)) {
                        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis()));
                        log.debug("Heartbeat touched: {}", path);
                    } else {
                        log.warn("Heartbeat file missing, recreating: {}", path);
                        touchFile(path);
                    }
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    log.debug("Heartbeat thread interrupted — stopping");
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.warn("Heartbeat touch failed: {}", e.getMessage());
                }
            }
            log.debug("Heartbeat thread exited");
        }, "sleep-prevention-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void stopHeartbeat() {
        if (heartbeatThread != null) {
            log.debug("Stopping heartbeat thread");
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
    }

    // ─── Utilities ───────────────────────────────────────────────────────────────

    private void touchFile(String path) {
        try {
            Files.write(Paths.get(path), new byte[0],
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Touched file: {}", path);
        } catch (Exception e) {
            log.error("Could not touch '{}': {}", path, e.getMessage());
        }
    }

    private void silentDelete(Path p) {
        try {
            boolean deleted = Files.deleteIfExists(p);
            log.debug("silentDelete '{}' → deleted={}", p, deleted);
        } catch (Exception e) {
            log.debug("silentDelete '{}' failed: {}", p, e.getMessage());
        }
    }

    private Path extractResource(String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String prefix = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        try (InputStream is = getClass().getResourceAsStream("/" + name)) {
            if (is == null) throw new IOException("Resource not bundled: " + name);
            Path tmp = Files.createTempFile("everstatus-" + prefix + "-", suffix);
            Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Extracted resource '{}' → {}", name, tmp);
            return tmp;
        }
    }

    private int runSilent(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        return p.waitFor();
    }

    /**
     * Like {@link #runSilent} but captures and returns stdout+stderr.
     * Used when we need to log the exact output on failure.
     */
    private String[] runCapture(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        int rc = p.waitFor();
        return new String[]{ String.valueOf(rc), out };
    }
}
