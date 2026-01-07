#!/usr/bin/env bash
#
# BossTerm Universal Installation Script
# https://github.com/kshivang/BossTerm
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.sh | bash
#   curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.sh | bash -s -- --version 1.0.5
#   curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.sh | bash -s -- --uninstall
#
# Or run locally:
#   ./install.sh
#   ./install.sh --uninstall
#   ./install.sh --method homebrew
#

set -e

# ============================================================================
# Configuration
# ============================================================================

GITHUB_REPO="kshivang/BossTerm"
GITHUB_RELEASE_URL="https://github.com/${GITHUB_REPO}/releases/download"
GITHUB_API_URL="https://api.github.com/repos/${GITHUB_REPO}/releases/latest"
HOMEBREW_TAP="kshivang/bossterm"

# Installation paths
MACOS_APP_PATH="/Applications/BossTerm.app"
LINUX_OPT_PATH="/opt/bossterm"
LINUX_JAR_PATH="/opt/bossterm/bossterm.jar"
CLI_SYSTEM_PATH="/usr/local/bin/bossterm"
CLI_USER_PATH="${HOME}/.local/bin/bossterm"
CONFIG_PATH="${HOME}/.bossterm"

# Script version
SCRIPT_VERSION="1.0.0"

# ============================================================================
# Colors and Output
# ============================================================================

# Check if stdout is a terminal
if [ -t 1 ]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    CYAN='\033[0;36m'
    BOLD='\033[1m'
    NC='\033[0m'
else
    RED=''
    GREEN=''
    YELLOW=''
    BLUE=''
    CYAN=''
    BOLD=''
    NC=''
fi

info() {
    echo -e "${BLUE}==>${NC} ${BOLD}$1${NC}"
}

success() {
    echo -e "${GREEN}==>${NC} ${BOLD}$1${NC}"
}

warn() {
    echo -e "${YELLOW}Warning:${NC} $1"
}

error() {
    echo -e "${RED}Error:${NC} $1" >&2
}

# ============================================================================
# Platform Detection
# ============================================================================

detect_os() {
    local os
    os="$(uname -s)"
    case "$os" in
        Darwin) echo "darwin" ;;
        Linux)  echo "linux" ;;
        MINGW*|MSYS*|CYGWIN*) echo "windows" ;;
        *)      echo "unknown" ;;
    esac
}

detect_arch() {
    local arch
    arch="$(uname -m)"
    case "$arch" in
        x86_64|amd64)  echo "amd64" ;;
        arm64|aarch64) echo "arm64" ;;
        armv7l)        echo "armv7" ;;
        i386|i686)     echo "386" ;;
        *)             echo "unknown" ;;
    esac
}

detect_distro() {
    if [ -f /etc/os-release ]; then
        # shellcheck disable=SC1091
        . /etc/os-release
        echo "${ID:-unknown}"
    elif [ -f /etc/debian_version ]; then
        echo "debian"
    elif [ -f /etc/redhat-release ]; then
        echo "rhel"
    elif [ -f /etc/arch-release ]; then
        echo "arch"
    elif [ -f /etc/alpine-release ]; then
        echo "alpine"
    else
        echo "unknown"
    fi
}

has_command() {
    command -v "$1" >/dev/null 2>&1
}

has_sudo() {
    if has_command sudo; then
        # Check if we can actually use sudo (either passwordless or already authenticated)
        sudo -n true 2>/dev/null || sudo -v 2>/dev/null
        return $?
    fi
    return 1
}

# ============================================================================
# Version Management
# ============================================================================

get_latest_version() {
    local version
    if has_command curl; then
        version=$(curl -sL "$GITHUB_API_URL" 2>/dev/null | grep '"tag_name"' | sed -E 's/.*"v([^"]+)".*/\1/' | head -1)
    elif has_command wget; then
        version=$(wget -qO- "$GITHUB_API_URL" 2>/dev/null | grep '"tag_name"' | sed -E 's/.*"v([^"]+)".*/\1/' | head -1)
    fi

    if [ -z "$version" ]; then
        error "Failed to fetch latest version. Please specify a version with --version"
        exit 1
    fi

    echo "$version"
}

