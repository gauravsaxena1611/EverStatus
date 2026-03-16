#!/bin/bash
# EverStatus Launcher for macOS and Linux
# This script can be distributed alongside the JAR file

JAR_NAME="activetrack-1.0.0.jar"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo "Error: Java is not installed or not in PATH"
    echo "Please install Java 17 or higher:"
    echo "  macOS: brew install openjdk@17"
    echo "  Linux: sudo apt install openjdk-17-jdk (Ubuntu/Debian)"
    exit 1
fi

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "Error: Java 17 or higher is required (found Java $JAVA_VERSION)"
    exit 1
fi

# Check if JAR file exists in same directory or target directory
if [ -f "$SCRIPT_DIR/$JAR_NAME" ]; then
    JAR_PATH="$SCRIPT_DIR/$JAR_NAME"
elif [ -f "$SCRIPT_DIR/target/$JAR_NAME" ]; then
    JAR_PATH="$SCRIPT_DIR/target/$JAR_NAME"
else
    echo "Error: Cannot find $JAR_NAME"
    echo "Please ensure the JAR file is in the same directory as this script"
    exit 1
fi

echo "Starting EverStatus application..."
echo "JAR Location: $JAR_PATH"
echo ""

# Run the application
java -jar "$JAR_PATH"

EXIT_CODE=$?
if [ $EXIT_CODE -ne 0 ]; then
    echo ""
    echo "Application exited with error code $EXIT_CODE"
    read -p "Press Enter to continue..."
fi

exit $EXIT_CODE
