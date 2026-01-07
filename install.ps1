<#
.SYNOPSIS
    BossTerm Universal Installation Script for Windows
.DESCRIPTION
    Downloads and installs BossTerm terminal emulator on Windows.
    Supports automatic Java installation via winget or Chocolatey.
.PARAMETER Version
    Specific version to install (default: latest)
.PARAMETER Method
    Installation method: auto, jar (default: auto)
.PARAMETER Uninstall
    Uninstall BossTerm
.PARAMETER NoCli
    Skip CLI launcher installation
.PARAMETER DryRun
    Show what would be done without executing
.PARAMETER Force
    Force reinstall even if already installed
.PARAMETER Help
    Show this help message
.EXAMPLE
    .\install.ps1
    Install the latest version of BossTerm
.EXAMPLE
    .\install.ps1 -Version 1.0.5
    Install a specific version
.EXAMPLE
    .\install.ps1 -Uninstall
    Uninstall BossTerm
.EXAMPLE
    iwr -useb https://raw.githubusercontent.com/kshivang/BossTerm/master/install.ps1 | iex
    Install from URL (curl pattern)
.LINK
    https://github.com/kshivang/BossTerm
#>

[CmdletBinding()]
param(
    [string]$Version,
    [ValidateSet("auto", "jar")]
    [string]$Method = "auto",
    [switch]$Uninstall,
    [switch]$NoCli,
    [switch]$DryRun,
    [switch]$Force,
    [switch]$Help
)

# ============================================================================
# Configuration
# ============================================================================

$script:GITHUB_REPO = "kshivang/BossTerm"
$script:GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
$script:GITHUB_RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/download"

# Installation paths
$script:INSTALL_DIR = Join-Path $env:LOCALAPPDATA "BossTerm"
$script:JAR_PATH = Join-Path $INSTALL_DIR "bossterm.jar"
$script:CLI_PATH = Join-Path $INSTALL_DIR "bossterm.cmd"
$script:CONFIG_DIR = Join-Path $env:USERPROFILE ".bossterm"

$script:SCRIPT_VERSION = "1.0.0"

# ============================================================================
# Helper Functions
# ============================================================================

function Write-Info {
    param([string]$Message)
    Write-Host "==> " -ForegroundColor Blue -NoNewline
    Write-Host $Message -ForegroundColor White
}

function Write-Success {
    param([string]$Message)
    Write-Host "==> " -ForegroundColor Green -NoNewline
    Write-Host $Message -ForegroundColor White
}

function Write-Warning {
    param([string]$Message)
    Write-Host "Warning: " -ForegroundColor Yellow -NoNewline
    Write-Host $Message
}

function Write-Error {
    param([string]$Message)
    Write-Host "Error: " -ForegroundColor Red -NoNewline
    Write-Host $Message
}

function Show-Help {
    @"
BossTerm Installation Script v$script:SCRIPT_VERSION

Usage:
  .\install.ps1 [OPTIONS]

  # Or via URL:
  iwr -useb https://raw.githubusercontent.com/kshivang/BossTerm/master/install.ps1 | iex

Options:
  -Version <version>    Install specific version (default: latest)
  -Method <method>      Installation method: auto, jar (default: auto)
  -Uninstall            Uninstall BossTerm
  -NoCli                Skip CLI launcher installation
  -DryRun               Show what would be done without executing
  -Force                Force reinstall even if already installed
  -Help                 Show this help message

Examples:
  .\install.ps1                     # Install latest version
  .\install.ps1 -Version 1.0.5      # Install specific version
  .\install.ps1 -Uninstall          # Uninstall
  .\install.ps1 -DryRun             # Preview only

For more information: https://github.com/$script:GITHUB_REPO
"@
}

# ============================================================================
# Platform Detection
# ============================================================================

function Get-Architecture {
    $arch = [System.Environment]::GetEnvironmentVariable("PROCESSOR_ARCHITECTURE")
    switch ($arch) {
        "AMD64" { return "amd64" }
        "ARM64" { return "arm64" }
        "x86"   { return "386" }
        default { return "unknown" }
    }
}

# ============================================================================
# Version Management
# ============================================================================

function Get-LatestVersion {
    try {
        Write-Info "Fetching latest version..."
        $response = Invoke-RestMethod -Uri $script:GITHUB_API_URL -UseBasicParsing
        $tagName = $response.tag_name
        if ($tagName -match "^v(.+)$") {
            return $Matches[1]
        }
        return $tagName
    }
    catch {
        Write-Error "Failed to fetch latest version: $_"
        return $null
    }
}