# ============================================================================
# Download Helpers
# ============================================================================

download_file() {
    local url="$1"
    local output="$2"

    info "Downloading from $url"

    if has_command curl; then
        curl -fsSL --progress-bar "$url" -o "$output"
    elif has_command wget; then
        wget -q --show-progress "$url" -O "$output"
    else
        error "Neither curl nor wget found. Please install one of them."
        exit 1
    fi
}

# ============================================================================
# Installation Detection
# ============================================================================

check_installed() {
    local os="$1"

    case "$os" in
        darwin)
            [ -d "$MACOS_APP_PATH" ] && return 0
            ;;
        linux)
            # Check various installation methods
            [ -d "$LINUX_OPT_PATH" ] && return 0
            [ -f "$LINUX_JAR_PATH" ] && return 0
            has_command bossterm && return 0
            # Check if installed via snap
            snap list bossterm >/dev/null 2>&1 && return 0
            # Check if installed via package manager
            dpkg -l bossterm >/dev/null 2>&1 && return 0
            rpm -q bossterm >/dev/null 2>&1 && return 0
            ;;
    esac

    return 1
}

get_installed_method() {
    local os="$1"

    case "$os" in
        darwin)
            # Check Homebrew first
            if has_command brew && brew list --cask bossterm >/dev/null 2>&1; then
                echo "homebrew"
                return
            fi
            if [ -d "$MACOS_APP_PATH" ]; then
                echo "dmg"
                return
            fi
            ;;
        linux)
            # Check package managers
            if snap list bossterm >/dev/null 2>&1; then
                echo "snap"
                return
            fi
            if dpkg -l bossterm >/dev/null 2>&1; then
                echo "deb"
                return
            fi
            if rpm -q bossterm >/dev/null 2>&1; then
                echo "rpm"
                return
            fi
            if [ -f "$LINUX_JAR_PATH" ]; then
                echo "jar"
                return
            fi
            if [ -d "$LINUX_OPT_PATH" ]; then
                echo "manual"
                return
            fi
            ;;
    esac

    echo "unknown"
}

# ============================================================================
# macOS Installation
# ============================================================================

install_homebrew() {
    info "Installing BossTerm via Homebrew..."

    if ! has_command brew; then
        error "Homebrew is not installed. Please install it first: https://brew.sh"
        return 1
    fi

    # Add tap if not already added
    if ! brew tap | grep -q "$HOMEBREW_TAP"; then
        info "Adding Homebrew tap: $HOMEBREW_TAP"
        brew tap "$HOMEBREW_TAP"
    fi

    info "Installing BossTerm cask..."
    brew install --cask bossterm

    success "BossTerm installed via Homebrew"
}

install_dmg() {
    local version="$1"
    local dmg_url="${GITHUB_RELEASE_URL}/v${version}/BossTerm-${version}.dmg"
    local tmp_dmg
    local mount_point

    info "Installing BossTerm from DMG (version ${version})..."

    # Create temp file
    tmp_dmg=$(mktemp /tmp/BossTerm-XXXXXX.dmg)

    # Download DMG
    download_file "$dmg_url" "$tmp_dmg"

    # Mount DMG
    info "Mounting DMG..."
    mount_point=$(hdiutil attach "$tmp_dmg" -nobrowse -noautoopen | grep "/Volumes/" | awk '{print $NF}')

    if [ -z "$mount_point" ]; then
        error "Failed to mount DMG"
        rm -f "$tmp_dmg"
        exit 1
    fi

    # Copy app to Applications
    info "Installing to /Applications..."
    if [ -d "$MACOS_APP_PATH" ]; then
        warn "Removing existing installation..."
        rm -rf "$MACOS_APP_PATH"
    fi

    cp -R "${mount_point}/BossTerm.app" "$MACOS_APP_PATH"

    # Unmount and cleanup
    hdiutil detach "$mount_point" -quiet
    rm -f "$tmp_dmg"

    # Remove quarantine attribute
    xattr -rd com.apple.quarantine "$MACOS_APP_PATH" 2>/dev/null || true

    success "BossTerm installed to /Applications"
}

