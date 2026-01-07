@echo off
setlocal EnableDelayedExpansion

:: ============================================================================
:: BossTerm Installation Script for Windows (Batch)
:: Legacy support for Windows without PowerShell
::
:: Usage:
::   install.bat              - Install latest version
::   install.bat /uninstall   - Uninstall
::   install.bat /version:X.Y - Install specific version
::   install.bat /dryrun      - Preview only
::   install.bat /help        - Show help
::
:: https://github.com/kshivang/BossTerm
:: ============================================================================

set "SCRIPT_VERSION=1.0.0"
set "GITHUB_REPO=kshivang/BossTerm"
set "INSTALL_DIR=%LOCALAPPDATA%\BossTerm"
set "JAR_PATH=%INSTALL_DIR%\bossterm.jar"
set "CLI_PATH=%INSTALL_DIR%\bossterm.cmd"
set "CONFIG_DIR=%USERPROFILE%\.bossterm"

:: Default values
set "VERSION="
set "UNINSTALL=0"
set "DRYRUN=0"
set "FORCE=0"
set "NOCLI=0"

:: Parse arguments
:parse_args
if "%~1"=="" goto :main
if /i "%~1"=="/help" goto :show_help
if /i "%~1"=="-help" goto :show_help
if /i "%~1"=="--help" goto :show_help
if /i "%~1"=="/?" goto :show_help
if /i "%~1"=="/uninstall" set "UNINSTALL=1" & shift & goto :parse_args
if /i "%~1"=="/dryrun" set "DRYRUN=1" & shift & goto :parse_args
if /i "%~1"=="/force" set "FORCE=1" & shift & goto :parse_args
if /i "%~1"=="/nocli" set "NOCLI=1" & shift & goto :parse_args
set "_arg=%~1"
if /i "!_arg:~0,9!"=="/version:" (
    set "VERSION=!_arg:~9!"
    shift
    goto :parse_args
)
echo Unknown option: %~1
echo Run: install.bat /help
exit /b 1

:: ============================================================================
:: Show Help
:: ============================================================================
:show_help
echo.
echo BossTerm Installation Script v%SCRIPT_VERSION%
echo.
echo Usage:
echo   install.bat [OPTIONS]
echo.
echo Options:
echo   /help              Show this help message
echo   /version:X.Y.Z     Install specific version (default: latest)
echo   /uninstall         Uninstall BossTerm
echo   /dryrun            Show what would be done without executing
echo   /force             Force reinstall even if already installed
echo   /nocli             Skip CLI launcher installation
echo.
echo Examples:
echo   install.bat                    Install latest version
echo   install.bat /version:1.0.5     Install specific version
echo   install.bat /uninstall         Uninstall
echo   install.bat /dryrun            Preview only
echo.
echo For more information: https://github.com/%GITHUB_REPO%
echo.
exit /b 0

:: ============================================================================
:: Main
:: ============================================================================
:main
echo.
echo ========================================
echo  BossTerm Installation Script v%SCRIPT_VERSION%
echo ========================================
echo.

:: Detect architecture
set "ARCH=amd64"
if "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "ARCH=arm64"
if "%PROCESSOR_ARCHITECTURE%"=="x86" set "ARCH=386"
echo ==^> Platform: Windows (%ARCH%)

:: Handle uninstall
if "%UNINSTALL%"=="1" (
    if not exist "%JAR_PATH%" (
        echo Warning: BossTerm does not appear to be installed
        exit /b 0
    )
    if "%DRYRUN%"=="1" (
        echo ==^> Dry run mode - would uninstall BossTerm
        exit /b 0
    )
    goto :uninstall
)

:: Check if already installed
if exist "%JAR_PATH%" (
    if "%FORCE%"=="0" (
        echo Warning: BossTerm is already installed at %JAR_PATH%
        echo Warning: Use /force to reinstall or /uninstall to remove first
        exit /b 0
    )
)

:: Get version if not specified
if "%VERSION%"=="" (
    echo ==^> Fetching latest version...
    call :get_latest_version
    if "!VERSION!"=="" (
        echo Error: Failed to fetch latest version
        echo Please specify version with /version:X.Y.Z
        exit /b 1
    )
)

echo ==^> Version: %VERSION%

:: Dry run
if "%DRYRUN%"=="1" (
    echo.
    echo ==^> Dry run mode - no changes will be made
    echo ==^> Would install BossTerm %VERSION% on Windows (%ARCH%)
    exit /b 0
)

echo.

:: Check Java
call :check_java
if errorlevel 1 (
    echo.
    echo Error: Java 17+ is required but not found
    echo.
    echo Please install Java 17 manually:
    echo   Option 1: winget install Microsoft.OpenJDK.17
    echo   Option 2: Download from https://adoptium.net/
    echo.
    echo After installing Java, run this script again.
    exit /b 1
)

:: Install
call :install_bossterm
if errorlevel 1 (
    echo Error: Installation failed
    exit /b 1
)

:: Install CLI
if "%NOCLI%"=="0" (
    call :install_cli
)

:: Success
echo.
echo ========================================
echo ==^> BossTerm %VERSION% installed successfully!
echo ========================================
echo.
echo To start BossTerm:
echo   - Run: bossterm
echo   - Or: java -jar "%JAR_PATH%"
echo.
echo Note: You may need to restart your terminal for PATH changes to take effect.
echo.
exit /b 0

