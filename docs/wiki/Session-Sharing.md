# Session Sharing

BossTerm can share a live terminal with another device — to **watch** it or to **control** it —
with **no cloud relay and no account**. Your machine is the server: BossTerm runs a small embedded
web server, serves an [xterm.js](https://xtermjs.org) viewer over a WebSocket, and other devices
reach it over your LAN or through a tunnel. The session key never leaves the URL fragment, so even
a public tunnel relay can't read your session.

> Session sharing is **off by default**. Turn it on under **Settings → Session Sharing**, then use
> **Share** from a tab's menu.

---

## What you can share

| Scope | Shares |
|-------|--------|
| **Tab** | The selected tab and its splits (the default) |
| **Window** | Every tab of the owning window (viewers switch tabs themselves) |
| **All windows** | Every tab of every window, grouped by window in the viewer |

## View vs. control

Each share produces a read-only **view** link and a **control** link (typing access) — each with
its own QR code. A view-only viewer can **request control** mid-session; you approve from a prompt.
Whether *connecting* needs approval is set by `sessionSharingApprovalScope`:

- `funnel` (default) — approval only for **public** links; LAN/loopback is trusted.
- `all` — always require approval.
- `off` — never prompt.

Approved devices get a rolling 24-hour access key, so reconnects skip the prompt.

## Reach: LAN, Tailscale, Cloudflare

Set by `shareTailscaleMode` (Settings → Session Sharing → Remote Access; also switchable in the
Share dialog):

| Mode | Reach | URL | Needs |
|------|-------|-----|-------|
| `off` | Local network | `http://<lan-ip>:7677/…` | Nothing |
| `serve` | Tailscale tailnet | `https://<host>.ts.net/…` | Tailscale + HTTPS |
| `funnel` | Public internet | `https://<host>.ts.net/…` | Tailscale Funnel |
| `cloudflare` | Public internet | `https://<rand>.trycloudflare.com/…` | **Nothing — default** |

**Cloudflare** is the default: `cloudflared` is fetched automatically (no account), and the tunnel
is **pre-warmed** at startup and **kept warm** across re-shares, so the QR is ready the moment you
hit Share. Set `sessionSharingPublicUrl` to advertise your own reverse-proxy URL instead.

## The web viewer

Plain xterm.js in any modern browser (iOS/Android included), tuned for touch:

- Soft-keyboard push (cursor stays visible; keyboard stays up during TUI output)
- On-screen key bar — Esc / Tab / Enter / Ctrl / arrows + a **⌨ toggle**
- Drag-to-scroll (even inside TUIs), pinch-zoom, fit-to-screen
- Clickable links, touch selection
- Tab/split switching, presence badge, terminal size, E2E verification code, and an **MCP pill**

## Native "Add remote" client

Another BossTerm can dial a share with **Add remote**: the host's shared tabs mirror into your
window (grouped by window for "all windows" shares). Control relays **up the chain**
(viewer → host → its upstream), and a client won't add a link pointing back at its own shares.

## Remote MCP

If the host runs the [[BossTerm MCP Server|MCP-Server]], sharing carries it: the viewer's **MCP
pill** toggles the host's server and attaches AI CLIs (executed on the host), and MCP tool calls on
shared tabs are relayed to the host's MCP server. The native remote client surfaces this as
**Remote MCP**.

## Security & end-to-end encryption

- **The key never reaches the server** — the per-share secret rides in the URL **fragment**
  (`…/?t=<token>#k=<secret>`), which browsers never transmit. No tunnel relay sees it.
- **Per-connection keys** — client and host exchange salts and derive a fresh AES-256-GCM key via
  HKDF-SHA256; every frame is encrypted, with a direction byte as additional data.
- **Verification code** — both ends show a short code (first 4 bytes of `SHA-256(secret)`); matching
  codes confirm the same untampered key.
- **Separate view/control bearer tokens**, revocable per role.
- **Trusted hosts** — loopback and private ranges (`10.*`, `192.168.*`, `172.16–31.*`, `*.local`,
  `*.ts.net`) allow plaintext; any `https`/tunnel link is end-to-end encrypted.

## Settings reference

| Setting | Default | Meaning |
|---------|---------|---------|
| `sessionSharingEnabled` | `false` | Master switch; off tears down any tunnel |
| `sessionSharingPort` | `7677` | Share-server port (tries the next free one if busy) |
| `sessionSharingBind` | `"lan"` | `"lan"` (0.0.0.0), `"loopback"`, or `"custom"` |
| `sessionSharingBindHost` | `""` | Host for `"custom"` bind |
| `shareTailscaleMode` | `"cloudflare"` | `"off"`, `"serve"`, `"funnel"`, `"cloudflare"` |
| `sessionSharingPublicUrl` | `""` | Advertise this URL instead (custom proxy) |
| `sessionSharingApprovalScope` | `"funnel"` | `"all"`, `"off"`, or `"funnel"` (public only) |
| `sessionSharingShowIndicator` | `true` | Show the sharing indicator in the tab bar |

## See Also

- [[BossTerm MCP Server|MCP-Server]] - Expose tabs to AI clients
- [[Configuration]] - All settings
- [[Troubleshooting]] - Common issues

> Full guide: [docs/session-sharing.md](https://github.com/kshivang/BossTerm/blob/master/docs/session-sharing.md)
