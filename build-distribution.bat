@echo off
REM EverStatus Portable Build Script for Windows
REM Creates a standalone portable app with bundled JRE - no Java install required on target machine

SET VERSION=1.0.0
SET JAR_NAME=activetrack-%VERSION%.jar

echo ========================================
echo EverStatus Portable Build for Windows
echo ========================================
echo.

REM Check jpackage is available
where jpackage >nul 2>&1
IF %ERRORLEVEL% NEQ 0 (
    echo ERROR: jpackage not found. Install JDK 17+ and ensure it is in PATH.
    pause
    exit /b 1
)

REM Determine icon option — skip gracefully if ES.ico is not present
SET ICON_OPT=
IF EXIST "ES.ico" (
    SET ICON_OPT=--icon ES.ico
) ELSE (
    echo WARNING: ES.ico not found - jpackage will use the default Java icon
)

REM Clean and build JAR
echo [1/3] Building JAR...
call .\mvnw.cmd clean package -DskipTests
IF %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    pause
    exit /b 1
)

REM Create portable app-image with bundled JRE
echo.
echo [2/3] Creating portable app with jpackage (this may take a minute)...
IF EXIST dist rmdir /s /q dist
mkdir dist

jpackage ^
  --type app-image ^
  --input target ^
  --name EverStatus ^
  --main-jar %JAR_NAME% ^
  --main-class org.springframework.boot.loader.JarLauncher ^
  --dest dist ^
  %ICON_OPT% ^
  --app-version %VERSION% ^
  --java-options "-Djava.awt.headless=false"

IF %ERRORLEVEL% NEQ 0 (
    echo jpackage failed!
    pause
    exit /b 1
)

REM Zip the app-image folder
echo.
echo [3/3] Creating ZIP archive...
cd dist
tar -a -c -f EverStatus-Windows-%VERSION%.zip EverStatus
cd ..

echo.
echo ========================================
echo SUCCESS!
echo   dist\EverStatus-Windows-%VERSION%.zip
echo ========================================
echo.
echo Contents: unzip, then double-click EverStatus\EverStatus.exe
echo No Java installation required on target machine.
echo.
pause
