#!/bin/bash
# Script to push wiki pages to GitHub Wiki
#
# PREREQUISITE: You must first initialize the wiki via GitHub UI:
# 1. Go to https://github.com/kshivang/BossTerm/wiki
# 2. Click "Create the first page"
# 3. Save the page (can be empty or minimal)
# 4. Then run this script

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WIKI_DIR="$SCRIPT_DIR/wiki"
TEMP_DIR="/tmp/BossTerm.wiki.push"

echo "BossTerm Wiki Push Script"
echo "========================="
echo ""

# Check if wiki directory exists
if [ ! -d "$WIKI_DIR" ]; then
    echo "Error: Wiki directory not found at $WIKI_DIR"
    exit 1
fi

# Clean up any previous temp directory
rm -rf "$TEMP_DIR"

# Clone the wiki repo
echo "Cloning wiki repository..."
if ! git clone https://github.com/kshivang/BossTerm.wiki.git "$TEMP_DIR" 2>/dev/null; then
    echo ""
    echo "Error: Could not clone wiki repository."
    echo ""
    echo "Please initialize the wiki first:"
    echo "1. Go to https://github.com/kshivang/BossTerm/wiki"
    echo "2. Click 'Create the first page'"
    echo "3. Save the page"
    echo "4. Run this script again"
    exit 1
fi

# Copy wiki files
echo "Copying wiki files..."
cp "$WIKI_DIR"/*.md "$TEMP_DIR/"

# Commit and push
cd "$TEMP_DIR"
git add .
git commit -m "docs: Update wiki documentation

🤖 Generated with [Claude Code](https://claude.com/claude-code)" || {
    echo "No changes to commit"
    exit 0
}

echo "Pushing to GitHub..."
git push origin master

# Clean up
rm -rf "$TEMP_DIR"

echo ""
echo "Wiki updated successfully!"
echo "View at: https://github.com/kshivang/BossTerm/wiki"