# ============================================================================
# Java Management
# ============================================================================

function Test-JavaInstalled {
    try {
        $javaVersion = & java -version 2>&1 | Select-Object -First 1
        if ($javaVersion -match '"(\d+)') {
            $majorVersion = [int]$Matches[1]
            return $majorVersion -ge 17
        }
        return $false
    }
    catch {
        return $false
    }
}

function Get-JavaVersion {
    try {
        $javaVersion = & java -version 2>&1 | Select-Object -First 1
        if ($javaVersion -match '"([^"]+)"') {
            return $Matches[1]
        }
        return "unknown"
    }
    catch {
        return "not installed"
    }
}

function Install-Java {
    Write-Info "Java 17+ not found. Attempting to install..."

    # Try winget first (Windows 10 1709+)
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Write-Info "Installing Java 17 via winget..."
        try {
            & winget install --id Microsoft.OpenJDK.17 --accept-source-agreements --accept-package-agreements
            if ($LASTEXITCODE -eq 0) {
                # Refresh PATH
                $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")
                Write-Success "Java 17 installed via winget"
                return $true
            }
        }
        catch {
            Write-Warning "winget installation failed: $_"
        }
    }

    # Try Chocolatey
    if (Get-Command choco -ErrorAction SilentlyContinue) {
        Write-Info "Installing Java 17 via Chocolatey..."
        try {
            & choco install openjdk17 -y
            if ($LASTEXITCODE -eq 0) {
                # Refresh PATH
                $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")
                Write-Success "Java 17 installed via Chocolatey"
                return $true
            }
        }
        catch {
            Write-Warning "Chocolatey installation failed: $_"
        }
    }

    # Manual instructions
    Write-Error "Could not automatically install Java 17"
    Write-Host ""
    Write-Host "Please install Java 17+ manually:"
    Write-Host "  Option 1: winget install Microsoft.OpenJDK.17"
    Write-Host "  Option 2: Download from https://adoptium.net/"
    Write-Host ""
    return $false
}

# ============================================================================
# Installation Detection
# ============================================================================

function Test-BossTermInstalled {
    return Test-Path $script:JAR_PATH
}

# ============================================================================
# Installation Functions
# ============================================================================

function Install-BossTerm {
    param(
        [string]$Version
    )

    $jarUrl = "$script:GITHUB_RELEASE_URL/v$Version/bossterm-$Version.jar"

    Write-Info "Installing BossTerm v$Version..."

    # Create installation directory
    if (-not (Test-Path $script:INSTALL_DIR)) {
        New-Item -ItemType Directory -Path $script:INSTALL_DIR -Force | Out-Null
        Write-Info "Created directory: $script:INSTALL_DIR"
    }

    # Download JAR
    Write-Info "Downloading from $jarUrl..."
    try {
        $ProgressPreference = 'SilentlyContinue'  # Speed up download
        Invoke-WebRequest -Uri $jarUrl -OutFile $script:JAR_PATH -UseBasicParsing
        $ProgressPreference = 'Continue'
        Write-Success "Downloaded bossterm.jar"
    }
    catch {
        Write-Error "Failed to download: $_"
        return $false
    }

    return $true
}

function Install-CliLauncher {
    Write-Info "Installing CLI launcher..."

    # Create bossterm.cmd launcher
    $launcherContent = @"
@echo off
setlocal
set "BOSSTERM_JAR=$script:JAR_PATH"
if not exist "%BOSSTERM_JAR%" (
    echo Error: BossTerm not found at %BOSSTERM_JAR%
    echo Please run: iwr -useb https://raw.githubusercontent.com/kshivang/BossTerm/master/install.ps1 ^| iex
    exit /b 1
)
java -jar "%BOSSTERM_JAR%" %*
"@

    Set-Content -Path $script:CLI_PATH -Value $launcherContent -Encoding ASCII
    Write-Success "Created CLI launcher at $script:CLI_PATH"

    # Add to PATH if not already there
    Add-ToUserPath $script:INSTALL_DIR

    return $true
}

