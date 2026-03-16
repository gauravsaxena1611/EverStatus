#!/bin/bash
# EverStatus Distribution Build Script for macOS/Linux
# This script builds the application and creates a distribution package

VERSION="1.0.0"
PLATFORM=$(uname -s | tr '[:upper:]' '[:lower:]')
DIST_DIR="dist/EverStatus-${PLATFORM}-${VERSION}"
JAR_NAME="activetrack-${VERSION}.jar"

echo "========================================"
echo "EverStatus Distribution Builder"
echo "========================================"
echo ""

# Clean and build
echo "[1/4] Building application..."
./mvnw clean package
if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

# Create distribution directory
echo ""
echo "[2/4] Creating distribution folder..."
rm -rf dist
mkdir -p "$DIST_DIR"

# Copy files
echo ""
echo "[3/4] Copying files..."
cp "target/$JAR_NAME" "$DIST_DIR/"
cp runEverStatus.sh "$DIST_DIR/"
cp ES.png "$DIST_DIR/"
cp README.md "$DIST_DIR/"
chmod +x "$DIST_DIR/runEverStatus.sh"

# Create README for users
echo "Creating user README..."
cat > "$DIST_DIR/INSTALL.txt" << 'EOF'
EverStatus - User Activity Automation
======================================

INSTALLATION:
1. Ensure Java 17 or higher is installed

   macOS:
   brew install openjdk@17

   Linux (Ubuntu/Debian):
   sudo apt install openjdk-17-jdk

2. Make the launcher executable:
   chmod +x runEverStatus.sh

3. Run the application:
   ./runEverStatus.sh

   Or double-click runEverStatus.sh if your system supports it

TROUBLESHOOTING:
- If "Java not found" appears, install Java 17+ and ensure it's in PATH
- Ensure activetrack-1.0.0.jar is in the same folder as runEverStatus.sh
- On macOS, you may need to allow the script in System Preferences > Security

For more information, see README.md
EOF

# Create ZIP archive
echo ""
echo "[4/4] Creating ZIP archive..."
cd dist
zip -r "EverStatus-${PLATFORM}-${VERSION}.zip" "EverStatus-${PLATFORM}-${VERSION}"
cd ..

echo ""
echo "========================================"
echo "SUCCESS! Distribution package created:"
echo "  dist/EverStatus-${PLATFORM}-${VERSION}.zip"
echo "========================================"
echo ""
echo "Contents:"
ls -1 "$DIST_DIR"
echo ""
echo "You can now distribute the ZIP file to ${PLATFORM} users."
echo ""