install_macos() {
    local version="$1"
    local method="$2"

    case "$method" in
        homebrew)
            install_homebrew
            ;;
        dmg)
            install_dmg "$version"
            ;;
        auto|"")
            # Try Homebrew first if available
            if has_command brew; then
                install_homebrew
            else
                install_dmg "$version"
            fi
            ;;
        *)
            error "Unknown installation method for macOS: $method"
            error "Supported methods: homebrew, dmg, auto"
            exit 1
            ;;
    esac
}

# ============================================================================
# Linux Installation
# ============================================================================

install_deb() {
    local version="$1"
    local arch="$2"
    local deb_arch
    local deb_url
    local tmp_deb

    # Map architecture
    case "$arch" in
        amd64) deb_arch="amd64" ;;
        arm64) deb_arch="arm64" ;;
        *)
            error "Unsupported architecture for Deb package: $arch"
            return 1
            ;;
    esac

    deb_url="${GITHUB_RELEASE_URL}/v${version}/bossterm_${version}_${deb_arch}.deb"

    info "Installing BossTerm from Deb package (version ${version}, arch ${deb_arch})..."

    # Create temp file
    tmp_deb=$(mktemp /tmp/bossterm-XXXXXX.deb)

    # Download Deb
    download_file "$deb_url" "$tmp_deb"

    # Install
    info "Installing package..."
    if has_sudo; then
        sudo dpkg -i "$tmp_deb" || true
        sudo apt-get install -f -y
    else
        error "sudo is required to install Deb packages"
        rm -f "$tmp_deb"
        exit 1
    fi

    # Cleanup
    rm -f "$tmp_deb"

    success "BossTerm installed via Deb package"
}

install_rpm() {
    local version="$1"
    local arch="$2"
    local rpm_arch
    local rpm_url
    local tmp_rpm

    # Map architecture
    case "$arch" in
        amd64) rpm_arch="x86_64" ;;
        arm64) rpm_arch="aarch64" ;;
        *)
            error "Unsupported architecture for RPM package: $arch"
            return 1
            ;;
    esac

    rpm_url="${GITHUB_RELEASE_URL}/v${version}/bossterm-${version}.${rpm_arch}.rpm"

    info "Installing BossTerm from RPM package (version ${version}, arch ${rpm_arch})..."

    # Create temp file
    tmp_rpm=$(mktemp /tmp/bossterm-XXXXXX.rpm)

    # Download RPM
    download_file "$rpm_url" "$tmp_rpm"

    # Install
    info "Installing package..."
    if has_sudo; then
        if has_command dnf; then
            sudo dnf install -y "$tmp_rpm"
        elif has_command yum; then
            sudo yum install -y "$tmp_rpm"
        else
            sudo rpm -i "$tmp_rpm"
        fi
    else
        error "sudo is required to install RPM packages"
        rm -f "$tmp_rpm"
        exit 1
    fi

    # Cleanup
    rm -f "$tmp_rpm"

    success "BossTerm installed via RPM package"
}

install_snap() {
    local version="$1"

    info "Installing BossTerm via Snap..."

    if ! has_command snap; then
        error "Snap is not installed. Please install snapd first."
        return 1
    fi

    if has_sudo; then
        sudo snap install bossterm --classic
    else
        error "sudo is required to install Snap packages"
        exit 1
    fi

    success "BossTerm installed via Snap"
}

