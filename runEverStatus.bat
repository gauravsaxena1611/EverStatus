@echo off
REM EverStatus Launcher for Windows
REM This script can be distributed alongside the JAR file

SET JAR_NAME=activetrack-1.0.0.jar

REM Check if Java is installed
java -version >nul 2>&1
IF %ERRORLEVEL% NEQ 0 (
    echo Error: Java is not installed or not in PATH
    echo Please install Java 17 or higher from https://adoptium.net/
    pause
    exit /b 1
)

REM Check if JAR file exists in same directory or target directory
IF EXIST "%~dp0%JAR_NAME%" (
    SET JAR_PATH=%~dp0%JAR_NAME%
) ELSE IF EXIST "%~dp0target\%JAR_NAME%" (
    SET JAR_PATH=%~dp0target\%JAR_NAME%
) ELSE (
    echo Error: Cannot find %JAR_NAME%
    echo Please ensure the JAR file is in the same directory as this script
    pause
    exit /b 1
)

echo Starting EverStatus application...
echo JAR Location: %JAR_PATH%
echo.

REM Run the application (output to console, not log file)
java -jar "%JAR_PATH%"

IF %ERRORLEVEL% NEQ 0 (
    echo.
    echo Application exited with error code %ERRORLEVEL%
    pause
)
exit /b %ERRORLEVEL%