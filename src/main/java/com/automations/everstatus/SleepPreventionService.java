package com.automations.everstatus;

import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;

/**
 * Prevents the system from sleeping while EverStatus is active.
 * Zero admin rights required on all platforms.
 *
 * ┌───────────────────────────────────────────────────────────────────────────────────┐
 * │  macOS — Chrome-like LaunchAgent (~/Library/LaunchAgents/)                       │
 * │                                                                                   │
 * │  On first enable(), a persistent background agent is installed to the user's     │
 * │  home directory — exactly like Chrome's Keystone auto-updater. No admin dialog, │
 * │  no password prompt. launchd starts it at every login automatically.             │
 * │                                                                                   │
 * │  The agent (everstatus-agent.sh) watches a heartbeat file in $HOME and runs     │
 * │  caffeinate -d -i -m -s while EverStatus is active. If EverStatus crashes,      │
 * │  the agent stops caffeinate after 180 s (heartbeat timeout) and launchd         │
 * │  keeps the agent itself alive for the next session.                              │
 * │                                                                                   │
 * │  Coverage:                                                                        │
 * │    AC power:      all sleep prevented, including lid-close          ✅ Full      │
 * │    Battery power: idle/display/disk sleep prevented                 ✅ Partial   │
 * │                   lid-close sleep enforced by macOS 10.14+ kernel   ⚠  (below)  │
 * │                                                                                   │
 * │  Why lid-close on battery cannot be fixed here:                                  │
 * │    The only API that prevents battery+lid-close sleep is                         │
 * │    IOPMSetSystemPowerSetting(kIOPMSleepDisabledKey). This is root-only at the   │
 * │    kernel level — even a LaunchAgent running as the current user cannot call it. │
 * │    The private entitlement com.apple.private.iokit.assertonlidclose that would  │
 * │    allow this for non-root processes is available only to Apple's own apps.      │
 * │    caffeinate -s (IOPMAssertPreventSystemSleep) is AC-only by design, even as   │
 * │    root. There is no public, zero-admin path around this on macOS 10.14+.       │
 * │                                                                                   │
 * ├───────────────────────────────────────────────────────────────────────────────────┤
 * │  Windows — SetThreadExecutionState (no admin)                                    │
 * │                                                                                   │
 * │  A background PowerShell loop calls SetThreadExecutionState with:                │
 * │    ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_AWAYMODE_REQUIRED (0x80000041)        │
 * │                                                                                   │
 * │  Coverage:                                                                        │
 * │    S3 (traditional sleep) laptops: Away Mode keeps the system running on        │
 * │      lid-close — screen off, quiet, but OS fully active  ✅ Full                │
 * │    Modern Standby (S0) laptops: OS terminates all power requests on lid-close   │
 * │      on battery — no user-space workaround exists         ⚠  Partial            │
 * └───────────────────────────────────────────────────────────────────────────────────┘
 */
@Service
public class SleepPreventionService {

    private static final String OS = System.getProperty("os.name", "").toLowerCase();

    // macOS agent paths  — all under the user's home directory, zero admin
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
    private Coverage coverage = Coverage.KEY_ONLY;
    private boolean  shutdownHookAdded;

    // ─── Public API ──────────────────────────────────────────────────────────────

    public synchronized void enable() {
        if (sleepProcess != null && sleepProcess.isAlive()) return;

        if (!shutdownHookAdded) {
            Runtime.getRuntime().addShutdownHook(
                new Thread(this::disable, "sleep-prevention-shutdown"));
            shutdownHookAdded = true;
        }

        try {
            if (OS.contains("win")) {
                sleepProcess = startWindowsSleepPrevention();
            } else if (OS.contains("mac") || OS.contains("darwin")) {
                sleepProcess = startMacSleepPrevention();
            }
        } catch (Exception e) {
            System.err.println("[SleepPrevention] Failed to start: " + e.getMessage());
        }
    }

    public synchronized void disable() {
        stopHeartbeat();
        if (sleepProcess != null) {
            sleepProcess.destroy();
            sleepProcess = null;
        }
        if (OS.contains("mac") || OS.contains("darwin")) {
            // Remove heartbeat file — agent will stop caffeinate within STALENESS_SECS
            silentDelete(Paths.get(MAC_CONTROL_FILE));
        }
        coverage = Coverage.KEY_ONLY;
    }

    public Coverage getCoverage() { return coverage; }

    // ─── macOS ───────────────────────────────────────────────────────────────────

    private Process startMacSleepPrevention() throws Exception {
        boolean onAC = isMacOnACPower();
        coverage = onAC ? Coverage.FULL : Coverage.PARTIAL;

        // Install the LaunchAgent to ~/Library/LaunchAgents/ — zero admin, like Chrome's Keystone
        installMacAgentIfNeeded();

        // Touch the heartbeat file — the agent will see it and start caffeinate
        touchFile(MAC_CONTROL_FILE);
        startHeartbeat(MAC_CONTROL_FILE);

        System.out.println("[SleepPrevention] macOS: "
            + (onAC ? "AC power — full coverage" : "Battery — idle sleep prevented; lid-close is OS-enforced")
            + " | LaunchAgent: " + (Files.exists(Paths.get(MAC_PLIST)) ? "installed" : "pending"));

        // Also keep a direct caffeinate process from the Java app as belt-and-suspenders
        // (covers the window before the agent detects the heartbeat file)
        return new ProcessBuilder("caffeinate", "-d", "-i", "-m", "-s").start();
    }

