# Installation

This guide covers all installation methods for BossTerm.

---

## macOS

### Homebrew (Recommended)

```bash
brew tap kshivang/bossterm
brew install --cask bossterm
```

### DMG Download

1. Download the latest DMG from [GitHub Releases](https://github.com/kshivang/BossTerm/releases)
2. Open the DMG file
3. Drag BossTerm to your Applications folder
4. Launch from Applications or Spotlight

---

## Linux

### Debian/Ubuntu (.deb)

```bash
# Download the .deb package from GitHub Releases
wget https://github.com/kshivang/BossTerm/releases/latest/download/bossterm_amd64.deb

# Install
sudo dpkg -i bossterm_*_amd64.deb

# Install dependencies if needed
sudo apt-get install -f
```

### Fedora/RHEL (.rpm)

```bash
# Download the .rpm package from GitHub Releases
wget https://github.com/kshivang/BossTerm/releases/latest/download/bossterm.x86_64.rpm

# Install
sudo dnf install bossterm-*.x86_64.rpm
```

### Snap

```bash
# From Snap Store
sudo snap install bossterm --classic

# Or manual installation from downloaded .snap file
sudo snap install bossterm_*.snap --classic --dangerous
```

---

## Windows

### JAR (Cross-platform)

Requires Java 17 or later:

```bash
# Download bossterm-*.jar from GitHub Releases
java -jar bossterm-*.jar
```

---

## Build from Source

### Prerequisites

- JDK 17 or later
- Git

### Steps

```bash
# Clone the repository
git clone https://github.com/kshivang/BossTerm.git
cd BossTerm

# Run directly
./gradlew :bossterm-app:run

# Or build distributable packages
./gradlew :bossterm-app:packageDmg      # macOS
./gradlew :bossterm-app:packageDeb      # Debian/Ubuntu
./gradlew :bossterm-app:packageRpm      # Fedora/RHEL
```

---

## Verifying Installation

After installation, launch BossTerm and verify:

1. Terminal opens with your default shell
2. Type `echo $TERM` - should show `xterm-256color`
3. Try `ls --color` to verify color support

---

## Next Steps

- [[Configuration]] - Customize BossTerm settings
- [[Shell-Integration]] - Set up OSC 7 and OSC 133 for enhanced features
- [[Keyboard-Shortcuts]] - Learn the keyboard shortcuts
