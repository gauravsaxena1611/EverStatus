# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

EverStatus is a Java desktop application that prevents system sleep by simulating F13 key presses at 60-second intervals. It uses Spring Boot for dependency injection and SWT (Eclipse Standard Widget Toolkit) for the GUI.

## Build Commands

```bash
# Build executable JAR (auto-detects platform for SWT bindings)
./mvnw clean package

# Build without tests
./mvnw clean package -DskipTests

# Run the application
java -jar target/activetrack-1.0.0.jar

# Windows launcher
runEverStatus.bat

# Unix launcher (requires chmod +x first)
./runEverStatus.sh

# Portable .app / .exe distribution (macOS — run on Mac)
chmod +x build-distribution.sh && ./build-distribution.sh

# Portable distribution (Windows — run on Windows)
build-distribution.bat
```

## macOS: Required JVM flag

SWT on macOS (Cocoa) requires the Display to be created on the main thread.
**Always add `-XstartOnFirstThread` to VM options** when running from IntelliJ or any launcher:

```
-XstartOnFirstThread
```

The shell launchers (`runEverStatus.sh`, `build-distribution.sh`) already include this flag.
For IntelliJ: Run → Edit Configurations → VM options → add `-XstartOnFirstThread`.

## Architecture

**Entry Point Flow:**
- `Application.java` → Spring Boot entry point, implements `CommandLineRunner`. Runs the following in `main()` **before** `SpringApplication.run()`:
  1. `detectLogDirectory()` — detects log folder next to the distributed app; sets `everstatus.log.dir` system property for Logback
  2. `clearPreviousLogs()` — deletes all `*.log` files in the log dir so each launch has a clean slate
  3. `Thread.setDefaultUncaughtExceptionHandler` — catches crashes on any background thread and writes full stack trace to the log file
  4. Startup diagnostics block — logs OS, OS version, arch, Java version, vendor, java.home, user, working dir, free/total/max memory, CPU count, every connected screen with its resolution
- `KeepActiveApp.java` → Main service class containing all UI and logic; wires two callbacks into `SleepPreventionService` after `Display` is created:
  1. `setPasswordDialogProvider` — shows native SWT password dialog via `display.syncExec()` (avoids osascript SIGKILL)
  2. `setCoverageChangedCallback` — calls `display.asyncExec(this::updateCoverageLabel)` so the status label reflects live power-source changes
- `SleepPreventionService.java` → OS-level sleep prevention, delegates credential work to store; runs power monitor daemon
- `MacAdminCredentialStore.java` → macOS Keychain credential storage (one-time admin prompt via SWT callback)

**Key Technical Decisions:**
- Uses `java.awt.Robot` for key simulation (not Spring scheduling)
- Uses `java.util.Timer` for scheduling, not Spring's `@Scheduled`
- SWT's `display.asyncExec()` pattern for thread-safe UI updates from timer threads
- 12-hour time format with AM/PM picker for end time selection
- Key simulated is **F13** (not F15) — F15 triggers the macOS brightness overlay, F13 has no system mapping
- `SleepPreventionService` uses a three-layer strategy on macOS (see below)
- **Power source monitor** (`sleep-prevention-power-monitor` daemon thread) polls every 30 seconds and dynamically adjusts sleep strategy — no restart required when AC/battery or display state changes
- **Coverage-change callback** (`setCoverageChangedCallback`) wired from `KeepActiveApp` so the SWT status label updates live via `display.asyncExec()` when the power monitor detects a change
- **Log directory auto-detection** (`detectLogDirectory()` in `Application`) — writes logs next to the distributed app; path is detected at runtime from `java.home` (jpackage) or `user.dir` (JAR launcher)
- **Log cleared on each startup** (`clearPreviousLogs()`) — deletes all `*.log` files before Logback initialises; each run is self-contained with no accumulation
- **Dual log files** — `everstatus.log` (full DEBUG+) and `everstatus-errors.log` (ERROR only) written side by side; both rolled at size limit, 30-day retention
- **Global uncaught exception handler** — registered before Spring starts; any unhandled exception on any thread is written to the log file (critical for `.app` bundles where stderr is invisible)

