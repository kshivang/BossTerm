# BOSS daemon terminal adapter

`HostedTerminalPool` runs terminal PTYs inside an embedder-owned background process.
It creates an isolated `SessionHost` and authenticated loopback attach server per logical
terminal surface, with one sharing server spanning all surfaces. The embedder owns process
startup, login registration, branding and plugin loading. This adapter starts no standalone
BossTerm process, tray, or MCP server.

Desktop mirrors use `DaemonBridgeCoordinator.registerHosted` and `TabbedTerminal(daemonMode = true)`.
The controller installs a daemon tab factory before creating shells, including programmatic
MCP/runner tabs. Open/split operations carry stable IDs and acknowledgments, so reconnect
replay cannot execute a command twice. Input and resize use the attach transport; the daemon
retains parsing, buffers, PTYs and split trees. Hosted MCP status stays owned by the embedder.

Dropping a UI mirror preserves its PTYs. Explicitly closing tabs/panes or calling
`HostedTerminalPool.closeSurface` stops the corresponding sessions. Plugin worker shutdown
must close the pool and then drain `TerminalRuntimeLifecycle.shutdownForUnload()` before
closing the worker classloader. Share All Windows spans the entire pool; group/session
shares remain restricted to their selected IDs. Sharing controls aggregate/deduplicate
states from live attach clients.

Attach protocol 5 adds stable open/split IDs, spawn options and open acknowledgments.
Negotiate the protocol before attaching; do not automatically replace a running worker
with incompatible code and discard its sessions.