:: ============================================================================
:: Get Latest Version
:: ============================================================================
:get_latest_version
:: Try curl first (Windows 10+)
where curl >nul 2>&1
if %errorlevel%==0 (
    for /f "tokens=*" %%i in ('curl -sL "https://api.github.com/repos/%GITHUB_REPO%/releases/latest" 2^>nul ^| findstr /C:"tag_name"') do (
        set "line=%%i"
        for /f "tokens=2 delims=:" %%j in ("!line!") do (
            set "tag=%%j"
            set "tag=!tag:~2,-2!"
            if "!tag:~0,1!"=="v" set "tag=!tag:~1!"
            set "VERSION=!tag!"
        )
    )
    if defined VERSION exit /b 0
)

:: Try PowerShell fallback
powershell -Command "(Invoke-RestMethod -Uri 'https://api.github.com/repos/%GITHUB_REPO%/releases/latest').tag_name -replace '^v',''" > "%TEMP%\bossterm_version.txt" 2>nul
if exist "%TEMP%\bossterm_version.txt" (
    set /p VERSION=<"%TEMP%\bossterm_version.txt"
    del "%TEMP%\bossterm_version.txt" >nul 2>&1
    if defined VERSION exit /b 0
)

exit /b 1

:: ============================================================================
:: Check Java
:: ============================================================================
:check_java
java -version >nul 2>&1
if errorlevel 1 (
    echo Warning: Java not found
    exit /b 1
)

:: Check version
for /f "tokens=3" %%i in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set "java_ver=%%i"
    set "java_ver=!java_ver:"=!"
    for /f "tokens=1 delims=." %%j in ("!java_ver!") do (
        set "major_ver=%%j"
        if !major_ver! GEQ 17 (
            echo ==^> Java version: !java_ver!
            exit /b 0
        )
    )
)

echo Warning: Java version too old (need 17+, found !java_ver!)
exit /b 1

:: ============================================================================
:: Install BossTerm
:: ============================================================================
:install_bossterm
set "JAR_URL=https://github.com/%GITHUB_REPO%/releases/download/v%VERSION%/bossterm-%VERSION%.jar"

echo ==^> Installing BossTerm v%VERSION%...

:: Create directory
if not exist "%INSTALL_DIR%" (
    mkdir "%INSTALL_DIR%"
    echo ==^> Created directory: %INSTALL_DIR%
)

:: Download JAR
echo ==^> Downloading from %JAR_URL%...

:: Try curl first
where curl >nul 2>&1
if %errorlevel%==0 (
    curl -fsSL "%JAR_URL%" -o "%JAR_PATH%"
    if %errorlevel%==0 (
        echo ==^> Downloaded bossterm.jar
        exit /b 0
    )
)

:: Try PowerShell fallback
powershell -Command "Invoke-WebRequest -Uri '%JAR_URL%' -OutFile '%JAR_PATH%' -UseBasicParsing" >nul 2>&1
if exist "%JAR_PATH%" (
    echo ==^> Downloaded bossterm.jar
    exit /b 0
)

echo Error: Failed to download JAR
exit /b 1

:: ============================================================================
:: Install CLI Launcher
:: ============================================================================
:install_cli
echo ==^> Installing CLI launcher...

:: Create launcher script
(
echo @echo off
echo setlocal
echo set "BOSSTERM_JAR=%JAR_PATH%"
echo if not exist "%%BOSSTERM_JAR%%" ^(
echo     echo Error: BossTerm not found at %%BOSSTERM_JAR%%
echo     echo Please run: install.bat
echo     exit /b 1
echo ^)
echo java -jar "%%BOSSTERM_JAR%%" %%*
) > "%CLI_PATH%"

echo ==^> Created CLI launcher at %CLI_PATH%

:: Add to PATH
call :add_to_path
exit /b 0

:: ============================================================================
:: Add to PATH
:: ============================================================================
:add_to_path
:: Check if already in PATH
echo %PATH% | findstr /C:"%INSTALL_DIR%" >nul 2>&1
if %errorlevel%==0 (
    echo ==^> Already in PATH
    exit /b 0
)

echo ==^> Adding %INSTALL_DIR% to user PATH...

:: Add to user PATH via PowerShell (safer than setx)
powershell -Command "[Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path', 'User') + ';%INSTALL_DIR%', 'User')" >nul 2>&1
if %errorlevel%==0 (
    echo ==^> Added to PATH
) else (
    echo Warning: Could not add to PATH automatically
    echo Please add %INSTALL_DIR% to your PATH manually
)

:: Update current session
set "PATH=%PATH%;%INSTALL_DIR%"
exit /b 0

:: ============================================================================
:: Uninstall
:: ============================================================================
:uninstall
echo ==^> Uninstalling BossTerm...

:: Remove installation directory
if exist "%INSTALL_DIR%" (
    rmdir /s /q "%INSTALL_DIR%"
    echo ==^> Removed %INSTALL_DIR%
)

:: Remove from PATH
echo %PATH% | findstr /C:"%INSTALL_DIR%" >nul 2>&1
if %errorlevel%==0 (
    echo ==^> Removing from PATH...
    powershell -Command "$p = [Environment]::GetEnvironmentVariable('Path', 'User'); $p = ($p -split ';' | Where-Object { $_ -ne '%INSTALL_DIR%' }) -join ';'; [Environment]::SetEnvironmentVariable('Path', $p, 'User')" >nul 2>&1
    echo ==^> Removed from PATH
)

:: Ask about config
if exist "%CONFIG_DIR%" (
    set /p "response=Remove configuration directory (%CONFIG_DIR%)? [y/N] "
    if /i "!response!"=="y" (
        rmdir /s /q "%CONFIG_DIR%"
        echo ==^> Removed configuration
    ) else (
        echo ==^> Configuration preserved at %CONFIG_DIR%
    )
)

echo ==^> BossTerm uninstalled
exit /b 0
