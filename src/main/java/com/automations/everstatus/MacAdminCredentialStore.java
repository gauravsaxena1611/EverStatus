package com.automations.everstatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Stores admin credentials once in the macOS Keychain and reuses them silently
 * on every subsequent call — no password dialog after the first approval.
 *
 * WHY KEYCHAIN instead of custom file encryption
 * ───────────────────────────────────────────────
 * Any scheme that stores an encrypted blob in a file must also store the
 * decryption key somewhere the JVM can reach it, which means an attacker
 * who can read the file can find the key too.  The macOS Keychain solves
 * this at the OS level:
 *
 *   • AES-256-CBC with a master key derived from the user's login password
 *   • On T2/M-series Macs: key material lives in the Secure Enclave and
 *     never leaves hardware — physically impossible to extract
 *   • No other user, root process, or app can read the item without an
 *     explicit Allow/Always Allow from the owning user
 *   • Same backend used by Safari, Chrome, 1Password, and iCloud Keychain
 *
 * Keychain item: service=com.everstatus.admin.disablesleep, account=<username>
 *
 * CREDENTIAL RESOLUTION ORDER
 * ────────────────────────────
 *  1. In-memory cache          — fastest path, same JVM session
 *  2. macOS Keychain lookup    — survives app restarts, zero prompts
 *  3. AppleScript dialog       — first-ever use or after password change
 *     a. Show hidden-input dialog (up to 3 attempts)
 *     b. Verify via "do shell script echo ok … with administrator privileges"
 *     c. Save to Keychain on success
 */