install_jar() {
    local version="$1"
    local jar_url="${GITHUB_RELEASE_URL}/v${version}/bossterm-${version}.jar"
    local install_dir="$LINUX_OPT_PATH"
    local jar_path="$LINUX_JAR_PATH"

    info "Installing BossTerm JAR (version ${version})..."

    # Check for Java 17+
    if ! has_command java; then
        warn "Java not found. Attempting to install OpenJDK 17..."
        install_java
    fi

    # Verify Java version
    local java_version
    java_version=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
    if [ "$java_version" -lt 17 ] 2>/dev/null; then
        error "Java 17+ is required. Found version: $java_version"
        error "Please install Java 17 or later and try again."
        exit 1
    fi

    # Create installation directory
    if has_sudo; then
        sudo mkdir -p "$install_dir"

        # Download JAR
        local tmp_jar
        tmp_jar=$(mktemp /tmp/bossterm-XXXXXX.jar)
        download_file "$jar_url" "$tmp_jar"

        sudo mv "$tmp_jar" "$jar_path"
        sudo chmod 644 "$jar_path"

        # Create wrapper script
        info "Creating launcher script..."
        sudo tee /usr/local/bin/bossterm-jar >/dev/null << 'LAUNCHER'
#!/usr/bin/env bash
exec java -jar /opt/bossterm/bossterm.jar "$@"
LAUNCHER
        sudo chmod +x /usr/local/bin/bossterm-jar
    else
        # User-level installation
        install_dir="${HOME}/.local/share/bossterm"
        jar_path="${install_dir}/bossterm.jar"

        mkdir -p "$install_dir"
        mkdir -p "${HOME}/.local/bin"

        download_file "$jar_url" "$jar_path"
        chmod 644 "$jar_path"

        # Create wrapper script
        cat > "${HOME}/.local/bin/bossterm-jar" << LAUNCHER
#!/usr/bin/env bash
exec java -jar "$jar_path" "\$@"
LAUNCHER
        chmod +x "${HOME}/.local/bin/bossterm-jar"
    fi

    success "BossTerm JAR installed"
}

install_java() {
    local distro
    distro=$(detect_distro)

    info "Installing OpenJDK 17..."

    if has_sudo; then
        case "$distro" in
            ubuntu|debian|pop|linuxmint)
                sudo apt-get update
                sudo apt-get install -y openjdk-17-jre
                ;;
            fedora)
                sudo dnf install -y java-17-openjdk
                ;;
            centos|rhel|rocky|alma)
                sudo yum install -y java-17-openjdk
                ;;
            arch|manjaro)
                sudo pacman -S --noconfirm jre17-openjdk
                ;;
            alpine)
                sudo apk add openjdk17-jre
                ;;
            opensuse*)
                sudo zypper install -y java-17-openjdk
                ;;
            *)
                error "Unable to auto-install Java on $distro. Please install Java 17+ manually."
                exit 1
                ;;
        esac
    else
        error "sudo is required to install Java. Please install Java 17+ manually."
        exit 1
    fi

    success "OpenJDK 17 installed"
}

install_linux() {
    local version="$1"
    local method="$2"
    local arch="$3"
    local distro
    distro=$(detect_distro)

    case "$method" in
        deb)
            install_deb "$version" "$arch"
            ;;
        rpm)
            install_rpm "$version" "$arch"
            ;;
        snap)
            install_snap "$version"
            ;;
        jar)
            install_jar "$version"
            ;;
        auto|"")
            # Auto-detect best method based on distro
            case "$distro" in
                ubuntu|debian|pop|linuxmint|elementary|zorin)
                    install_deb "$version" "$arch"
                    ;;
                fedora|centos|rhel|rocky|alma)
                    install_rpm "$version" "$arch"
                    ;;
                *)
                    # Try snap first, then JAR
                    if has_command snap; then
                        install_snap "$version"
                    else
                        install_jar "$version"
                    fi
                    ;;
            esac
            ;;
        *)
            error "Unknown installation method for Linux: $method"
            error "Supported methods: deb, rpm, snap, jar, auto"
            exit 1
            ;;
    esac
}

# ============================================================================
# CLI Launcher Installation
# ============================================================================

