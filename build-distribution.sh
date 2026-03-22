#!/bin/bash
# EverStatus Portable Build Script for macOS
# Creates a standalone .app bundle with bundled JRE - no Java install required on target machine

VERSION="1.0.0"
JAR_NAME="activetrack-${VERSION}.jar"

echo "========================================"
echo "EverStatus Portable Build for macOS"
echo "========================================"
echo ""

# Check jpackage is available
if ! command -v jpackage &> /dev/null; then
    echo "ERROR: jpackage not found. Install JDK 17+ and ensure it is in PATH."
    echo "  brew install openjdk@17"
    echo "  export PATH=\"/opt/homebrew/opt/openjdk@17/bin:\$PATH\""
    exit 1
fi

# Determine icon option: prefer .icns, fall back to .png, skip if neither exists
ICON_OPT=""
if [ -f "ES.icns" ]; then
    ICON_OPT="--icon ES.icns"
elif [ -f "ES.png" ]; then
    ICON_OPT="--icon ES.png"
else
    echo "WARNING: No icon file found (ES.icns / ES.png) — jpackage will use the default Java icon"
fi

# Build JAR
echo "[1/3] Building JAR..."
./mvnw clean package -DskipTests
if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

# Create portable .app bundle with bundled JRE
echo ""
echo "[2/3] Creating .app bundle with jpackage (this may take a minute)..."
rm -rf dist
mkdir -p dist

jpackage \
  --type app-image \
  --input target \
  --name EverStatus \
  --main-jar "$JAR_NAME" \
  --main-class org.springframework.boot.loader.JarLauncher \
  --dest dist \
  $ICON_OPT \
  --app-version "$VERSION" \
  --java-options "-XstartOnFirstThread -Djava.awt.headless=false" \
  --mac-package-name "com.automations.everstatus"

if [ $? -ne 0 ]; then
    echo "jpackage failed!"
    exit 1
fi

# Zip the .app bundle
echo ""
echo "[3/3] Creating ZIP archive..."
cd dist
zip -r "EverStatus-macOS-${VERSION}.zip" "EverStatus.app"
cd ..

echo ""
echo "========================================"
echo "SUCCESS!"
echo "  dist/EverStatus-macOS-${VERSION}.zip"
echo "========================================"
echo ""
echo "Contents: unzip, then double-click EverStatus.app to run."
echo "No Java installation required on target machine."
echo ""
