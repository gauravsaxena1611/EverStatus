package com.automations.everstatus;

import org.springframework.stereotype.Service;

/**
 * Prevents the system from sleeping (including on lid close) while EverStatus is active.
 *
 * Windows: Uses SetThreadExecutionState via a background PowerShell process.
 *   ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_AWAYMODE_REQUIRED (0x80000041)
 *   - Keeps system fully awake through lid close
 *   - Allows display to turn off (saves power)
 *   - No admin rights required
 *
 * macOS: Uses the built-in `caffeinate` utility.
 *   caffeinate -s -i
 *   - -s: prevents system sleep (covers lid close)
 *   - -i: prevents idle sleep (works on battery too)
 *   - No admin rights required, ships with every Mac
 *
 * Other platforms: no-op (graceful fallback).
 */
@Service
public class SleepPreventionService {

    private static final String OS = System.getProperty("os.name", "").toLowerCase();

    private Process sleepProcess;

    public void enable() {
        if (sleepProcess != null && sleepProcess.isAlive()) {
            return;
        }
        try {
            if (OS.contains("win")) {
                sleepProcess = startWindowsSleepPrevention();
            } else if (OS.contains("mac") || OS.contains("darwin")) {
                sleepProcess = startMacSleepPrevention();
            }
            // Linux/other: key simulation alone is sufficient on most desktop environments
        } catch (Exception e) {
            System.err.println("[SleepPrevention] Failed to start sleep prevention: " + e.getMessage());
        }
    }

    public void disable() {
        if (sleepProcess != null) {
            sleepProcess.destroy();
            sleepProcess = null;
        }
    }

    /**
     * Launches a PowerShell process that:
     * 1. Loads kernel32.dll via Add-Type
     * 2. Calls SetThreadExecutionState(0x80000041) — ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_AWAYMODE_REQUIRED
     * 3. Loops indefinitely (so the thread stays alive and the state is maintained)
     *
     * When the process is destroyed, Windows automatically clears the execution state.
     */
    private Process startWindowsSleepPrevention() throws Exception {
        // 0x80000041 = ES_CONTINUOUS (0x80000000) | ES_SYSTEM_REQUIRED (0x00000001) | ES_AWAYMODE_REQUIRED (0x00000040)
        // ES_AWAYMODE_REQUIRED: system stays fully active (lid close, idle) but display can turn off
        String script =
            "Add-Type -MemberDefinition '[DllImport(\"kernel32.dll\")] " +
            "public static extern uint SetThreadExecutionState(uint esFlags);' " +
            "-Name 'Power' -Namespace 'Win32' -PassThru | Out-Null; " +
            "[Win32.Power]::SetThreadExecutionState(0x80000041) | Out-Null; " +
            "while ($true) { Start-Sleep -Seconds 30 }";

        return new ProcessBuilder(
            "powershell", "-NonInteractive", "-NoProfile", "-Command", script
        ).redirectErrorStream(true).start();
    }

    /**
     * Launches caffeinate (built into macOS) which prevents system and idle sleep.
     * The process keeps running until destroyed, at which point the prevention is lifted.
     */
    private Process startMacSleepPrevention() throws Exception {
        return new ProcessBuilder("caffeinate", "-s", "-i")
            .start();
    }
}