install_cli() {
    local os="$1"
    local cli_path
    local cli_content

    info "Installing CLI launcher..."

    # Determine installation path
    if has_sudo; then
        cli_path="$CLI_SYSTEM_PATH"
    else
        cli_path="$CLI_USER_PATH"
        mkdir -p "$(dirname "$cli_path")"
    fi

    # Generate CLI script based on OS
    case "$os" in
        darwin)
            cli_content='#!/usr/bin/env bash
#
# BossTerm CLI Launcher Script
# Version: 1.0.0
#

APP_PATH="/Applications/BossTerm.app"
APP_NAME="BossTerm"
VERSION="1.0.0"

check_app() {
    if [ ! -d "$APP_PATH" ]; then
        echo "Error: BossTerm.app not found at $APP_PATH"
        echo "Run: curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.sh | bash"
        exit 1
    fi
}

open_bossterm() {
    open -a "$APP_NAME" "$@"
}

expand_path() {
    local path="$1"
    path="${path/#\~/$HOME}"
    if [[ ! "$path" =~ ^/ ]]; then
        path="$(cd "$path" 2>/dev/null && pwd || echo "$(pwd)/$path")"
    fi
    echo "$path"
}

show_help() {
    cat <<EOF
BossTerm - Modern Terminal Emulator
Version: $VERSION

Usage:
  bossterm                      Open BossTerm
  bossterm <path>               Open in directory (if path exists)
  bossterm -d <path>            Open in specified directory
  bossterm --new-window         Open a new window

Options:
  -d, --directory <path>   Start in specified directory
  -n, --new-window         Force open a new window
  -v, --version            Show version information
  -h, --help               Show this help message

EOF
}

main() {
    check_app

    if [ $# -eq 0 ]; then
        open_bossterm
        exit 0
    fi

    case "$1" in
        -h|--help) show_help; exit 0 ;;
        -v|--version) echo "BossTerm CLI version $VERSION"; exit 0 ;;
        -n|--new-window) open_bossterm -n; exit 0 ;;
        -d|--directory)
            [ -z "$2" ] && { echo "Error: Directory path required"; exit 1; }
            dir_path=$(expand_path "$2")
            [ ! -d "$dir_path" ] && { echo "Error: Directory not found: $dir_path"; exit 1; }
            BOSSTERM_CWD="$dir_path" open_bossterm
            exit 0
            ;;
        -*)
            echo "Error: Unknown option: $1"
            echo "Run '\''bossterm --help'\'' for usage"
            exit 1
            ;;
        *)
            path=$(expand_path "$1")
            if [ -d "$path" ]; then
                BOSSTERM_CWD="$path" open_bossterm
            elif [ -f "$path" ]; then
                BOSSTERM_CWD="$(dirname "$path")" open_bossterm
            else
                echo "Error: Path not found: $1"
                exit 1
            fi
            ;;
    esac
}

main "$@"
'
            ;;
        linux)
            cli_content='#!/usr/bin/env bash
#
# BossTerm CLI Launcher Script (Linux)
# Version: 1.0.0
#

VERSION="1.0.0"

find_bossterm() {
    local locations=(
        "/opt/bossterm/bin/BossTerm"
        "/usr/local/bin/BossTerm"
        "/usr/bin/BossTerm"
        "$HOME/.local/share/bossterm/bin/BossTerm"
        "/snap/bossterm/current/bin/bossterm"
    )

    for loc in "${locations[@]}"; do
        if [ -x "$loc" ]; then
            echo "$loc"
            return 0
        fi
    done

    # Check JAR installation
    if [ -f "/opt/bossterm/bossterm.jar" ] && command -v java >/dev/null 2>&1; then
        echo "jar:/opt/bossterm/bossterm.jar"
        return 0
    fi
    if [ -f "$HOME/.local/share/bossterm/bossterm.jar" ] && command -v java >/dev/null 2>&1; then
        echo "jar:$HOME/.local/share/bossterm/bossterm.jar"
        return 0
    fi

    # Check snap
    if [ -n "$SNAP" ]; then
        echo "$SNAP/bin/bossterm"
        return 0
    fi

    return 1
}

