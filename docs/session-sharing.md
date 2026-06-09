# Session Sharing

BossTerm can share a live terminal with another device — to **watch** it, or to **control** it —
without any cloud relay or account. Your machine *is* the server: BossTerm runs a small embedded
web server, serves an [xterm.js](https://xtermjs.org)-based viewer over a WebSocket, and other
devices reach it over your LAN or through a tunnel. The session key never leaves the URL fragment,
so even a public tunnel relay can't read your session.

> Session sharing is **off by default**. Turn it on under **Settings → Session Sharing**, then
> use **Share** from a tab's menu.

---

## Table of contents

- [What you can share (scopes)](#what-you-can-share-scopes)
- [View vs. control](#view-vs-control)
- [Reach: LAN, Tailscale, Cloudflare](#reach-lan-tailscale-cloudflare)
- [Starting a share](#starting-a-share)
- [The web viewer](#the-web-viewer)
- [The native "Add remote" client](#the-native-add-remote-client)
- [Remote MCP](#remote-mcp)
- [Security & end-to-end encryption](#security--end-to-end-encryption)
- [Settings reference](#settings-reference)
- [Troubleshooting](#troubleshooting)

---

## What you can share (scopes)

Pick a scope in the Share dialog — you can switch it live:

| Scope | Shares | Notes |
|-------|--------|-------|
| **Tab** | The selected tab and its splits | The default. |
| **Window** | Every tab of the window that owns the tab | Reacts to tabs opening/closing; viewers switch tabs themselves. |
| **All windows** | Every tab of every BossTerm window | Reacts to windows opening/closing; the viewer groups tabs by window. |

Splits are preserved: a viewer sees the same pane layout, and on a phone the splits collapse into
swipeable sub-tabs.

## View vs. control

Each share produces **two links** (and two QR codes):

- **View** — read-only. The viewer sees output but can't type.
- **Control** — typing access: input, opening/closing tabs and splits, launching AI assistants,
  renaming, etc.

A view-only viewer can **request control** mid-session; you get an approval prompt and can grant or
deny. Whether *connecting* needs approval at all is governed by
[`sessionSharingApprovalScope`](#settings-reference):

- `funnel` (default) — approval is required only for **public** links (a Cloudflare/Tailscale
  Funnel tunnel or a custom public URL); LAN/loopback is trusted and connects without a prompt.
- `all` — always require approval.
- `off` — never prompt.

When you approve a device it receives a rolling 24-hour access key, so reconnects from the same
device skip the prompt. Granting control to a view-only viewer is remembered the same way.

## Reach: LAN, Tailscale, Cloudflare

How the viewer URL is produced is set by **`shareTailscaleMode`** (Settings → Session Sharing →
Remote Access, also switchable live in the Share dialog):

| Mode | Reach | URL | Needs |
|------|-------|-----|-------|
| `off` | Your local network | `http://<lan-ip>:7677/…` | Nothing — works out of the box |
| `serve` | Your Tailscale tailnet | `https://<host>.ts.net/…` | Tailscale + MagicDNS/HTTPS |
| `funnel` | Public internet | `https://<host>.ts.net/…` | Tailscale Funnel enabled |
| `cloudflare` | Public internet | `https://<random>.trycloudflare.com/…` | **Nothing — the default** |

**Cloudflare** is the default: BossTerm downloads `cloudflared` for you on first use (no account, no
config) and opens a quick tunnel. Each session gets a fresh random hostname. To make the QR appear
instantly, the tunnel is **pre-warmed** at startup (when sharing is enabled with a remote provider)
and **kept warm** across re-shares — so the verified public URL is already published by the time you
open the Share dialog. Disabling sharing, switching the mode to `off`, or quitting tears the tunnel
down.

If you front the server with your own reverse proxy, set
[`sessionSharingPublicUrl`](#settings-reference) and that URL is advertised instead.

## Starting a share

1. Enable sharing once: **Settings → Session Sharing → Enable**.
2. Right-click a tab (or use the Tab menu) → **Share tab / Share window / Share all windows**.
3. The Share dialog opens with:
   - an editable **session name** (defaults to `you_your-machine`),
   - the **QR code** with a **View / Control** toggle and copyable links,
   - the **scope** picker (Tab / Window / All windows),
   - the **remote-access** mode and its status.
4. Scan the QR or send the link. On another device it opens the [web viewer](#the-web-viewer); in
   another BossTerm it can be dialed with [Add remote](#the-native-add-remote-client).

A small indicator in the tab bar shows while you're sharing (toggle with
`sessionSharingShowIndicator`).

## The web viewer

The viewer is plain xterm.js served from the share server — it runs in any modern browser
(Chrome, Safari, Firefox, Edge; iOS and Android included) with nothing to install. It's tuned for
phones:

- **Soft keyboard** — the view lifts so the cursor stays visible above the keyboard, and the
  keyboard stays up while a TUI streams output.
- **On-screen key bar** — Esc, Tab, Enter, Ctrl combos and arrows, plus a **⌨ toggle** to show/hide
  the soft keyboard.
- **Touch** — drag to scroll (including inside mouse-reporting TUIs), pinch to zoom, and a
  fit-to-screen mode so the whole grid fits without horizontal panning.
- **Links** — URLs are clickable; text selection works by touch.
- **Tabs & splits** — switch tabs and panes from chips; drag split dividers to resize (with
  control).
- **Status** — a presence badge (viewer count), the terminal size, an end-to-end verification code,
  and an **MCP pill** (see [Remote MCP](#remote-mcp)).

## The native "Add remote" client

Another BossTerm can connect to a share as a first-class client instead of a browser:

- **Add remote** → paste a share link. The host's shared tabs mirror into your window as remote
  tabs, grouped by window when the host shared "all windows" (e.g. `Window 2 › Tab 3 (via host)`).
- Control requests relay **up the chain**: viewer → host → the host's own upstream, so you can steer
  a session two hops away (each host approves in turn).
- A client refuses to add a link that points back at its own shares (no mirror loops).

## Remote MCP

If the host has the [BossTerm MCP server](mcp-server.md) running, sharing carries it along:

- The viewer shows an **MCP pill** reflecting the host's MCP state; from it you can toggle the
  server and attach AI CLIs — the actions run on the host.
- MCP tool calls against shared tabs are **relayed to the host's** MCP server, using the host's
  configured server name and port.

This lets a phone or a second machine point an AI client at the host's terminals through the same
shared session.

## Security & end-to-end encryption

- **The key never reaches the server.** The per-share session secret (32 random bytes) is placed in
  the URL **fragment** — `…/?t=<token>#k=<secret>`. Browsers never transmit the part after `#`, so
  no server or tunnel relay (Cloudflare, Tailscale) ever sees it.
- **Per-connection keys.** Client and host exchange random salts (in the clear — useless on their
  own) and derive a fresh AES-256-GCM key with HKDF-SHA256. Every frame is encrypted; a direction
  byte is mixed in as additional data so frames can't be reflected back.
- **Verification code.** The Share dialog and the viewer each show a short code (the first 4 bytes
  of `SHA-256(secret)`, as 8 hex digits). If they match, both ends hold the same untampered key.
- **Tokens.** View and control are **separate bearer tokens**, so you can hand out read-only access
  without exposing control, and revoke one role independently of the key.
- **Trusted hosts.** Loopback and private addresses (`127.0.0.1`, `10.*`, `192.168.*`,
  `172.16–31.*`, `169.254.*`, `*.local`, `*.ts.net`) are treated as private. Plaintext links are
  allowed only there; any `https`/tunnel link is end-to-end encrypted. An old plaintext-only client
  connecting to a public tunnel is rejected with an "update BossTerm" message.

## Settings reference

All under **Settings → Session Sharing**, persisted in `~/.bossterm/settings.json`:

| Setting | Default | Meaning |
|---------|---------|---------|
| `sessionSharingEnabled` | `false` | Master switch. Off ⇒ sharing isn't offered and any tunnel is torn down. |
| `sessionSharingPort` | `7677` | TCP port for the share server. If busy, it tries the next free port. |
| `sessionSharingBind` | `"lan"` | `"lan"` (bind `0.0.0.0`), `"loopback"` (`127.0.0.1` only), or `"custom"`. |
| `sessionSharingBindHost` | `""` | Host to bind when `sessionSharingBind` is `"custom"` (blank ⇒ `127.0.0.1`). |
| `shareTailscaleMode` | `"cloudflare"` | Remote-access provider: `"off"`, `"serve"`, `"funnel"`, `"cloudflare"`. |
| `sessionSharingPublicUrl` | `""` | Advertise this URL instead of the bound/tunnel URL (for a custom proxy). |
| `sessionSharingApprovalScope` | `"funnel"` | Require join approval: `"all"`, `"off"`, or `"funnel"` (only for public links). |
| `sessionSharingShowIndicator` | `true` | Show the sharing indicator in the tab bar. |

> Note `shareTailscaleMode` defaults to `cloudflare`, but sharing is still gated by
> `sessionSharingEnabled` (off by default) — so no tunnel opens until you turn sharing on.

## Troubleshooting

- **QR/link opens "tunnel error" or a blank page** — give the Cloudflare tunnel a moment on the
  very first share (it downloads `cloudflared` once). Use **Refresh link** in the dialog to spin a
  fresh tunnel. Quick-tunnel hostnames are ephemeral; a new one is minted each session.
- **Viewer says "Forbidden"** — the link is pointing at the wrong local service. Re-open the Share
  dialog and use its current link/QR (it always reflects the live tunnel).
- **Can't connect on the LAN** — confirm both devices are on the same network and your firewall
  allows inbound on `sessionSharingPort` (7677). Check `sessionSharingBind` is `"lan"`, not
  `"loopback"`.
- **Tailscale modes do nothing** — ensure `tailscale` is installed and signed in; Funnel needs to be
  enabled for the node, and Serve needs MagicDNS/HTTPS certificates.
- **Verification codes differ** — do **not** trust the session; the relay or link may be tampered.
  Regenerate the link.
- **Phone keyboard hides the input in a TUI** — tap the **⌨** key-bar button to toggle the keyboard;
  the view re-aligns the cursor above it.

See also: [BossTerm MCP Server](mcp-server.md) · [Troubleshooting](troubleshooting.md).
