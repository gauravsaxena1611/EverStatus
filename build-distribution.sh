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

# Determine icon: use .icns if available, fall back to .png
ICON_FILE="ES.icns"
if [ ! -f "$ICON_FILE" ]; then
    ICON_FILE="ES.png"
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
  --main-class com.automations.everstatus.Application \
  --dest dist \
  --icon "$ICON_FILE" \
  --app-version "$VERSION" \
  --java-options "-Djava.awt.headless=false" \
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
