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
│       │   └── KeepActiveApp.java
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
- Check OS power settings (they may override the app)
- Windows: Settings > System > Power & Sleep
- Verify the app is running (status bar should update)

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

**Version**: 1.0.0
**Last Updated**: December 19, 2024