APP_PATH=$(find_bossterm)

check_app() {
    if [ -z "$APP_PATH" ]; then
        echo "Error: BossTerm not found"
        echo ""
        echo "Install with: curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.sh | bash"
        exit 1
    fi
}

open_bossterm() {
    if [ -n "$BOSSTERM_CWD" ]; then
        cd "$BOSSTERM_CWD" || exit 1
    fi

    if [[ "$APP_PATH" == jar:* ]]; then
        local jar_path="${APP_PATH#jar:}"
        nohup java -jar "$jar_path" "$@" >/dev/null 2>&1 &
    else
        nohup "$APP_PATH" "$@" >/dev/null 2>&1 &
    fi
    disown
}

expand_path() {
    local path="$1"
    path="${path/#\~/$HOME}"
    if [[ ! "$path" =~ ^/ ]]; then
        path="$(cd "$path" 2>/dev/null && pwd || echo "$(pwd)/$path")"
    fi
    echo "$path"
}

show_help() {
    cat <<EOF
BossTerm - Modern Terminal Emulator
Version: $VERSION

Usage:
  bossterm                      Open BossTerm
  bossterm <path>               Open in directory (if path exists)
  bossterm -d <path>            Open in specified directory
  bossterm --new-window         Open a new window

Options:
  -d, --directory <path>   Start in specified directory
  -n, --new-window         Force open a new window
  -v, --version            Show version information
  -h, --help               Show this help message

EOF
}

main() {
    check_app

    if [ $# -eq 0 ]; then
        open_bossterm
        exit 0
    fi

    case "$1" in
        -h|--help) show_help; exit 0 ;;
        -v|--version) echo "BossTerm CLI version $VERSION"; exit 0 ;;
        -n|--new-window) open_bossterm --new-window; exit 0 ;;
        -d|--directory)
            [ -z "$2" ] && { echo "Error: Directory path required"; exit 1; }
            dir_path=$(expand_path "$2")
            [ ! -d "$dir_path" ] && { echo "Error: Directory not found: $dir_path"; exit 1; }
            BOSSTERM_CWD="$dir_path" open_bossterm
            exit 0
            ;;
        -*)
            echo "Error: Unknown option: $1"
            echo "Run '\''bossterm --help'\'' for usage"
            exit 1
            ;;
        *)
            path=$(expand_path "$1")
            if [ -d "$path" ]; then
                BOSSTERM_CWD="$path" open_bossterm
            elif [ -f "$path" ]; then
                BOSSTERM_CWD="$(dirname "$path")" open_bossterm
            else
                echo "Error: Path not found: $1"
                exit 1
            fi
            ;;
    esac
}

main "$@"
'
            ;;
    esac

    # Install the CLI script
    if has_sudo && [ "$cli_path" = "$CLI_SYSTEM_PATH" ]; then
        echo "$cli_content" | sudo tee "$cli_path" >/dev/null
        sudo chmod +x "$cli_path"
    else
        echo "$cli_content" > "$cli_path"
        chmod +x "$cli_path"

        # Check if ~/.local/bin is in PATH
        if [[ ":$PATH:" != *":$HOME/.local/bin:"* ]]; then
            warn "\$HOME/.local/bin is not in your PATH"
            warn "Add this to your shell profile:"
            warn "  export PATH=\"\$HOME/.local/bin:\$PATH\""
        fi
    fi

    success "CLI launcher installed to $cli_path"
}

# ============================================================================
# Uninstallation
# ============================================================================