    /**
     * Installs the sleep prevention agent to ~/Library/LaunchAgents/ with zero admin.
     * Equivalent to Chrome installing Keystone to ~/Library/LaunchAgents/ —
     * all paths are user-writable, launchctl load requires no elevated privileges.
     * Safe to call on every launch; exits immediately if already installed.
     */
    private void installMacAgentIfNeeded() {
        if (isAgentLoaded()) return;

        try {
            // 1. Create support directories (all in ~/Library — user-writable, no admin)
            Files.createDirectories(Paths.get(MAC_SUPPORT_DIR));
            Files.createDirectories(Paths.get(MAC_AGENTS_DIR));

            // 2. Extract and deploy the agent shell script
            Path scriptTmp = extractResource("everstatus-agent.sh");
            Path scriptDest = Paths.get(MAC_AGENT_SCRIPT);
            Files.copy(scriptTmp, scriptDest, StandardCopyOption.REPLACE_EXISTING);
            silentDelete(scriptTmp);
            scriptDest.toFile().setExecutable(true, false);

            // 3. Write the LaunchAgent plist (generated dynamically with actual paths)
            Files.writeString(Paths.get(MAC_PLIST), buildAgentPlist());

            // 4. Load the agent — launchctl load/bootstrap on ~/Library/LaunchAgents/
            //    requires no admin, same as Chrome's Keystone registration
            int rc = loadAgent();
            if (rc == 0) {
                System.out.println("[SleepPrevention] macOS LaunchAgent installed and loaded "
                    + "(~/Library/LaunchAgents/" + MAC_DAEMON_LABEL + ".plist) — no admin used");
            } else {
                System.err.println("[SleepPrevention] LaunchAgent load returned rc=" + rc
                    + "; direct caffeinate still active as fallback");
            }
        } catch (Exception e) {
            System.err.println("[SleepPrevention] LaunchAgent install failed: " + e.getMessage()
                + " — direct caffeinate still active as fallback");
        }
    }

    /** Loads the plist using the current macOS-preferred launchctl command. */
    private int loadAgent() throws Exception {
        // Try modern bootstrap (macOS 11+) first
        String uid = getUnixUID();
        if (uid != null) {
            int rc = runSilent("launchctl", "bootstrap", "gui/" + uid, MAC_PLIST);
            if (rc == 0) return 0;
        }
        // Fall back to legacy load (macOS 10.x)
        return runSilent("launchctl", "load", "-w", MAC_PLIST);
    }

    /** Returns true if the LaunchAgent is already registered with launchd. */
    private boolean isAgentLoaded() {
        try {
            int rc = runSilent("launchctl", "list", MAC_DAEMON_LABEL);
            return rc == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String getUnixUID() {
        try {
            Process p = new ProcessBuilder("id", "-u").start();
            String uid = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return uid.matches("\\d+") ? uid : null;
        } catch (Exception e) {
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
            + "    <!-- Start at login and auto-restart on crash (like Chrome's Keystone) -->\n"
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

    private boolean isMacOnACPower() {
        try {
            Process p = new ProcessBuilder("pmset", "-g", "batt")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out.contains("AC Power");
        } catch (Exception e) {
            return true;
        }
    }

    // ─── Windows ─────────────────────────────────────────────────────────────────

    private Process startWindowsSleepPrevention() throws Exception {
        boolean modernStandby = isWindowsModernStandby();
        coverage = modernStandby ? Coverage.PARTIAL : Coverage.FULL;

        System.out.println("[SleepPrevention] Windows: "
            + (modernStandby ? "Modern Standby (S0) — idle sleep prevented; lid-close on battery is OS-terminated"
                             : "S3 sleep — Away Mode active, lid-close keeps system running"));

        // 0x80000041 = ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_AWAYMODE_REQUIRED
        String script =
            "Add-Type -MemberDefinition '[DllImport(\"kernel32.dll\")] " +
            "public static extern uint SetThreadExecutionState(uint esFlags);' " +
            "-Name 'Power' -Namespace 'Win32' -PassThru | Out-Null; " +
            "[Win32.Power]::SetThreadExecutionState(0x80000041) | Out-Null; " +
            "while ($true) { Start-Sleep -Seconds 30; " +
            "[Win32.Power]::SetThreadExecutionState(0x80000041) | Out-Null }";

        return new ProcessBuilder("powershell", "-NonInteractive", "-NoProfile", "-Command", script)
            .redirectErrorStream(true).start();
    }

    private boolean isWindowsModernStandby() {
        try {
            Process p = new ProcessBuilder("powercfg", "/a")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out.contains("S0 Low Power Idle");
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Heartbeat ───────────────────────────────────────────────────────────────

    private void startHeartbeat(String path) {
        heartbeatThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Path p = Paths.get(path);
                    if (Files.exists(p))
                        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis()));
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {}
            }
        }, "sleep-prevention-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void stopHeartbeat() {
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
    }

    // ─── Utilities ───────────────────────────────────────────────────────────────

    private void touchFile(String path) {
        try {
            Files.write(Paths.get(path), new byte[0],
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("[SleepPrevention] Could not touch " + path + ": " + e.getMessage());
        }
    }

    private void silentDelete(Path p) {
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    private Path extractResource(String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String prefix = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        try (InputStream is = getClass().getResourceAsStream("/" + name)) {
            if (is == null) throw new IOException("Resource not bundled: " + name);
            Path tmp = Files.createTempFile("everstatus-" + prefix + "-", suffix);
            Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    private int runSilent(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        return p.waitFor();
    }
}