function Add-ToUserPath {
    param([string]$Directory)

    $currentPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($currentPath -notlike "*$Directory*") {
        Write-Info "Adding $Directory to user PATH..."
        $newPath = "$currentPath;$Directory"
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
        $env:Path = "$env:Path;$Directory"
        Write-Success "Added to PATH"
    }
    else {
        Write-Info "Already in PATH"
    }
}

function Remove-FromUserPath {
    param([string]$Directory)

    $currentPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($currentPath -like "*$Directory*") {
        Write-Info "Removing $Directory from user PATH..."
        $paths = $currentPath -split ";" | Where-Object { $_ -ne $Directory -and $_ -ne "" }
        $newPath = $paths -join ";"
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
        Write-Success "Removed from PATH"
    }
}

# ============================================================================
# Uninstallation
# ============================================================================

function Uninstall-BossTerm {
    Write-Info "Uninstalling BossTerm..."

    # Remove installation directory
    if (Test-Path $script:INSTALL_DIR) {
        Remove-Item -Path $script:INSTALL_DIR -Recurse -Force
        Write-Success "Removed $script:INSTALL_DIR"
    }

    # Remove from PATH
    Remove-FromUserPath $script:INSTALL_DIR

    # Ask about config
    if (Test-Path $script:CONFIG_DIR) {
        $response = Read-Host "Remove configuration directory ($script:CONFIG_DIR)? [y/N]"
        if ($response -match "^[Yy]$") {
            Remove-Item -Path $script:CONFIG_DIR -Recurse -Force
            Write-Success "Removed configuration"
        }
        else {
            Write-Info "Configuration preserved at $script:CONFIG_DIR"
        }
    }

    Write-Success "BossTerm uninstalled"
}

# ============================================================================
# Main
# ============================================================================

function Main {
    Write-Host ""
    Write-Host "BossTerm Installation Script v$script:SCRIPT_VERSION" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host ""

    # Show help
    if ($Help) {
        Show-Help
        exit 0
    }

    # Detect platform
    $arch = Get-Architecture
    Write-Info "Platform: Windows ($arch)"

    if ($arch -eq "unknown") {
        Write-Warning "Unknown architecture, installation may not work correctly"
    }

    # Handle uninstall
    if ($Uninstall) {
        if (-not (Test-BossTermInstalled)) {
            Write-Warning "BossTerm does not appear to be installed"
            exit 0
        }

        if ($DryRun) {
            Write-Info "Dry run mode - would uninstall BossTerm"
            exit 0
        }

        Uninstall-BossTerm
        exit 0
    }

    # Check if already installed
    if ((Test-BossTermInstalled) -and (-not $Force)) {
        Write-Warning "BossTerm is already installed at $script:JAR_PATH"
        Write-Warning "Use -Force to reinstall or -Uninstall to remove first"
        exit 0
    }

    # Get version
    if (-not $Version) {
        $Version = Get-LatestVersion
        if (-not $Version) {
            exit 1
        }
    }

    Write-Info "Version: $Version"
    Write-Info "Method: $Method"

    # Dry run
    if ($DryRun) {
        Write-Host ""
        Write-Info "Dry run mode - no changes will be made"
        Write-Info "Would install BossTerm $Version on Windows ($arch)"
        exit 0
    }

    Write-Host ""

    # Check Java
    if (-not (Test-JavaInstalled)) {
        $javaVersion = Get-JavaVersion
        Write-Warning "Java 17+ required (found: $javaVersion)"

        if (-not (Install-Java)) {
            Write-Error "Please install Java 17+ and try again"
            exit 1
        }

        # Verify Java after install
        if (-not (Test-JavaInstalled)) {
            Write-Error "Java installation succeeded but version check failed"
            Write-Host "Please restart your terminal and run this script again"
            exit 1
        }
    }
    else {
        $javaVersion = Get-JavaVersion
        Write-Info "Java version: $javaVersion"
    }

    # Install
    if (-not (Install-BossTerm -Version $Version)) {
        Write-Error "Installation failed"
        exit 1
    }

    # Install CLI launcher
    if (-not $NoCli) {
        Install-CliLauncher | Out-Null
    }

    # Success message
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Success "BossTerm $Version installed successfully!"
    Write-Host "========================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "To start BossTerm:"
    Write-Host "  - Run: bossterm"
    Write-Host "  - Or: java -jar `"$script:JAR_PATH`""
    Write-Host ""
    Write-Host "Note: You may need to restart your terminal for PATH changes to take effect."
    Write-Host ""
    exit 0
}

# Run main
Main