**Sleep Prevention Strategy (`SleepPreventionService.java`):**

| Scenario | Coverage | Mechanism |
|---|---|---|
| Idle sleep — any platform, AC or battery | ✅ Full | `caffeinate -i` (macOS) / `ES_SYSTEM_REQUIRED` (Windows) |
| Lid-close — macOS on AC | ✅ Full | `caffeinate -s` → `IOPMAssertPreventSystemSleep` (AC-only) |
| Lid-close — macOS battery + external display | ✅ Full | macOS clamshell mode keeps system awake automatically |
| Lid-close — macOS battery, no external display | ✅ Full* | `pmset -b disablesleep 1` via credential store (one-time admin prompt) |
| Lid-close — Windows S3 (traditional sleep) | ✅ Full | `ES_AWAYMODE_REQUIRED` → Away Mode keeps system running |
| Lid-close — Windows Modern Standby, battery | ⚠️ Partial | OS terminates all power requests on user-initiated sleep; no user-space fix |

\* On first use: one macOS password dialog appears. Credential is saved to Keychain — never asked again unless the password changes.

**macOS Sleep Prevention — Three Layers:**
1. **Direct `caffeinate` child process** — started immediately, covers the window before the agent is up
2. **LaunchAgent** (`~/Library/LaunchAgents/com.everstatus.agent.plist`) — watches a heartbeat file, runs `caffeinate -d -i -m -s`, persists across reboots (zero admin, Chrome-Keystone pattern)
3. **`pmset -b disablesleep 1`** — kernel-level lid-close prevention on battery, executed via `MacAdminCredentialStore` (one admin prompt stored in Keychain)

**Power Source Monitor (`startPowerMonitor`):**
- Started at the end of `startMacSleepPrevention()`, after the initial scenario is set
- Daemon thread `sleep-prevention-power-monitor`, polls every 30 seconds
- Compares current `isMacOnACPower()` and `hasExternalDisplay()` against last known values
- On change: calls `adjustForPowerChange(onAC, hasExtDisplay)` which applies or removes `pmset -b disablesleep`, updates `coverage`, fires `coverageChangedCallback`
- **No logs when state is unchanged** — only logs on actual state transitions
- Stopped in `disable()` alongside the heartbeat thread

**`MacAdminCredentialStore.java` — Keychain Credential Store:**
- Stores admin password once in the macOS Keychain (`security add-generic-password`)
- Keychain item: service `com.everstatus.admin.disablesleep`, account = current username
- **In-memory cache** → fastest path, avoids Keychain lookup within the same session
- **Keychain lookup** → zero prompts on subsequent launches
- **SWT dialog callback** → password dialog shown via `Function<String, String> passwordPrompt` supplied at construction — delegates all UI to `KeepActiveApp.openPasswordDialog()` via `display.syncExec()`. This avoids the `osascript display dialog` SIGKILL (exit 137) that occurs when AppleScript UI is launched from a background thread while SWT owns the Cocoa main thread.
- Password verified against the OS before being stored (runs `echo ok` as admin via `do shell script ... with administrator privileges`)
- If stored password is stale (password changed), Keychain entry is cleared and a new prompt appears

**Detection helpers (all zero-admin, read-only):**
- `isMacOnACPower()` — `pmset -g batt` output contains "AC Power"
- `hasExternalDisplay()` — counts `Resolution:` entries in `system_profiler SPDisplaysDataType`; >1 means external display connected
- `isWindowsModernStandby()` — `powercfg /a` output contains "S0 Low Power Idle"

**Windows Compatibility — Known Pitfalls (already fixed, do not revert):**

1. **`build-distribution.bat` `--main-class`** — Spring Boot fat JARs nest application classes under `BOOT-INF/classes/`. The correct value is `org.springframework.boot.loader.JarLauncher` — the Spring Boot launcher that unpacks `BOOT-INF/` before delegating to the real main class.

