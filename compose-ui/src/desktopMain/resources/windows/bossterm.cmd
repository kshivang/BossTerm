@echo off
setlocal enabledelayedexpansion

:: BossTerm CLI Launcher for Windows
:: Usage: bossterm [path] [-d directory] [-n]
::
:: Options:
::   path         Open BossTerm in the specified directory
::   -d DIR       Open BossTerm in the specified directory
::   -n           Open in new window (default behavior)
::
:: Examples:
::   bossterm                    Open BossTerm in current directory
::   bossterm C:\Projects        Open in C:\Projects
::   bossterm -d "C:\My Folder"  Open in specified directory

set "TARGET_DIR="

:: Parse arguments
:parse_args
if "%~1"=="" goto find_app
if /i "%~1"=="-d" (
    set "TARGET_DIR=%~2"
    shift
    shift
    goto parse_args
)
if /i "%~1"=="-n" (
    :: -n is default behavior on Windows (always new window)
    shift
    goto parse_args
)
if /i "%~1"=="--help" (
    goto show_help
)
if /i "%~1"=="-h" (
    goto show_help
)
:: Treat any other argument as a path
set "TARGET_DIR=%~1"
shift
goto parse_args

:find_app
:: Search for BossTerm.exe in common installation locations
set "APP_PATH="

:: Check Local AppData first (user install)
if exist "%LOCALAPPDATA%\BossTerm\BossTerm.exe" (
    set "APP_PATH=%LOCALAPPDATA%\BossTerm\BossTerm.exe"
    goto found_app
)

:: Check Program Files (system install)
if exist "%ProgramFiles%\BossTerm\BossTerm.exe" (
    set "APP_PATH=%ProgramFiles%\BossTerm\BossTerm.exe"
    goto found_app
)

:: Check Program Files (x86)
if exist "%ProgramFiles(x86)%\BossTerm\BossTerm.exe" (
    set "APP_PATH=%ProgramFiles(x86)%\BossTerm\BossTerm.exe"
    goto found_app
)

:: App not found
echo BossTerm not found.
echo.
echo Please install BossTerm from https://bossterm.dev
echo Or ensure BossTerm.exe is in one of these locations:
echo   - %LOCALAPPDATA%\BossTerm\
echo   - %ProgramFiles%\BossTerm\
exit /b 1

:found_app
:: If no target directory specified, use current directory
if not defined TARGET_DIR (
    set "TARGET_DIR=%CD%"
)

:: Resolve target directory to absolute path
pushd "%TARGET_DIR%" 2>nul
if errorlevel 1 (
    echo Error: Directory not found: %TARGET_DIR%
    exit /b 1
)
set "BOSSTERM_CWD=!CD!"
popd

:: Launch BossTerm with working directory
:: Use environment variable to pass CWD (app will read this)
set "BOSSTERM_CWD=%BOSSTERM_CWD%"
start "" "%APP_PATH%"
exit /b 0

:show_help
echo BossTerm - Terminal Emulator
echo.
echo Usage: bossterm [path] [-d directory] [-n]
echo.
echo Options:
echo   path         Open BossTerm in the specified directory
echo   -d DIR       Open BossTerm in the specified directory
echo   -n           Open in new window (default behavior)
echo   -h, --help   Show this help message
echo.
echo Examples:
echo   bossterm                    Open in current directory
echo   bossterm C:\Projects        Open in C:\Projects
echo   bossterm -d "C:\My Folder"  Open in directory with spaces
exit /b 0