uninstall() {
    local os="$1"
    local method
    method=$(get_installed_method "$os")

    info "Uninstalling BossTerm..."

    case "$os" in
        darwin)
            uninstall_macos "$method"
            ;;
        linux)
            uninstall_linux "$method"
            ;;
    esac

    # Remove CLI launcher
    if [ -f "$CLI_SYSTEM_PATH" ]; then
        info "Removing CLI launcher from $CLI_SYSTEM_PATH..."
        if has_sudo; then
            sudo rm -f "$CLI_SYSTEM_PATH"
        else
            warn "Cannot remove $CLI_SYSTEM_PATH without sudo"
        fi
    fi

    if [ -f "$CLI_USER_PATH" ]; then
        info "Removing CLI launcher from $CLI_USER_PATH..."
        rm -f "$CLI_USER_PATH"
    fi

    # Remove config (ask first)
    if [ -d "$CONFIG_PATH" ]; then
        echo ""
        echo -n "Remove configuration directory ($CONFIG_PATH)? [y/N] "
        read -r response
        if [[ "$response" =~ ^[Yy]$ ]]; then
            rm -rf "$CONFIG_PATH"
            info "Configuration removed"
        else
            info "Configuration preserved at $CONFIG_PATH"
        fi
    fi

    success "BossTerm uninstalled"
}

uninstall_macos() {
    local method="$1"

    case "$method" in
        homebrew)
            info "Uninstalling via Homebrew..."
            brew uninstall --cask bossterm
            ;;
        dmg|manual|unknown)
            if [ -d "$MACOS_APP_PATH" ]; then
                info "Removing $MACOS_APP_PATH..."
                rm -rf "$MACOS_APP_PATH"
            fi
            ;;
    esac

    # Clean up macOS-specific files
    rm -rf ~/Library/Application\ Support/BossTerm 2>/dev/null || true
    rm -rf ~/Library/Caches/ai.rever.bossterm 2>/dev/null || true
    rm -f ~/Library/Preferences/ai.rever.bossterm.plist 2>/dev/null || true
    rm -rf ~/Library/Saved\ Application\ State/ai.rever.bossterm.savedState 2>/dev/null || true
}

uninstall_linux() {
    local method="$1"

    case "$method" in
        snap)
            info "Uninstalling via Snap..."
            if has_sudo; then
                sudo snap remove bossterm
            else
                warn "sudo required to remove Snap package"
            fi
            ;;
        deb)
            info "Uninstalling Deb package..."
            if has_sudo; then
                sudo dpkg -r bossterm || sudo apt-get remove -y bossterm
            else
                warn "sudo required to remove Deb package"
            fi
            ;;
        rpm)
            info "Uninstalling RPM package..."
            if has_sudo; then
                if has_command dnf; then
                    sudo dnf remove -y bossterm
                elif has_command yum; then
                    sudo yum remove -y bossterm
                else
                    sudo rpm -e bossterm
                fi
            else
                warn "sudo required to remove RPM package"
            fi
            ;;
        jar|manual|unknown)
            if has_sudo; then
                [ -d "$LINUX_OPT_PATH" ] && sudo rm -rf "$LINUX_OPT_PATH"
                [ -f /usr/local/bin/bossterm-jar ] && sudo rm -f /usr/local/bin/bossterm-jar
            fi
            [ -d "${HOME}/.local/share/bossterm" ] && rm -rf "${HOME}/.local/share/bossterm"
            [ -f "${HOME}/.local/bin/bossterm-jar" ] && rm -f "${HOME}/.local/bin/bossterm-jar"
            ;;
    esac
}

# ============================================================================
# Help and Usage
# ============================================================================

show_help() {
    cat << EOF
BossTerm Installation Script v${SCRIPT_VERSION}

Usage:
  curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.sh | bash
  ./install.sh [OPTIONS]

Options:
  -h, --help              Show this help message
  -v, --version VERSION   Install specific version (default: latest)
  -m, --method METHOD     Force installation method
  -u, --uninstall         Uninstall BossTerm
  --no-cli                Skip CLI launcher installation
  --dry-run               Show what would be done without executing
  --force                 Force reinstall even if already installed

Installation Methods:
  macOS:    homebrew, dmg, auto (default)
  Linux:    deb, rpm, snap, jar, auto (default)

Examples:
  # Install latest version (auto-detect best method)
  curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.sh | bash

  # Install specific version
  curl -fsSL https://raw.githubusercontent.com/kshivang/BossTerm/master/install.sh | bash -s -- --version 1.0.5

  # Install using specific method
  ./install.sh --method homebrew
  ./install.sh --method deb

  # Uninstall
  ./install.sh --uninstall

For more information: https://github.com/${GITHUB_REPO}

EOF
}

