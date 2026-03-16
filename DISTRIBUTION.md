# EverStatus – Packaging & Distribution Guide

This guide explains how to build standalone, portable packages for Windows and macOS.
The output is a self-contained app – **users do not need Java installed**.

> **Size note:** Bundling a JRE adds ~100–150 MB to the package. This is unavoidable for
> a Java app that requires no pre-installed runtime.

---

## Prerequisites (build machine only)

- JDK 17 or higher (must include `jpackage` – standard in JDK 14+)
- Maven (or use the included `mvnw` wrapper)
- Build on the **target platform**: build on Windows to produce the Windows package,
  build on macOS to produce the Mac package

---

## Windows – Step-by-Step

### 1. Install JDK 17+ on your Windows build machine

Download from https://adoptium.net/ and ensure `jpackage` is in your PATH:
```
jpackage --version
```

### 2. Build the portable package

Run the build script from the project root:
```
build-distribution.bat
```

This will:
- Compile and package the JAR (`mvnw clean package`)
- Bundle a JRE using `jpackage --type app-image`
- Zip the result to `dist\EverStatus-Windows-1.0.0.zip`

### 3. Distribute

Share `dist\EverStatus-Windows-1.0.0.zip` with users.

**User instructions (include in your release notes):**
1. Extract the ZIP anywhere (Desktop, USB drive, etc.)
2. Open the `EverStatus` folder
3. Double-click `EverStatus.exe`
4. No Java installation required

---

## macOS – Step-by-Step

### 1. Install JDK 17+ on your Mac build machine

```bash
brew install openjdk@17
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
jpackage --version
```

> **Apple Silicon vs Intel:** Run this on the Mac architecture you're targeting.
> The bundle only runs on the same architecture it was built on. Build on M-series Mac
> for Apple Silicon users; build on Intel Mac for Intel users.

### 2. (Optional) Create a high-quality icon

`jpackage` prefers an `.icns` icon on macOS. If you only have `ES.png`, the script
will use it as a fallback. To convert:
```bash
# Using ImageMagick
convert ES.png -resize 1024x1024 ES.icns
# Or use the free app "Image2icon" from the Mac App Store
```
Place `ES.icns` in the project root.

### 3. Build the portable package

Run the build script from the project root:
```bash
chmod +x build-distribution.sh
./build-distribution.sh
```

This will:
- Compile and package the JAR
- Bundle a JRE using `jpackage --type app-image`
- Zip the result to `dist/EverStatus-macOS-1.0.0.zip`

### 4. Distribute

Share `dist/EverStatus-macOS-1.0.0.zip` with users.

**User instructions (include in your release notes):**
1. Extract the ZIP and move `EverStatus.app` anywhere (Applications, Desktop, etc.)
2. Double-click `EverStatus.app`
3. If macOS blocks it (Gatekeeper): right-click → Open → Open
4. No Java installation required

---

## Manual jpackage Commands

If you prefer to run jpackage directly without the build scripts:

### Windows
```
jpackage --type app-image --input target --name EverStatus --main-jar activetrack-1.0.0.jar --main-class com.automations.everstatus.Application --dest dist --icon ES.ico --app-version 1.0.0 --java-options "-Djava.awt.headless=false"
```

### macOS
```bash
jpackage --type app-image --input target --name EverStatus --main-jar activetrack-1.0.0.jar --main-class com.automations.everstatus.Application --dest dist --icon ES.icns --app-version 1.0.0 --java-options "-Djava.awt.headless=false" --mac-package-name "com.automations.everstatus"
```

---

## Output Structure

### Windows (`dist\EverStatus-Windows-1.0.0.zip`)
```
EverStatus/
├── EverStatus.exe          ← double-click to run
├── runtime/                ← bundled JRE (no install needed)
└── app/
    └── activetrack-1.0.0.jar
```

### macOS (`dist/EverStatus-macOS-1.0.0.zip`)
```
EverStatus.app/             ← double-click to run
└── Contents/
    ├── MacOS/EverStatus
    ├── runtime/            ← bundled JRE (no install needed)
    └── app/
        └── activetrack-1.0.0.jar
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `jpackage: command not found` | Install JDK 17+ (not just JRE). Verify with `jpackage --version`. |
| macOS: "app is damaged or can't be opened" | Right-click → Open, or run `xattr -cr EverStatus.app` in Terminal |
| macOS: Gatekeeper blocks unsigned app | Right-click → Open → Open (only needed once) |
| App opens but GUI doesn't appear | Ensure you built with the correct platform profile (SWT is platform-native) |
| Build fails with SWT error | Run the build on the target OS, not cross-compiled |

---

## Releasing a New Version

1. Update `<version>` in `pom.xml`
2. Update `VERSION` in `build-distribution.bat` and `build-distribution.sh`
3. Run the build script on each platform
4. Distribute the new ZIPs
