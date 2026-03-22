# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

EverStatus is a Java desktop application that prevents system sleep by simulating F15 key presses at 60-second intervals. It uses Spring Boot for dependency injection and SWT (Eclipse Standard Widget Toolkit) for the GUI.

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
```

## Architecture

**Entry Point Flow:**
- `Application.java` → Spring Boot entry point, implements `CommandLineRunner`
- `KeepActiveApp.java` → Main service class containing all UI and logic (single 663-line class)

**Key Technical Decisions:**
- Uses `java.awt.Robot` for key simulation (not Spring scheduling)
- Uses `java.util.Timer` for scheduling, not Spring's `@Scheduled`
- SWT's `display.asyncExec()` pattern for thread-safe UI updates from timer threads
- 12-hour time format with AM/PM picker for end time selection
- `SleepPreventionService` uses a two-layer strategy on Windows and `caffeinate` on macOS (see below)

**Sleep Prevention Strategy (`SleepPreventionService.java`):**
No admin rights required. Coverage depends on platform and power source:

| Scenario | Coverage | Mechanism |
|---|---|---|
| Idle sleep — any platform, AC or battery | ✅ Full | caffeinate -i (macOS) / ES_SYSTEM_REQUIRED (Windows) |
| Lid-close — macOS on AC | ✅ Full | caffeinate -s → IOPMAssertPreventSystemSleep (AC-only) |
| Lid-close — Windows S3 (traditional sleep) | ✅ Full | ES_AWAYMODE_REQUIRED → Away Mode keeps system running |
| Lid-close — Windows Modern Standby, battery | ⚠️ Partial | OS terminates all power requests on user-initiated sleep; no user-space fix |
| Lid-close — macOS battery (10.14+) | ⚠️ Partial | macOS enforces this at kernel level; IOPMAssertPreventSystemSleep is AC-only even as root |

- `SleepPreventionService.getCoverage()` returns `FULL` or `PARTIAL` after `enable()` is called
- Windows sleep mode (Modern Standby vs S3) detected via `powercfg /a` (no admin)
- macOS power source (AC vs battery) detected via `pmset -g batt` (no admin)
- Coverage shown in the UI status bar when PARTIAL

**Platform-Specific SWT:**
Maven profiles auto-activate based on OS to include correct SWT native bindings:
- `windows` → `org.eclipse.swt.win32.win32.x86_64`
- `macos-x86_64` → `org.eclipse.swt.cocoa.macosx.x86_64`
- `macos-aarch64` → `org.eclipse.swt.cocoa.macosx.aarch64`
- `linux` → `org.eclipse.swt.gtk.linux.x86_64`

## Configuration

Settings in `src/main/resources/application.properties`:
- `everstatus.timer.interval` - Key press interval (default: 60000ms)
- `everstatus.shutdown.delay` - Grace period before auto-close (default: 3000ms)
- `everstatus.key.code` - Key to simulate (default: VK_F15)

## UI Color Scheme (Dark Theme)

The application uses a Catppuccin-inspired dark theme:
- Background: RGB(30, 30, 46)
- Card panels: RGB(49, 50, 68)
- Input fields: RGB(69, 71, 90)
- Accent (sky blue): RGB(137, 180, 250)
- Start button (green): RGB(166, 227, 161)
- Stop button (pink): RGB(243, 139, 168)

## Requirements

- Java 17+
- Maven 3.6+ (wrapper included)
