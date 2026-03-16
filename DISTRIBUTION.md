# EverStatus Distribution Guide

This guide explains how to package and distribute EverStatus for Windows and macOS systems.

## Prerequisites

- Java 17 or higher installed on the build machine
- Maven (or use the included Maven wrapper `mvnw`)

## Building for Distribution

### Option 1: Build on the Target Platform (Recommended)

For best compatibility, build the JAR on each target platform:

#### On Windows:
```bash
.\mvnw.cmd clean package
```

#### On macOS/Linux:
```bash
./mvnw clean package
```

This will create `target/activetrack-1.0.0.jar` with the correct SWT library for your platform.

### Option 2: Build All Platforms (Advanced)

To build JARs for all platforms from a single machine, you'll need to manually activate profiles:

#### For Windows build:
```bash
./mvnw clean package -P windows
```

#### For macOS Intel (x86_64):
```bash
./mvnw clean package -P macos-x86_64
```

#### For macOS Apple Silicon (ARM64):
```bash
./mvnw clean package -P macos-aarch64
```

#### For Linux:
```bash
./mvnw clean package -P linux
```

## Distribution Package Structure

Create a distribution folder with the following structure:

```
EverStatus-1.0.0/
├── activetrack-1.0.0.jar       (The executable JAR)
├── runEverStatus.bat            (Windows launcher)
├── runEverStatus.sh             (macOS/Linux launcher)
├── ES.ico                       (Application icon - Windows)
├── ES.png                       (Application icon - macOS)
└── README.md                    (User instructions)
```

## Steps to Create Distribution Packages

### For Windows Users:

1. **Build the JAR** (on Windows machine):
   ```bash
   .\mvnw.cmd clean package
   ```

2. **Create distribution folder**:
   ```bash
   mkdir dist\EverStatus-Windows-1.0.0
   copy target\activetrack-1.0.0.jar dist\EverStatus-Windows-1.0.0\
   copy runEverStatus.bat dist\EverStatus-Windows-1.0.0\
   copy ES.ico dist\EverStatus-Windows-1.0.0\
   copy README.md dist\EverStatus-Windows-1.0.0\
   ```

3. **Create ZIP archive**:
   ```bash
   cd dist
   tar -a -c -f EverStatus-Windows-1.0.0.zip EverStatus-Windows-1.0.0
   ```

4. **Distribute**: Share the `EverStatus-Windows-1.0.0.zip` file

### For macOS Users:

1. **Build the JAR** (on macOS machine):
   ```bash
   ./mvnw clean package
   ```

2. **Create distribution folder**:
   ```bash
   mkdir -p dist/EverStatus-macOS-1.0.0
   cp target/activetrack-1.0.0.jar dist/EverStatus-macOS-1.0.0/
   cp runEverStatus.sh dist/EverStatus-macOS-1.0.0/
   cp ES.png dist/EverStatus-macOS-1.0.0/
   cp README.md dist/EverStatus-macOS-1.0.0/
   chmod +x dist/EverStatus-macOS-1.0.0/runEverStatus.sh
   ```

3. **Create ZIP archive**:
   ```bash
   cd dist
   zip -r EverStatus-macOS-1.0.0.zip EverStatus-macOS-1.0.0
   ```

4. **Distribute**: Share the `EverStatus-macOS-1.0.0.zip` file

## User Installation Instructions

### Windows:
1. Extract the ZIP file to any location
2. Ensure Java 17+ is installed (download from https://adoptium.net/)
3. Double-click `runEverStatus.bat` to launch the application
4. Optionally, create a desktop shortcut to `runEverStatus.bat`

### macOS:
1. Extract the ZIP file to any location
2. Ensure Java 17+ is installed:
   ```bash
   brew install openjdk@17
   ```
3. Open Terminal, navigate to the extracted folder, and run:
   ```bash
   chmod +x runEverStatus.sh
   ./runEverStatus.sh
   ```
4. Alternatively, double-click `runEverStatus.sh` if your system allows script execution

### Alternative: Direct JAR Execution
On any platform with Java 17+ installed:
```bash
java -jar activetrack-1.0.0.jar
```

## Creating Native Installers (Optional)

### Windows EXE (Using Launch4j)

You already have a launch4j configuration. To create a Windows EXE:

1. Install Launch4j from https://launch4j.sourceforge.net/
2. Update `launch4j configuration.xml` with correct paths:
   ```xml
   <jar>target\activetrack-1.0.0.jar</jar>
   <outfile>executable\EverStatus.exe</outfile>
   <icon>ES.ico</icon>
   ```
3. Run Launch4j and load the configuration
4. Click "Build wrapper" to generate `EverStatus.exe`

### macOS App Bundle (Using jpackage)

Create a native macOS app using jpackage (requires JDK 17+):

```bash
jpackage \
  --input target \
  --name EverStatus \
  --main-jar activetrack-1.0.0.jar \
  --main-class com.automations.everstatus.Application \
  --type app-image \
  --icon ES.png \
  --app-version 1.0.0 \
  --vendor "Your Company" \
  --copyright "Copyright 2024" \
  --mac-package-name "com.automations.everstatus"
```

This creates `EverStatus.app` that can be distributed.

## Troubleshooting

### "Java not found" error
- Install Java 17 or higher from https://adoptium.net/
- Ensure JAVA_HOME is set and Java is in PATH

### "Cannot find JAR file" error
- Ensure `activetrack-1.0.0.jar` is in the same directory as the launcher script
- Or place the JAR in a `target` subdirectory

### GUI doesn't appear
- Check that you're not running with `java.awt.headless=true`
- Ensure your system supports GUI applications

### Platform-specific SWT errors
- Rebuild the JAR on the target platform to get the correct SWT library
- Or manually activate the correct Maven profile during build

## Version Updates

When releasing a new version:

1. Update version in `pom.xml`:
   ```xml
   <version>1.0.1</version>
   ```

2. Update JAR_NAME in launcher scripts:
   - `runEverStatus.bat`: Update `SET JAR_NAME=activetrack-1.0.1.jar`
   - `runEverStatus.sh`: Update `JAR_NAME="activetrack-1.0.1.jar"`

3. Rebuild and redistribute

## Best Practices

1. **Always test on target platform** before distributing
2. **Include README** with clear installation instructions
3. **Sign your code** (Windows: Authenticode, macOS: Code signing)
4. **Provide Java installation links** in your documentation
5. **Consider bundling JRE** for truly standalone distribution (increases size significantly)
6. **Keep launcher scripts** in version control alongside your code