# ============================================================================
# Main
# ============================================================================

main() {
    local version=""
    local method="auto"
    local do_uninstall=false
    local install_cli_flag=true
    local dry_run=false
    local force=false

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help)
                show_help
                exit 0
                ;;
            -v|--version)
                version="$2"
                shift 2
                ;;
            -m|--method)
                method="$2"
                shift 2
                ;;
            -u|--uninstall)
                do_uninstall=true
                shift
                ;;
            --no-cli)
                install_cli_flag=false
                shift
                ;;
            --dry-run)
                dry_run=true
                shift
                ;;
            --force)
                force=true
                shift
                ;;
            *)
                error "Unknown option: $1"
                echo "Run '$0 --help' for usage"
                exit 1
                ;;
        esac
    done

    # Detect platform
    local os
    local arch
    os=$(detect_os)
    arch=$(detect_arch)

    echo ""
    echo -e "${CYAN}BossTerm Installation Script v${SCRIPT_VERSION}${NC}"
    echo -e "${CYAN}========================================${NC}"
    echo ""
    info "Platform: $os ($arch)"

    if [ "$os" = "unknown" ]; then
        error "Unsupported operating system"
        exit 1
    fi

    if [ "$os" = "windows" ]; then
        error "Windows is not yet supported. Please use WSL2 with Linux installation."
        exit 1
    fi

    if [ "$arch" = "unknown" ]; then
        warn "Unknown architecture, some installation methods may not work"
    fi

    # Handle uninstall
    if [ "$do_uninstall" = true ]; then
        if ! check_installed "$os"; then
            warn "BossTerm does not appear to be installed"
            exit 0
        fi
        uninstall "$os"
        exit 0
    fi

    # Check if already installed
    if check_installed "$os" && [ "$force" = false ]; then
        local installed_method
        installed_method=$(get_installed_method "$os")
        warn "BossTerm is already installed (method: $installed_method)"
        warn "Use --force to reinstall or --uninstall to remove first"
        exit 0
    fi

    # Get version if not specified
    if [ -z "$version" ]; then
        info "Fetching latest version..."
        version=$(get_latest_version)
    fi

    info "Version: $version"
    info "Method: $method"

    if [ "$dry_run" = true ]; then
        echo ""
        info "Dry run mode - no changes will be made"
        info "Would install BossTerm $version on $os ($arch) using method: $method"
        exit 0
    fi

    echo ""

    # Install
    case "$os" in
        darwin)
            install_macos "$version" "$method"
            ;;
        linux)
            install_linux "$version" "$method" "$arch"
            ;;
    esac

    # Install CLI launcher
    if [ "$install_cli_flag" = true ]; then
        echo ""
        install_cli "$os"
    fi

    # Success message
    echo ""
    echo -e "${GREEN}========================================${NC}"
    success "BossTerm $version installed successfully!"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo "To start BossTerm:"
    case "$os" in
        darwin)
            echo "  - Open from Applications folder"
            echo "  - Or run: bossterm"
            ;;
        linux)
            echo "  - Open from your application menu"
            echo "  - Or run: bossterm"
            ;;
    esac
    echo ""
    echo "For shell integration (recommended), add to your shell profile:"
    echo ""
    echo "  # Bash (~/.bashrc)"
    echo "  PROMPT_COMMAND='echo -ne \"\\033]7;file://\${HOSTNAME}\${PWD}\\007\"'"
    echo ""
    echo "  # Zsh (~/.zshrc)"
    echo "  precmd() { echo -ne \"\\033]7;file://\${HOST}\${PWD}\\007\" }"
    echo ""
}

# Run main
main "$@"
