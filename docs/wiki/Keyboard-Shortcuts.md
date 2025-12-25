# Keyboard Shortcuts

Complete keyboard shortcut reference for BossTerm.

---

## Window & Tab Management

| Shortcut | Action |
|----------|--------|
| Ctrl/Cmd+N | New window |
| Ctrl/Cmd+T | New tab |
| Ctrl/Cmd+W | Close tab/pane |
| Ctrl+Tab | Next tab |
| Ctrl+Shift+Tab | Previous tab |
| Ctrl/Cmd+1 | Jump to tab 1 |
| Ctrl/Cmd+2 | Jump to tab 2 |
| Ctrl/Cmd+3 | Jump to tab 3 |
| Ctrl/Cmd+4 | Jump to tab 4 |
| Ctrl/Cmd+5 | Jump to tab 5 |
| Ctrl/Cmd+6 | Jump to tab 6 |
| Ctrl/Cmd+7 | Jump to tab 7 |
| Ctrl/Cmd+8 | Jump to tab 8 |
| Ctrl/Cmd+9 | Jump to last tab |

---

## Split Panes

| Shortcut | Action |
|----------|--------|
| Ctrl/Cmd+D | Split vertically (side by side) |
| Ctrl/Cmd+Shift+D | Split horizontally (top/bottom) |
| Ctrl/Cmd+Option+Up | Navigate to pane above |
| Ctrl/Cmd+Option+Down | Navigate to pane below |
| Ctrl/Cmd+Option+Left | Navigate to pane on left |
| Ctrl/Cmd+Option+Right | Navigate to pane on right |
| Ctrl/Cmd+] | Navigate to next pane (cycle) |
| Ctrl/Cmd+[ | Navigate to previous pane (cycle) |

---

## Clipboard

| Shortcut | Action |
|----------|--------|
| Ctrl/Cmd+C | Copy selection |
| Ctrl/Cmd+V | Paste |
| Ctrl/Cmd+A | Select all |
| Ctrl/Cmd+Shift+C | Copy (alternative) |
| Ctrl/Cmd+Shift+V | Paste (alternative) |

**Note**: When `copyOnSelect` is enabled, text is automatically copied when selected.

---

## Search

| Shortcut | Action |
|----------|--------|
| Ctrl/Cmd+F | Open search |
| Enter | Find next match |
| Shift+Enter | Find previous match |
| Escape | Close search |

---

## Terminal Control

| Shortcut | Action |
|----------|--------|
| Ctrl+Space | Toggle IME (Input Method) |
| Ctrl/Cmd+, | Open settings |
| Ctrl/Cmd+Shift+D | Toggle debug panel |

---

## Mouse Actions

| Action | Behavior |
|--------|----------|
| Click | Position cursor / Focus terminal |
| Double-click | Select word |
| Triple-click | Select line |
| Drag | Select text |
| Ctrl/Cmd+Click | Open link |
| Middle-click | Paste (if enabled) |
| Right-click | Context menu |
| Scroll | Scroll through history |
| Shift+Click | Extend selection |
| Shift+Scroll | Force local scroll (bypass mouse reporting) |

---

## Mouse Reporting Bypass

When a terminal app has mouse reporting enabled (vim, tmux, htop, etc.), hold **Shift** to perform local actions:

| Action | Without Shift | With Shift |
|--------|---------------|------------|
| Click | Sent to app | Local selection |
| Scroll | Sent to app | Local scroll |
| Drag | Sent to app | Local selection |

---

## Terminal Apps

These shortcuts are passed to terminal applications:

| Shortcut | Common Usage |
|----------|--------------|
| Ctrl+C | Interrupt/Cancel |
| Ctrl+D | EOF/Exit |
| Ctrl+Z | Suspend |
| Ctrl+L | Clear screen |
| Ctrl+R | Reverse search (bash/zsh) |
| Ctrl+A | Beginning of line |
| Ctrl+E | End of line |
| Ctrl+U | Clear line before cursor |
| Ctrl+K | Clear line after cursor |
| Ctrl+W | Delete word before cursor |

---

## See Also

- [[Features]] - Full feature overview
- [[Configuration]] - Customize shortcuts (coming soon)