public class MacAdminCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(MacAdminCredentialStore.class);

    // ── Keychain identity ─────────────────────────────────────────────────────
    private static final String SERVICE = "com.everstatus.admin.disablesleep";
    private static final String ACCOUNT = System.getProperty("user.name", "everstatus");

    // ── Password prompt callback ──────────────────────────────────────────────
    // Provided by KeepActiveApp and shows a native SWT dialog on the main thread.
    // Accepts the message string, returns the entered password or null if cancelled.
    // Using a callback keeps all SWT code in KeepActiveApp and all credential
    // logic here, and avoids the osascript display dialog SIGKILL issue on macOS.
    private final Function<String, String> passwordPrompt;

    // ── In-memory cache (lives only for this JVM session) ────────────────────
    private String cachedPassword = null;

    public MacAdminCredentialStore(Function<String, String> passwordPrompt) {
        this.passwordPrompt = passwordPrompt;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a verified admin password (never logged — only boolean results are logged).
     * Priority: in-memory cache → Keychain → AppleScript dialog.
     *
     * @return verified password, or {@code null} if the user cancelled all attempts
     */
    public String getOrPromptPassword() {
        log.debug("getOrPromptPassword() — thread='{}' account='{}'",
            Thread.currentThread().getName(), ACCOUNT);

        // 1. In-memory cache
        if (cachedPassword != null) {
            log.debug("Trying in-memory cached credential...");
            long t0 = System.currentTimeMillis();
            boolean valid = verifyPassword(cachedPassword);
            log.debug("In-memory cache verification took {}ms — valid={}", System.currentTimeMillis() - t0, valid);
            if (valid) {
                log.debug("Using in-memory cached credential (no Keychain or dialog needed)");
                return cachedPassword;
            }
            log.warn("In-memory cached credential is no longer valid — password changed mid-session; clearing cache");
            cachedPassword = null;
            deleteFromKeychain();
        }

        // 2. Keychain lookup
        log.debug("Checking Keychain for service='{}'...", SERVICE);
        long t0 = System.currentTimeMillis();
        String stored = readFromKeychain();
        log.debug("Keychain lookup took {}ms — found={}", System.currentTimeMillis() - t0, stored != null);

        if (stored != null) {
            log.debug("Keychain entry found — verifying...");
            t0 = System.currentTimeMillis();
            boolean valid = verifyPassword(stored);
            log.debug("Keychain credential verification took {}ms — valid={}", System.currentTimeMillis() - t0, valid);

            if (valid) {
                log.info("Keychain credential verified successfully — no dialog needed");
                cachedPassword = stored;
                return cachedPassword;
            }
            log.warn("Keychain credential is stale (password changed?) — deleting entry and falling through to dialog");
            deleteFromKeychain();
        } else {
            log.info("No Keychain entry found for service='{}' — will show password dialog", SERVICE);
        }

        // 3. AppleScript dialog
        return promptAndStore();
    }

    /**
     * Runs {@code pmset -b disablesleep <flag>} as admin using stored credentials.
     * Clears cache + Keychain and returns false if the command fails (e.g. password changed).
     *
     * @param flag "1" to disable lid sleep, "0" to re-enable
     * @return true on success
     */
    public boolean runPmset(String flag) {
        log.info("runPmset('{}') called on thread='{}'", flag, Thread.currentThread().getName());

        String password = getOrPromptPassword();
        if (password == null) {
            log.warn("runPmset('{}') — no credential available (user cancelled or 3 failed attempts)", flag);
            return false;
        }

        log.debug("Running: pmset -b disablesleep {} via osascript (credentials from {})",
            flag, cachedPassword != null ? "cache" : "keychain/dialog");
        long t0 = System.currentTimeMillis();
        boolean ok = runAsAdmin(password, "pmset -b disablesleep " + flag);
        log.info("pmset -b disablesleep {} completed in {}ms — success={}", flag, System.currentTimeMillis() - t0, ok);

        if (!ok) {
            log.warn("pmset command failed — likely password changed between verify and run; clearing credentials");
            cachedPassword = null;
            deleteFromKeychain();
        }
        return ok;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Show AppleScript password dialog (up to 3 attempts), verify, store on success. */
    private String promptAndStore() {
        log.info("Showing password dialog (up to 3 attempts) on thread='{}'", Thread.currentThread().getName());

        for (int attempt = 1; attempt <= 3; attempt++) {
            log.info("Password dialog — attempt {}/3", attempt);

            String header = attempt == 1
                ? "EverStatus needs your admin password to prevent lid-close sleep on battery.\\n\\n"
                  + "Your password will be stored in the macOS Keychain — "
                  + "you won\\'t be asked again unless your password changes."
                : "Incorrect password. Please try again (" + attempt + "/3).";

            long t0 = System.currentTimeMillis();
            String password = showPasswordDialog(header);
            log.debug("Password dialog returned in {}ms — entered={}", System.currentTimeMillis() - t0, password != null);

            if (password == null) {
                log.info("Password dialog cancelled by user on attempt {}", attempt);
                return null;
            }

            log.debug("Verifying entered password (attempt {})...", attempt);
            t0 = System.currentTimeMillis();
            boolean valid = verifyPassword(password);
            log.info("Password verification on attempt {} took {}ms — valid={}", attempt, System.currentTimeMillis() - t0, valid);

            if (valid) {
                log.info("Password verified on attempt {} — saving to Keychain", attempt);
                saveToKeychain(password);
                cachedPassword = password;
                log.info("Credential saved to Keychain and cached in memory");
                return password;
            }
            log.warn("Incorrect password on attempt {}/3", attempt);
        }

        log.error("All 3 password attempts failed — lid-close sleep prevention will not be active");
        return null;
    }

    /**
     * Delegates to the SWT password dialog callback supplied at construction time.
     * The callback runs on the SWT main thread via display.syncExec(), so this method
     * is safe to call from any background thread.
     * The returned string is NEVER logged.
     */
    private String showPasswordDialog(String message) {
        log.debug("Invoking SWT password dialog callback on calling thread='{}'",
            Thread.currentThread().getName());
        if (passwordPrompt == null) {
            log.error("No password prompt callback set — cannot show dialog (was setPasswordDialogProvider called?)");
            return null;
        }
        // The callback internally calls display.syncExec(), which blocks the calling
        // (background) thread until the user dismisses the dialog on the main thread.
        String result = passwordPrompt.apply(message);
        log.debug("SWT password dialog returned — entered={}", result != null);
        return result;
    }

    /**
     * Verifies a password by running a harmless privileged no-op.
     * Uses "user name + password" osascript params — no system dialog appears.
     * The password is NEVER logged.
     */
    private boolean verifyPassword(String password) {
        log.debug("verifyPassword() — running 'echo ok' as admin via osascript for account='{}'", ACCOUNT);
        try {
            String script = "do shell script \"echo ok\" user name "
                + asScript(ACCOUNT) + " password " + asScript(password)
                + " with administrator privileges";
            Process p = new ProcessBuilder("osascript", "-e", script)
                .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int rc = p.waitFor();
            log.debug("verifyPassword osascript rc={} output='{}'", rc, output);
            return rc == 0;
        } catch (Exception e) {
            log.error("verifyPassword() exception: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Runs a shell command as admin using the supplied password.
     * No system dialog appears. The password is NEVER logged — only the command is.
     */
    private boolean runAsAdmin(String password, String shellCmd) {
        log.debug("runAsAdmin() — shellCmd='{}' account='{}'", shellCmd, ACCOUNT);
        try {
            String safeCmd = shellCmd.replace("\\", "\\\\").replace("\"", "\\\"");
            String script = "do shell script \"" + safeCmd + "\" user name "
                + asScript(ACCOUNT) + " password " + asScript(password)
                + " with administrator privileges";
            Process p = new ProcessBuilder("osascript", "-e", script)
                .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int rc = p.waitFor();
            log.debug("runAsAdmin osascript rc={} output='{}'", rc, output);
            return rc == 0;
        } catch (Exception e) {
            log.error("runAsAdmin('{}') exception: {}", shellCmd, e.getMessage(), e);
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Keychain I/O  (uses the `security` CLI — ships with every macOS)
    // ─────────────────────────────────────────────────────────────────────────

    private String readFromKeychain() {
        log.debug("readFromKeychain() — security find-generic-password -a '{}' -s '{}'", ACCOUNT, SERVICE);
        try {
            Process p = new ProcessBuilder(
                "security", "find-generic-password",
                "-a", ACCOUNT, "-s", SERVICE, "-w"
            ).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int rc = p.waitFor();
            // rc=44 = item not found, rc=0 = success
            log.debug("security find-generic-password rc={} hasValue={}", rc, !out.isEmpty());
            return (rc == 0 && !out.isEmpty()) ? out : null;
        } catch (Exception e) {
            log.error("readFromKeychain() exception: {}", e.getMessage(), e);
            return null;
        }
    }

    private void saveToKeychain(String password) {
        log.debug("saveToKeychain() — security add-generic-password -a '{}' -s '{}'", ACCOUNT, SERVICE);
        try {
            // -U = update existing item if present, create new one if not
            Process p = new ProcessBuilder(
                "security", "add-generic-password",
                "-a", ACCOUNT, "-s", SERVICE,
                "-l", "EverStatus admin credential",
                "-w", password,
                "-U"
            ).redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            int rc = p.waitFor();
            if (rc == 0) {
                log.info("Credential saved to Keychain (service='{}', account='{}')", SERVICE, ACCOUNT);
            } else {
                log.error("Keychain save returned rc={} — credential will be re-prompted on next launch", rc);
            }
        } catch (Exception e) {
            log.error("saveToKeychain() exception: {}", e.getMessage(), e);
        }
    }

    private void deleteFromKeychain() {
        log.debug("deleteFromKeychain() — security delete-generic-password -a '{}' -s '{}'", ACCOUNT, SERVICE);
        try {
            Process p = new ProcessBuilder(
                "security", "delete-generic-password",
                "-a", ACCOUNT, "-s", SERVICE
            ).redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            int rc = p.waitFor();
            log.debug("security delete-generic-password rc={}", rc);
        } catch (Exception e) {
            log.debug("deleteFromKeychain() exception (may not exist): {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  AppleScript string encoding
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Encodes an arbitrary Java string as a valid AppleScript string expression.
     * Handles {@code "} via {@code & quote &} splitting; backslash is literal in AppleScript.
     *
     * <pre>
     *   pass"word  →  "pass" & quote & "word"
     * </pre>
     */
    private static String asScript(String value) {
        if (value == null) return "\"\"";
        String[] parts = value.split("\"", -1);
        if (parts.length == 1) return "\"" + value + "\"";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(" & quote & ");
            sb.append('"').append(parts[i]).append('"');
        }
        return sb.toString();
    }
}
