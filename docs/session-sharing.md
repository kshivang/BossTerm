# Session Sharing

BossTerm can share a live terminal with another device - to **watch** it, or to **control** it -
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
- [Live sessions on your BOSS account](#live-sessions-on-your-boss-account)
- [Settings reference](#settings-reference)
- [Troubleshooting](#troubleshooting)

---

## What you can share (scopes)

Pick a scope in the Share dialog - you can switch it live:

| Scope | Shares | Notes |
|-------|--------|-------|
| **Tab** | The selected tab and its splits | The default. |
| **Window** | Every tab of the window that owns the tab | Reacts to tabs opening/closing; viewers switch tabs themselves. |
| **All windows** | Every tab of every BossTerm window | Reacts to windows opening/closing; the viewer groups tabs by window. |

Splits are preserved: a viewer sees the same pane layout, and on a phone the splits collapse into
swipeable sub-tabs.

## View vs. control

Each share produces **two links** (and two QR codes):

- **View** - read-only. The viewer sees output but can't type.
- **Control** - typing access: input, opening/closing tabs and splits, launching AI assistants,
  renaming, etc.

A view-only viewer can **request control** mid-session; you get an approval prompt and can grant or
deny. Whether *connecting* needs approval at all is governed by
[`sessionSharingApprovalScope`](#settings-reference):

- `funnel` (default) - approval is required only for **public** links (a Cloudflare/Tailscale
  Funnel tunnel or a custom public URL); LAN/loopback is trusted and connects without a prompt.
- `all` - always require approval.
- `off` - never prompt.

When you approve a device it receives a rolling 24-hour access key, so reconnects from the same
device skip the prompt. Granting control to a view-only viewer is remembered the same way.

## Reach: LAN, Tailscale, Cloudflare

How the viewer URL is produced is set by **`shareTailscaleMode`** (Settings → Session Sharing →
Remote Access, also switchable live in the Share dialog):

| Mode | Reach | URL | Needs |
|------|-------|-----|-------|
| `off` | Your local network | `http://<lan-ip>:7677/…` | Nothing - works out of the box |
| `serve` | Your Tailscale tailnet | `https://<host>.ts.net/…` | Tailscale + MagicDNS/HTTPS |
| `funnel` | Public internet | `https://<host>.ts.net/…` | Tailscale Funnel enabled |
| `cloudflare` | Public internet | `https://<random>.trycloudflare.com/…` | **Nothing - the default** |

**Cloudflare** is the default: BossTerm downloads `cloudflared` for you on first use (no account, no
config) and opens a quick tunnel. Each session gets a fresh random hostname. To make the QR appear
instantly, the tunnel is **pre-warmed** at startup (when sharing is enabled with a remote provider)
and **kept warm** across re-shares - so the verified public URL is already published by the time you
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

The viewer is plain xterm.js served from the share server - it runs in any modern browser
(Chrome, Safari, Firefox, Edge; iOS and Android included) with nothing to install. It's tuned for
phones:

- **Soft keyboard** - the view lifts so the cursor stays visible above the keyboard, and the
  keyboard stays up while a TUI streams output.
- **On-screen key bar** - Esc, Tab, Enter, Ctrl combos and arrows, plus a **⌨ toggle** to show/hide
  the soft keyboard.
- **Touch** - drag to scroll (including inside mouse-reporting TUIs), pinch to zoom, and a
  fit-to-screen mode so the whole grid fits without horizontal panning.
- **Links** - URLs are clickable; text selection works by touch.
- **Tabs & splits** - switch tabs and panes from chips; drag split dividers to resize (with
  control).
- **Status** - a presence badge (viewer count), the terminal size, an end-to-end verification code,
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
  server and attach AI CLIs - the actions run on the host.
- MCP tool calls against shared tabs are **relayed to the host's** MCP server, using the host's
  configured server name and port.

This lets a phone or a second machine point an AI client at the host's terminals through the same
shared session.

## Security & end-to-end encryption

- **The key never reaches the server.** The per-share session secret (32 random bytes) is placed in
  the URL **fragment** - `…/?t=<token>#k=<secret>`. Browsers never transmit the part after `#`, so
  no server or tunnel relay (Cloudflare, Tailscale) ever sees it.
- **Per-connection keys.** Client and host exchange random salts (in the clear - useless on their
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

## Live sessions on your BOSS account

Sign in (menu > **Sign In...**) and every share you start is also registered against your BOSS
account, so you can open it from any browser without pasting a link:

1. Open <https://cli.risaboss.com> on the other device.
2. Enter the email you signed into BossTerm with and click the button in the email.
3. You land back on the page, signed in. With exactly one live session it opens straight away;
   with several you get a list (device, session name, scope, E2E badge) and pick one.

What is published, and to whom:

- One row per share in a Supabase table (`terminal_sessions`) that only your account can read
  (row-level security on `auth.uid()`). BossTerm writes it as **you**, with your own session
  token; there is no service key in the app.
- The row holds the **links** (read-only and an account link), your `username_machine` device
  name, the session name, scope, app version and server-side timestamps. Never terminal output,
  input or history.
- The links include the `#k=` end-to-end secret, so Supabase (not the Cloudflare relay) could read
  it. That is the trade for opening a session from a phone with nothing to paste; turn
  `publishSessionsToAccount` off if you would rather not.
- BossTerm heartbeats each row every 30 s (an upsert; the server stamps the time). The page shows a
  session for 90 s after the last heartbeat, and stale rows are swept server-side after 15 min.
  Quitting, unsharing or signing out deletes your rows immediately.

The **account link** is a third token on every share, next to the view and control tokens. It
grants control and is **auto-admitted**: a device arriving on it over an end-to-end encrypted
connection skips the approval prompt, since holding the secret from your own registry is the
proof. That link carries its **own** `#k` secret (derived one-way from the share's), so someone
holding your read-only link cannot combine it with a relay-logged account token to skip the
prompt; the E2E badge for an account-link viewer therefore differs from the Share sheet's code,
and the live-sessions page shows the right one. The account link is never shown in the Share
sheet; the ordinary view/control links still prompt as `sessionSharingApprovalScope` says.

**Automatic sharing.** While you are signed in and publishing, BossTerm keeps one all-windows
share running by itself (`autoShareToAccount`, on by default), so the page always lists this
machine with no Share Tab step. It is a separate kind of share from the ones you start: the tab
Share/Stop button does not show or stop it, switching **Enable Session Sharing** off leaves it
running (only your own shares stop), and it is reached over Cloudflare even when the remote mode
is "off". The only things that stop it are its two switches, in Settings > Session Sharing and in
the Share dialog's collapsed "Auto-share to <your email>" section (above Remote access), or
signing out. It never touches a share you started by hand.

When a session opened from the page ends (host stopped sharing, connection lost for good, request
denied), the viewer returns to the page after a couple of seconds instead of showing "you can
close this tab". The page tags the link with `from=live-sessions`; a plain share link never
redirects anywhere.

**Your other devices, inside BossTerm.** The Remote Sessions window (the cloud "Add remote"
button) lists the sessions your other signed-in BossTerms are sharing, refreshed on open and every
15 seconds. Connect mirrors that device's tabs here through the native client, using the account
link, so the other machine does not prompt for approval. Your own shares are filtered out.

Not covered yet: daemon-mode shares, and BossTerm embedded inside BossConsole (the `terminal-tab`
plugin), where the account menu is hidden.

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
| `publishSessionsToAccount` | `true` | While signed in, list every active share under your BOSS account (see below). |
| `autoShareToAccount` | `true` | With the above, keep an all-windows share running automatically while signed in. |

> Note `shareTailscaleMode` defaults to `cloudflare`, but sharing is still gated by
> `sessionSharingEnabled` (off by default) - so no tunnel opens until you turn sharing on.

## Troubleshooting

- **QR/link opens "tunnel error" or a blank page** - give the Cloudflare tunnel a moment on the
  very first share (it downloads `cloudflared` once). Use **Refresh link** in the dialog to spin a
  fresh tunnel. Quick-tunnel hostnames are ephemeral; a new one is minted each session.
- **Viewer says "Forbidden"** - the link is pointing at the wrong local service. Re-open the Share
  dialog and use its current link/QR (it always reflects the live tunnel).
- **Can't connect on the LAN** - confirm both devices are on the same network and your firewall
  allows inbound on `sessionSharingPort` (7677). Check `sessionSharingBind` is `"lan"`, not
  `"loopback"`.
- **Tailscale modes do nothing** - ensure `tailscale` is installed and signed in; Funnel needs to be
  enabled for the node, and Serve needs MagicDNS/HTTPS certificates.
- **Verification codes differ** - do **not** trust the session; the relay or link may be tampered.
  Regenerate the link.
- **Phone keyboard hides the input in a TUI** - tap the **⌨** key-bar button to toggle the keyboard;
  the view re-aligns the cursor above it.

See also: [BossTerm MCP Server](mcp-server.md) · [Troubleshooting](troubleshooting.md).
