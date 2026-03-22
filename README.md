# EverStatus - Keep Awake Application

A Java desktop application that prevents your system from going to sleep by simulating keyboard activity at regular intervals.

## Features

- **Modern UI**: Dark theme with intuitive two-panel layout
- **12-Hour Time Format**: Easy time selection with AM/PM picker
- **Calendar Integration**: Visual date picker for scheduling
- **Duration Presets**: Quick selection (15 min to 12 hours)
- **Real-time Status**: Live preview of end time and remaining duration
- **Non-intrusive**: Uses F15 key (doesn't interfere with your work)
- **Auto-shutdown**: Gracefully closes when end time is reached
- **System Sleep Prevention**: Prevents idle sleep and lid-close sleep without requiring admin rights (see [Sleep Prevention](#sleep-prevention) below)

## Requirements

- **Java**: JRE 17 or higher
- **Operating System**: Windows (primary), Linux, macOS
- **RAM**: 512 MB minimum

## How to Run

### Option 1: Using Launcher Scripts (Easiest)

**Windows:**
```batch
runEverStatus.bat
```

**Linux/Mac:**
```bash
chmod +x runEverStatus.sh
./runEverStatus.sh
```

### Option 2: Direct JAR Execution
```bash
java -jar target/activetrack-1.0.0.jar
```

## How to Use

1. **Set End Time**: Use the time picker to select when the app should stop (HH:MM AM/PM)
2. **Select Date**: Choose the end date from the calendar
3. **Or Use Duration**: Select a preset duration from the dropdown (automatically updates time and date)
4. **Preview**: The status bar shows your selected end time and remaining duration
5. **Click Start**: Application begins simulating keyboard activity every minute
6. **Automatic Stop**: App closes automatically when end time is reached, or click Stop to end manually

## Technical Details

### Architecture
- **Framework**: Spring Boot 3.0.0
- **GUI**: SWT (Eclipse Standard Widget Toolkit)
- **Language**: Java 17
- **Build Tool**: Maven

### Key Components
- `Application.java` - Spring Boot entry point
- `KeepActiveApp.java` - Main application logic and UI
- `SleepPreventionService.java` - Cross-platform OS-level sleep prevention
- `application.properties` - Configuration settings

### Configuration (application.properties)

```properties
# Timer interval (milliseconds) - how often to simulate key press
everstatus.timer.interval=60000

# Shutdown delay (milliseconds) - grace period before closing
everstatus.shutdown.delay=3000

# Key code to simulate (VK_F15 is non-intrusive)
everstatus.key.code=VK_F15

# UI dimensions
everstatus.ui.window.width=800
everstatus.ui.window.height=580
```

## Building from Source

### Prerequisites
- JDK 17 or higher
- Maven 3.6+

### Build Steps

```bash
# Clean and build
./mvnw clean package

# Skip tests for faster build
./mvnw clean package -DskipTests

# Run the application
java -jar target/activetrack-1.0.0.jar
```

## Project Structure

```
everstatus/
├── src/
│   └── main/
│       ├── java/com/automations/everstatus/
│       │   ├── Application.java
│       │   ├── KeepActiveApp.java
│       │   └── SleepPreventionService.java
│       └── resources/
│           └── application.properties
├── target/
│   └── activetrack-1.0.0.jar
├── pom.xml
├── mvnw / mvnw.cmd (Maven wrapper)
├── runEverStatus.bat / .sh (Launch scripts)
└── README.md
```

## How It Works

1. **Keyboard Simulation**: Every 60 seconds, the app simulates pressing the F15 key
2. **System Activity**: This registers as user activity, preventing screen lock/sleep
3. **Non-intrusive**: F15 key has no effect on most applications
4. **Scheduled Stop**: Monitors system time and stops automatically at the specified end time
5. **OS-Level Sleep Prevention**: In addition to key simulation, a platform-specific background process actively prevents the OS from sleeping (see [Sleep Prevention](#sleep-prevention))

---

## Sleep Prevention

EverStatus uses a dedicated `SleepPreventionService` that runs alongside key simulation to prevent sleep at the OS level. No administrator rights or special privileges are required.

### How it works per platform

**Windows**
A background PowerShell process calls `SetThreadExecutionState` with three flags:
- `ES_CONTINUOUS` — keeps the state persistent until the app stops
- `ES_SYSTEM_REQUIRED` — prevents idle/timeout sleep
- `ES_AWAYMODE_REQUIRED` — on S3-sleep laptops, triggers **Away Mode** on lid close: the screen turns off and the machine is quiet, but the OS keeps running (network, CPU, Teams presence maintained)

The sleep mode is auto-detected at startup using `powercfg /a` (read-only, no admin).

**macOS — Chrome-like LaunchAgent (zero admin)**

On first start, EverStatus installs a persistent background agent to the user's home directory — the same pattern Chrome uses for its Keystone auto-updater. No admin, no password dialog:

| What gets installed | Where | Admin needed? |
|---|---|---|
| Agent shell script | `~/Library/Application Support/EverStatus/everstatus-agent.sh` | No |
| LaunchAgent plist | `~/Library/LaunchAgents/com.everstatus.agent.plist` | No |

Loaded via `launchctl bootstrap gui/<uid>` — a user-space operation. launchd starts the agent at every login and restarts it automatically if it crashes.

The agent watches a heartbeat file (`~/.everstatus.active`) that EverStatus updates every 60 s, and runs `caffeinate -d -i -m -s` while the heartbeat is fresh:
- `-i` — prevents idle sleep on battery (`IOPMAssertPreventUserIdleSystemSleep`)
- `-s` — prevents system sleep on AC power (`IOPMAssertPreventSystemSleep`)
- `-d` — prevents display sleep
- `-m` — prevents disk idle sleep

The power source (AC vs battery) is auto-detected using `pmset -g batt` (read-only, no admin).

### Coverage by scenario

| Scenario | Coverage | Details |
|---|---|---|
| Idle sleep — any platform, AC or battery | ✅ Full | Fully prevented |
| Lid-close — macOS on AC power | ✅ Full | `caffeinate -s` blocks system sleep |
| Lid-close — Windows S3 (traditional sleep) | ✅ Full | Away Mode keeps system running |
| Lid-close — Windows Modern Standby on battery | ⚠️ Partial | OS terminates all power requests on lid close (Microsoft design decision) |
| Lid-close — macOS on battery (10.14+) | ⚠️ Partial | macOS enforces this at the kernel level regardless of user-space assertions |

When coverage is partial, the status bar displays:
> *"Note: lid-close on battery may still sleep (OS restriction)"*

### Why the two partial cases cannot be fixed without admin

**Windows Modern Standby**: Microsoft's documentation explicitly states that on Modern Standby systems, all power requests — including `PowerRequestAwayModeRequired` — are terminated by the OS when the user closes the lid on battery. This is an intentional OS design decision. Changing it requires `powercfg` which needs administrator rights.

**macOS battery + lid-close**: The only API that prevents lid-close sleep on battery is `IOPMSetSystemPowerSetting(kIOPMSleepDisabledKey)`. This is root-only at the kernel level. The Chrome-like LaunchAgent approach was fully explored: a LaunchAgent runs as the current user (not root) so it has identical restrictions to caffeinate. The private entitlement `com.apple.private.iokit.assertonlidclose` that would allow this for non-root processes is reserved for Apple's own apps and unavailable to third-party developers. `caffeinate -s` (`IOPMAssertPreventSystemSleep`) is AC-only by design, even when run as root. Battery Toolkit solves this with a root daemon installed via one-time admin authorization — which EverStatus intentionally avoids.

## End-to-End User Flow

No admin prompt, no password dialog — ever. EverStatus uses only user-space APIs.

### Windows

1. Launch the JAR or `runEverStatus.bat` — no UAC prompt
2. Pick a duration or end time, click **START**
3. A hidden PowerShell process calls `SetThreadExecutionState(0x80000041)` in a background loop
4. **S3 (traditional sleep) laptop**: full coverage — lid-close keeps the system running via Away Mode
5. **Modern Standby (S0) laptop**: partial coverage — the status bar shows the note below

### macOS

1. Launch the JAR or `./runEverStatus.sh` — no password prompt
2. Pick a duration or end time, click **START**
3. Power source detected silently via `pmset -g batt` (read-only, no admin)
4. A LaunchAgent is installed to `~/Library/LaunchAgents/` (user-writable, zero admin) — persists across reboots
5. `caffeinate -d -i -m -s` is also started directly as a child process (belt-and-suspenders until the agent picks up the heartbeat)
6. **On AC power**: full coverage including lid-close
7. **On battery**: partial coverage — status bar shows the note below

### What the status bar shows

| State | Top line | Bottom line |
|---|---|---|
| Before start | `Will end at 3:00 PM on Mar 22` | `Duration: 2h 30m` |
| Running — full coverage | `Active until 3:00 PM on Mar 22, 2026` | `Time remaining: 2h 28m` |
| Running — partial coverage | `Active until 3:00 PM on Mar 22, 2026` | `Note: lid-close on battery may still sleep (OS restriction)` |
| Session complete | `Session Complete` | `Closing in 3 seconds...` |

> **Partial coverage** means idle sleep is fully prevented. Only lid-close on battery falls into an OS-enforced restriction — keeping the laptop plugged in gives full prevention on both platforms.

---

## Use Cases

- **Presentations**: Keep screen active during long presentations
- **Downloads**: Prevent sleep during large file downloads
- **Remote Sessions**: Maintain active connection status
- **Monitoring**: Keep dashboards and monitoring screens active
- **Testing**: Maintain system activity for long-running tests

## Troubleshooting

### Application won't start
- Verify Java 17+ is installed: `java -version`
- Check if JAR exists in target folder
- Try rebuilding: `./mvnw clean package`

### System still goes to sleep
- Check the status bar — if it shows *"Note: lid-close on battery may still sleep (OS restriction)"*, your platform falls into one of the two cases that require admin to fully resolve (see [Sleep Prevention](#sleep-prevention))
- **Windows idle sleep**: Verify the app is running; the PowerShell background process should be active
- **macOS on AC**: `caffeinate` should be running — check with `pgrep caffeinate` in Terminal
- **Lid-close on battery**: This is an OS-enforced restriction on Modern Standby Windows and macOS 10.14+; keeping the laptop plugged in gives full prevention

### Calendar not displaying properly
- Ensure SWT library for your OS is correctly configured in pom.xml
- Windows 64-bit: `org.eclipse.swt.win32.win32.x86_64`
- Linux: `org.eclipse.swt.gtk.linux.x86_64`
- macOS: `org.eclipse.swt.cocoa.macosx.x86_64`

## License

[Specify your license here]

## Author

Automations Team

---

**Version**: 1.1.0
**Last Updated**: March 2026
