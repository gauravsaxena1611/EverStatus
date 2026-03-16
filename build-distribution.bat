@echo off
REM EverStatus Distribution Build Script for Windows
REM This script builds the application and creates a distribution package

SET VERSION=1.0.0
SET DIST_DIR=dist\EverStatus-Windows-%VERSION%
SET JAR_NAME=activetrack-%VERSION%.jar

echo ========================================
echo EverStatus Distribution Builder
echo ========================================
echo.

REM Clean and build
echo [1/4] Building application...
call .\mvnw.cmd clean package
IF %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    pause
    exit /b 1
)

REM Create distribution directory
echo.
echo [2/4] Creating distribution folder...
IF EXIST dist rmdir /s /q dist
mkdir "%DIST_DIR%"

REM Copy files
echo.
echo [3/4] Copying files...
copy target\%JAR_NAME% "%DIST_DIR%\" >nul
copy runEverStatus.bat "%DIST_DIR%\" >nul
copy ES.ico "%DIST_DIR%\" >nul
copy README.md "%DIST_DIR%\" >nul

REM Create README for users
echo Creating user README...
(
echo EverStatus - User Activity Automation
echo ======================================
echo.
echo INSTALLATION:
echo 1. Ensure Java 17 or higher is installed
echo    Download from: https://adoptium.net/
echo.
echo 2. Double-click 'runEverStatus.bat' to start the application
echo.
echo 3. Optionally, create a desktop shortcut to runEverStatus.bat
echo.
echo TROUBLESHOOTING:
echo - If "Java not found" appears, install Java 17+ and add it to PATH
echo - Ensure %JAR_NAME% is in the same folder as runEverStatus.bat
echo.
echo For more information, see README.md
) > "%DIST_DIR%\INSTALL.txt"

REM Create ZIP archive
echo.
echo [4/4] Creating ZIP archive...
cd dist
tar -a -c -f EverStatus-Windows-%VERSION%.zip EverStatus-Windows-%VERSION%
cd ..

echo.
echo ========================================
echo SUCCESS! Distribution package created:
echo   dist\EverStatus-Windows-%VERSION%.zip
echo ========================================
echo.
echo Contents:
dir "%DIST_DIR%" /b
echo.
echo You can now distribute the ZIP file to Windows users.
echo.
pause