2. **Launcher scripts must `cd` to their own directory** — `detectLogDirectory()` relies on `user.dir` to place logs next to the JAR. Fix: `runEverStatus.sh` runs `cd "$SCRIPT_DIR"` and `runEverStatus.bat` runs `cd /d "%~dp0"` before invoking `java`.

3. **Hardcoded `/` path separator** — All path construction uses `Paths.get()` so the native separator is always used.

4. **`logback-spring.xml` fallback log path** — Uses `${user.home}/everstatus-logs` which is a valid cross-platform path.

5. **Build script icon guard** — If the icon file is missing, both scripts now warn gracefully and continue without an icon rather than failing.

6. **macOS-only code correctly gated** — `caffeinate`, `launchctl`, `pmset`, `system_profiler`, `osascript`, `MacAdminCredentialStore`, and the power monitor daemon are all inside `if (os.contains("mac"))` branches. Never reachable on Windows.

7. **SWT Color resource leaks** — `darkFgColor` and `darkGreenFgColor` are stored as class fields, initialised once, and disposed in the `SWT.Close` listener. Short-lived colours created inside methods are stored as local variables and disposed at the end of that method.

**Platform-Specific SWT:**
Maven profiles auto-activate based on OS to include correct SWT native bindings:
- `windows` → `org.eclipse.swt.win32.win32.x86_64`
- `macos-x86_64` → `org.eclipse.swt.cocoa.macosx.x86_64`
- `macos-aarch64` → `org.eclipse.swt.cocoa.macosx.aarch64`
- `linux` → `org.eclipse.swt.gtk.linux.x86_64`

## Logging

### Log directory detection (`Application.detectLogDirectory`)

| Runtime context | How detected | Log location |
|---|---|---|
| jpackage macOS `.app` | `java.home` contains `.app/Contents/runtime` — walk up to `.app`, use parent | `<.app parent>/logs/` |
| jpackage Windows | `java.home` ends with `\runtime` — use parent (install dir) | `<install dir>/logs/` |
| JAR + launcher script | `user.dir` = directory of the script/jar | `<jar dir>/logs/` |
| Fallback (IDE) | None of the above | `~/Library/Logs/EverStatus` (macOS) / `~/everstatus-logs` |

### Log files (`logback-spring.xml`)

| File | Level filter | Max size | Retention |
|---|---|---|---|
| `everstatus.log` | DEBUG and above | 10 MB | 30 days |
| `everstatus-errors.log` | ERROR only | 5 MB | 30 days |

## Configuration

Settings in `src/main/resources/application.properties`:
- `everstatus.timer.interval` — Key press interval (default: `60000` ms)
- `everstatus.shutdown.delay` — Grace period before auto-close (default: `3000` ms)
- `everstatus.key.code` — Key to simulate (default: `VK_F13`; F13 has no system mapping on macOS or Windows)

## Project Structure

```
src/main/java/com/automations/everstatus/
├── Application.java               — Spring Boot entry point
├── KeepActiveApp.java             — All UI + timer logic (SWT)
├── SleepPreventionService.java    — OS-level sleep prevention
└── MacAdminCredentialStore.java   — macOS Keychain credential store (macOS only)

src/main/resources/
├── application.properties         — Runtime configuration
├── logback-spring.xml             — Logback file appender configuration
└── everstatus-agent.sh            — Bundled LaunchAgent shell script (macOS)
```

## UI Color Scheme (Dark Theme)

The application uses a Catppuccin-inspired dark theme:
- Background: `RGB(30, 30, 46)`
- Card panels: `RGB(49, 50, 68)`
- Input fields: `RGB(69, 71, 90)`
- Accent (sky blue): `RGB(137, 180, 250)`
- Start button (green): `RGB(166, 227, 161)`
- Stop button (pink): `RGB(243, 139, 168)`

## Requirements

- Java 17+
- Maven 3.6+ (wrapper included)
- macOS: `-XstartOnFirstThread` JVM flag required (already in all launchers)
