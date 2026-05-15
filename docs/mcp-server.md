# BossTerm MCP Server

BossTerm ships an in-process [Model Context Protocol](https://modelcontextprotocol.io)
server that exposes the running terminal to MCP-aware clients (Claude Code,
Codex, Gemini CLI, OpenCode, etc.). Clients can enumerate tabs, read
scrollback, search output, capture the last completed command, and — when
write tools are enabled — drive shells, send signals, and open new panes.

This guide covers user-facing enablement and the embedder API. For a hands-on
example, see [`embedded-example/`](../embedded-example/) and
[`tabbed-example/`](../tabbed-example/).

## Overview

- **Transport**: SSE (Server-Sent Events) over HTTP, served by an embedded
  Ktor CIO engine.
- **Endpoint**: `http://127.0.0.1:<port>/`. Default port `7676`; configurable
  via Settings → BossTerm MCP → Port or `mcpPort` in `settings.json`.
- **Binding**: loopback only. Any process running as your user can reach the
  endpoint while it is enabled. Requests with a non-loopback `Host` header
  are rejected with `403 Forbidden` (DNS-rebinding defense).
- **Opt-in**: disabled by default. The server only starts when `mcpEnabled`
  is `true`. Embedders can set `defaultEnabled = true` to flip the default
  on first launch — see [BossTermMcpConfig reference](#bosstermmcpconfig-reference).

## Enabling the server (user)

1. Open **Settings** (gear icon in the top-right of the tab bar) →
   **BossTerm MCP**.
2. Toggle **Enable BossTerm MCP Server**. The small green
   **BossTerm MCP on** pill appears in the tab bar; click it for a popover
   that shows the endpoint URL, attached AI CLIs, and a one-click toggle.
3. (Optional) Adjust **Port** if `7676` clashes with another local service.
   Toggling the port while enabled performs a stop-then-start.
4. (Optional) In **Exposed Tools**, untick any built-in tool you don't want
   MCP clients to call. The change applies immediately — no server restart.
5. (Optional) Under **Attach to AI CLI**, click the button for each AI CLI
   you want to register this endpoint with. See
   [Attaching to AI CLIs](#attaching-to-ai-clis).

## Endpoint and security

The server is constructed in
[`BossTermMcpManager`](../compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/mcp/BossTermMcpManager.kt)
and binds via Ktor:

- Host: `127.0.0.1` (never `0.0.0.0`).
- Port: `settings.mcpPort` (default `7676`).
- Path: `/` (root). The SDK 0.8.3 quirk re-mounts SSE/POST at the application
  root regardless of any wrapping path, so the endpoint URL is the
  authoritative pointer.
- A request interceptor rejects non-loopback `Host` headers (only `127.0.0.1`,
  `localhost`, and their `:<port>` forms are allowed) so a victim browser
  resolving `attacker.example` to `127.0.0.1` can't reach the server.

The full advertised URL is logged at startup:

```
INFO  BossTermMcpManager - BossTerm MCP server ready: http://127.0.0.1:7676/ (SSE transport, N state(s) registered)
```

## Built-in tools

Tool names are unprefixed below. If the embedder sets
`BossTermMcpConfig.toolNamePrefix`, every name is prefixed with that string
(e.g. `bossconsole_list_tabs`).

Read tools are always registered (subject to the per-tool allow-list in
Settings → Exposed Tools). Write tools are registered only when the
embedder's `BossTermMcpConfig.allowWriteTools = true`.

### `list_tabs`

List all open terminal tabs across every window registered with the
`McpTerminalRegistry`.

- Arguments: none.
- Returns:
  ```json
  {
    "tabs": [
      {
        "id": "<uuid>",
        "title": "<string>",
        "cwd": "<string|null>",
        "pid": 12345,
        "isActive": true
      }
    ],
    "activeTabId": "<uuid|null>"
  }
  ```
- `activeTabId` is the active tab of the **primary** (first-registered)
  window. `isActive` on each `TabInfo` is per-window.

### `get_active_tab`

Return the active tab of the primary window, or the literal JSON `null` if no
tab is active.

- Arguments: none.
- Returns: a `TabInfo` object (same shape as in `list_tabs`) or `null`.

### `read_scrollback`

Read the last N lines from a tab or split pane's buffer (history + visible
screen). Trailing whitespace per line is stripped.

- Required: `tab_id` (string).
- Optional:
  - `lines` (integer, minimum `1`, default `200`).
  - `pane_id` (string) — to target a specific split pane (the value returned
    by `run_in_panel`). Omit to read the focused pane.
- Returns:
  ```json
  { "lines": ["..."], "totalAvailable": 1234 }
  ```

### `search_output`

Regex-search the entire scrollback (history + screen) of a tab or pane.

- Required: `tab_id` (string), `pattern` (string, Kotlin/Java regex syntax).
- Optional:
  - `max_matches` (integer, minimum `1`, default `50`) — truncates at this
    many matches; `truncated` in the response indicates it was hit.
  - `ignore_case` (boolean, default `false`).
  - `pane_id` (string).
- Returns:
  ```json
  {
    "matches": [
      { "row": -42, "line": "...", "matchStart": 0, "matchEnd": 5 }
    ],
    "truncated": false,
    "historyLinesCount": 1000,
    "height": 24
  }
  ```
- Row numbers follow the buffer convention: negative for history (oldest =
  `-historyLinesCount`), `0..height-1` for the visible screen.

### `get_last_command`

Return the most recently completed shell command for a tab (as captured via
OSC 133). Requires shell integration — see
[Shell Integration](../README.md#shell-integration).

- Required: `tab_id` (string).
- Returns either `null` (no command completed yet) or:
  ```json
  {
    "commandText": null,
    "exitCode": 0,
    "startedAtMs": 1700000000000,
    "finishedAtMs": 1700000000123,
    "durationMs": 123,
    "cwd": "/home/me"
  }
  ```
- `commandText` is currently always `null`; capturing the typed command text
  reliably is a follow-up.

### `read_debug_console`

Read recent entries from a tab's debug-data buffer (PTY output, user input,
emulator-generated, and console-log entries). Available only when debug data
collection is enabled for the tab. Supports incremental polling via
`since_index`.

- Required: `tab_id` (string).
- Optional:
  - `max_chunks` (integer, `1..settings.debugMaxChunks`, default `100`).
  - `since_index` (integer, ≥ 0) — return only chunks with `index >
    since_index`. Use the previous response's `stats.newestIndex` for polling.
  - `sources` (array of strings, case-insensitive) — filter to a subset of:
    `PTY_OUTPUT`, `USER_INPUT`, `EMULATOR_GENERATED`, `CONSOLE_LOG`. Omit the
    key entirely to get every source; an empty array (or one containing only
    unknown names) returns no chunks.
- Returns:
  ```json
  {
    "chunks": [
      { "index": 42, "timestamp": 1700000000000, "source": "PTY_OUTPUT", "data": "..." }
    ],
    "stats": {
      "totalChunks": 9999,
      "chunksStored": 1000,
      "oldestIndex": 9000,
      "newestIndex": 9999,
      "debugEnabled": true
    }
  }
  ```

### `send_input` (write tool)

Write text to a tab's shell stdin. Append `\n` to the text yourself if you
want the shell to execute it.

- Required: `tab_id` (string), `text` (string).
- Optional: `pane_id` (string).
- Returns: `{ "ok": true }`.

### `send_signal` (write tool)

Send a control signal to a tab's shell.

- Required: `tab_id` (string), `signal` (`"ctrl_c"`, `"ctrl_d"`, or
  `"ctrl_z"`).
- Optional: `pane_id` (string).
- Returns: `{ "ok": true }`.

### `run_in_panel` (write tool)

Open a new terminal panel and write a script to it. All three modes wait for
the shell's OSC 133;A prompt-ready signal (or the configured fallback delay)
before sending the script, so the command runs cleanly rather than racing
with shell startup.

- Required:
  - `panel`: `"new_tab"`, `"horizontal_split"`, or `"vertical_split"`.
  - `script`: text to write to the new panel's shell. Include `\n` to submit
    as a command.
- Optional:
  - `tab_id` (string) — source tab id. Required for splits; defaults to the
    primary window's active tab.
  - `working_dir` (string) — for splits, defaults to the inherited cwd via
    OSC 7.
  - `split_ratio` (number, `0.05..0.95`) — fraction of the parent's dimension
    the **new** pane gets. Defaults to the user's `mcpDefaultSplitRatio`
    (typically `0.3`).
- Returns:
  ```json
  { "ok": true, "tabId": "<uuid>", "paneId": "<uuid|null>" }
  ```
  `paneId` is `null` for `new_tab`; for splits it's the new pane's session
  id, which you can pass back as `pane_id` to other tools.

## `manage_tools` meta-tool

Always exposed. Use it to introspect or change which built-in tools are
available to MCP clients at runtime. The setting is persisted under
`disabledMcpTools` in `~/.bossterm/settings.json`; toggles also apply
immediately to the live server.

### `operation: "list"`

Returns the current enable state of every available built-in (write tools are
omitted when `allowWriteTools = false`).

```json
{
  "tools": [
    { "name": "list_tabs",           "enabled": true },
    { "name": "get_active_tab",      "enabled": true },
    { "name": "read_scrollback",     "enabled": true },
    { "name": "search_output",       "enabled": true },
    { "name": "get_last_command",    "enabled": true },
    { "name": "read_debug_console",  "enabled": true },
    { "name": "send_input",          "enabled": false },
    { "name": "send_signal",         "enabled": true },
    { "name": "run_in_panel",        "enabled": true }
  ]
}
```

### `operation: "enable"` / `"disable"`

Both take a non-empty `names` array of **unprefixed** built-in tool names:

```json
{ "operation": "disable", "names": ["send_input", "run_in_panel"] }
```

Response:

```json
{ "ok": true }
```

Unknown names error out before any change is written. `manage_tools` itself
is reserved and cannot be disabled — that would brick the surface.

## Attaching to AI CLIs

The **Attach to AI CLI** buttons (Settings → BossTerm MCP → Attach to AI CLI)
register the running endpoint with a third-party CLI in one click. The
operations are idempotent: each button first runs the CLI's `mcp remove`
subcommand (errors ignored), then `mcp add`, so re-clicking after a port
change just refreshes the entry. The CLIs that have ever succeeded are
persisted in `mcpAttachedTo` and re-attached silently on startup so the
endpoint URL stays current across restarts.

Commands run under the hood (from
[`McpCliAttacher`](../compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/mcp/McpCliAttacher.kt)):

| CLI         | Command                                                                   |
|-------------|---------------------------------------------------------------------------|
| Claude Code | `claude mcp add --scope user --transport sse <name> <url>`                |
| Codex       | `codex mcp add <name> --url <url>`                                        |
| Gemini CLI  | `gemini mcp add <name> <url> --transport sse --scope user`                |
| OpenCode    | Scripted edit of `~/.config/opencode/opencode.json` via a `node -e` shim. |

`<name>` is the embedder's `BossTermMcpConfig.serverName` (default
`"bossterm"`) and `<url>` is `http://127.0.0.1:<port>/`.

If the CLI binary is missing, the shell-out fails, or the operation times
out (15 s), the corresponding config snippet is dropped onto the clipboard
instead, and an amber "exit N — config copied to clipboard" status is shown
under the button. **Codex caveat**: registration succeeds with codex-cli 0.130,
but Codex currently speaks streamable HTTP only, so the runtime connection
will fail against the SSE endpoint until BossTerm's MCP SDK is upgraded.

## Settings reference

All MCP-related fields live in
[`TerminalSettings`](../compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/settings/TerminalSettings.kt)
and are persisted to `~/.bossterm/settings.json`.

| Key                       | Type                | Default     | Meaning                                                                                                |
|---------------------------|---------------------|-------------|--------------------------------------------------------------------------------------------------------|
| `mcpEnabled`              | `Boolean`           | `false`     | Bind the MCP server. Toggles the engine on/off live.                                                   |
| `mcpPort`                 | `Int`               | `7676`      | Localhost TCP port. Changing while enabled performs stop-then-start.                                   |
| `mcpShowStatusIndicator`  | `Boolean`           | `true`      | Show the green "BossTerm MCP on" pill in the tab bar.                                                  |
| `mcpDefaultSplitRatio`    | `Float`             | `0.3`       | Default new-pane size for `run_in_panel` splits when `split_ratio` is omitted. Range `0.05..0.95`.     |
| `mcpAttachedTo`           | `Set<String>`       | `{}`        | Stable `persistenceKey`s (e.g. `"CLAUDE_CODE"`) of attached AI CLIs. Used for silent re-attach.        |
| `disabledMcpTools`        | `Set<String>`       | `{}`        | Unprefixed built-in tool names hidden from clients. Edited via the UI or `manage_tools`.               |
| `mcpMaxAnswerChars`       | `Int`               | `150_000`   | Soft ceiling on tool response size. When exceeded, the tool returns a progressively smaller summary instead of the full payload — see [Response shortening](#response-shortening). Advanced; no UI control. |
| `mcpConfigured`           | `Boolean`           | `false`     | Internal first-launch marker. Once `true`, embedder defaults no longer override the user's choice.     |

## Embedder integration

Other applications that depend on `compose-ui` can stand up the MCP server
themselves. The contract has four parts.

### 1. Create a `BossTermMcpConfig`

```kotlin
val mcpConfig = BossTermMcpConfig(
    serverName = "myapp",
    serverVersion = "1.0",
    // Optional: prefix every built-in tool name (e.g. "myapp_list_tabs")
    toolNamePrefix = "myapp_",
    // Optional: skip write tools entirely for an observe-only build
    allowWriteTools = true,
    // Optional: auto-enable on first launch
    defaultEnabled = true,
    // Optional: override descriptions of specific built-ins
    customToolDescriptions = mapOf(
        "list_tabs" to "List tabs in MyApp's integrated terminal."
    ),
    // Optional: register app-specific tools
    additionalTools = { server ->
        server.addTool(
            name = "myapp_health_check",
            description = "Report MyApp's daemon status.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList())
        ) { _ ->
            CallToolResult(
                content = listOf(TextContent(text = buildJsonObject { put("ok", true) }.toString())),
                isError = false, structuredContent = null, meta = null
            )
        }
    }
)
```

See [BossTermMcpConfig reference](#bosstermmcpconfig-reference) for every
field.

### 2. Start `BossTermMcpManager`

```kotlin
val mcpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val mcpManager = BossTermMcpManager(
    registry = McpTerminalRegistry,
    settingsManager = SettingsManager.instance,
    parentScope = mcpScope,
    config = mcpConfig
)
mcpManager.start()
Runtime.getRuntime().addShutdownHook(Thread {
    mcpManager.stop()
    mcpScope.cancel()
})
```

`start()` is idempotent. It begins watching `mcpEnabled` / `mcpPort` and
binds/unbinds the Ktor engine accordingly. `stop()` cancels the watcher and
stops Ktor asynchronously — safe to call from `onDispose` on the UI thread.

### 3. Provide `LocalBossTermMcpConfig` for the settings UI

```kotlin
application {
    CompositionLocalProvider(LocalBossTermMcpConfig provides mcpConfig) {
        // your TabbedTerminal / EmbeddableTerminal / SettingsWindow tree
    }
}
```

The Settings UI reads this composition local to:

- Show your `serverName` and `serverVersion` in the endpoint note.
- Hide the MCP section entirely when `showInSettingsUi = false`.
- Skip rendering write-tool toggles when `allowWriteTools = false`.

If the composition local is `null` (no embedder provided one), the MCP
section in Settings renders a "how to configure" banner pointing at this
guide.

### 4. Register each window's state with `McpTerminalRegistry`

`McpTerminalRegistry` is a singleton. Each window that hosts a
`TabbedTerminalState` must register that state so the MCP server can find
tabs across windows. The registry only accepts `TabbedTerminalState` — apps
built on the single-terminal `EmbeddableTerminal` component can still bind
the MCP server and register their own tools via `additionalTools`, but the
tab-scoped built-ins (`list_tabs`, `read_scrollback`, `send_input`, etc.)
won't see any tabs.

```kotlin
val tabbedState = rememberTabbedTerminalState(autoDispose = true)

DisposableEffect(tabbedState) {
    McpTerminalRegistry.register(tabbedState)
    onDispose { McpTerminalRegistry.unregister(tabbedState) }
}

TabbedTerminal(state = tabbedState, /* ... */)
```

Without this step, `list_tabs` returns an empty array and every other tool
will error with `Unknown tab_id`. The `bossterm-app/src/desktopMain/kotlin/ai/rever/bossterm/app/Main.kt`
window code is the canonical example.

### Canonical samples

- [`embedded-example/src/desktopMain/kotlin/ai/rever/bossterm/embedded/Main.kt`](../embedded-example/src/desktopMain/kotlin/ai/rever/bossterm/embedded/Main.kt)
  — single-window app, register on the embeddable state, custom tool
  `embedded_example_app_info`.
- [`tabbed-example/src/desktopMain/kotlin/ai/rever/bossterm/tabbed/Main.kt`](../tabbed-example/src/desktopMain/kotlin/ai/rever/bossterm/tabbed/Main.kt)
  — multi-window tabbed app, register per-window, custom tool
  `tabbed_example_window_overview` that iterates `McpTerminalRegistry.allStates()`.

## `BossTermMcpConfig` reference

From
[`BossTermMcpConfig`](../compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/mcp/BossTermMcpConfig.kt):

| Parameter                 | Type                       | Default            | Meaning                                                                                                                              |
|---------------------------|----------------------------|--------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `serverName`              | `String`                   | `"bossterm"`       | Reported to clients as `Implementation.name`. Also used as the `<name>` in CLI-attach commands.                                      |
| `serverVersion`           | `String`                   | `"1.0"`            | Reported as `Implementation.version`.                                                                                                |
| `toolNamePrefix`          | `String`                   | `""`               | Prefix for built-in tool names. Empty string disables prefixing. Does **not** apply to `additionalTools`.                            |
| `allowWriteTools`         | `Boolean`                  | `true`             | When `false`, `send_input`, `send_signal`, and `run_in_panel` are not registered.                                                    |
| `defaultPort`             | `Int`                      | `7676`             | First-launch port. After `mcpConfigured` flips, the user's setting wins.                                                             |
| `defaultEnabled`          | `Boolean`                  | `false`            | First-launch enabled state. After `mcpConfigured` flips, the user's setting wins.                                                    |
| `showInSettingsUi`        | `Boolean`                  | `true`             | When `false`, hides the MCP section from the in-app Settings UI. The status pill is still driven by `mcpShowStatusIndicator`.        |
| `additionalTools`         | `(Server) -> Unit`         | `{}` (no-op)       | Hook to register embedder-specific tools. Tool names here are **not** prefixed — the embedder owns the namespace.                    |
| `customToolDescriptions`  | `Map<String, String>`      | `{}`               | Override descriptions of built-in tools. Keys are unprefixed names. Unknown keys are silently ignored; unmentioned tools keep default. |

## Customizing tool descriptions

The default descriptions are generic ("List all open terminal tabs across all
windows…"). Embedders typically want clients to see app-specific phrasing:

```kotlin
BossTermMcpConfig(
    customToolDescriptions = mapOf(
        "list_tabs" to "List tabs inside MyApp's integrated terminal pane.",
        "send_input" to "Send keystrokes to the MyApp-managed shell."
    )
)
```

Keys are **always** the unprefixed name, regardless of `toolNamePrefix`. The
override is honored on every `addTool` call site in
[`BossTermMcpServer`](../compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/mcp/BossTermMcpServer.kt)
via the `describe(builtin, default)` helper. `manage_tools` itself does not
go through the helper and cannot be overridden.

## Adding custom tools

`additionalTools` runs once per `Server` instance, right after the built-ins
are registered:

```kotlin
additionalTools = { server ->
    server.addTool(
        name = "myapp_open_settings",
        description = "Open MyApp's preferences window.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {},
            required = emptyList()
        )
    ) { _ ->
        myApp.openSettings()
        CallToolResult(
            content = listOf(TextContent(text = "{\"ok\": true}")),
            isError = false, structuredContent = null, meta = null
        )
    }
}
```

The tool name is whatever the embedder picks — `toolNamePrefix` is **not**
applied. Choose a unique prefix to avoid colliding with future BossTerm
built-ins. See the two example modules for working tools that introspect the
host process via the embedder hook.

## Disabling tools at runtime

Tools can be hidden from clients in three equivalent ways. Each writes
`disabledMcpTools` in `settings.json`; changes apply live without restarting
the server.

- **Settings UI** — Settings → BossTerm MCP → Exposed Tools, uncheck the
  tool.
- **`manage_tools` MCP tool** — `{"operation": "disable", "names":
  ["send_input"]}`.
- **Direct edit** — add the unprefixed name to `disabledMcpTools` in
  `~/.bossterm/settings.json` and save. The settings watcher detects the
  change and reconfigures the live server.

A short-circuit in `BossTermMcpServer.applyDisabledSet(...)` performs the
add/remove against the SDK's live `Server` under an internal lock, so
concurrent toggles from the UI and a `manage_tools` call don't corrupt the
tool registry.

## Response shortening

Three built-in tools can return unbounded responses — `search_output`,
`read_scrollback`, and `read_debug_console` — so each runs its full payload
through a per-tool fallback ladder. If the full JSON would exceed
`settings.mcpMaxAnswerChars` (default `150_000`), the server returns a
progressively smaller well-formed JSON instead. The agent never sees a
truncated mid-response blob; it sees a smaller object with a `"shortened"`
key explaining what was dropped, and refines the next call accordingly.

Each shortened response includes a `"shortened"` string describing the
projection so clients can detect they're looking at a summary. Common
shortened shapes:

`search_output`
- **positions only** — `matches` keeps `row`, `matchStart`, `matchEnd`; the
  matched line text is dropped. Usually 60–80% smaller than full.
- **row counts** — `{rowCounts: {row: hits}, totalMatches}` instead of per-match
  records.
- **totals only** — `{totalMatches, truncated, historyLinesCount, height}`.

`read_scrollback`
- **tail** — keeps the last 20 lines and reports the requested count.
- **totals only** — `{totalAvailable}` with a "retry with smaller `lines`"
  hint.

`read_debug_console`
- **metadata only** — chunks keep `index`, `timestamp`, `source`; the `data`
  byte payload is dropped. Great for polling "any new chunks since N".
- **stats only** — drops the chunks list entirely; agent narrows by
  `since_index`, `sources`, or `max_chunks` on the next call.

Reduce `mcpMaxAnswerChars` (e.g. to `50_000`) if you want the fallbacks to
trigger more aggressively in agent loops. Raise it (or set to `0` to
disable) if you have a custom client that handles large payloads natively.

## Troubleshooting

**The server won't bind on the chosen port.** Another process is already on
that port. The startup logs surface `BossTerm MCP server failed to bind
127.0.0.1:7676 (port in use?)`. Pick a different port in Settings; the
manager rebinds automatically.

**The status pill says "BossTerm MCP on" but clients see no tabs.** The
window's `TabbedTerminalState` / `EmbeddableTerminalState` isn't registered
with `McpTerminalRegistry`. See
[Register each window's state](#4-register-each-windows-state-with-mcpterminalregistry).

**`manage_tools` rejects a name.** Names are case-sensitive and unprefixed.
The error message lists every available name; copy from there. If you set
`toolNamePrefix`, **do not** include the prefix in the `names` array.

**The "Attach to AI CLI" button drops a clipboard config instead.** The CLI
binary isn't on the running BossTerm process's `PATH`, the CLI doesn't have a
non-interactive `mcp add`, or the shell-out timed out. Paste the snippet into
the CLI's config and the next BossTerm startup will see it via
`mcpAttachedTo`.

**Codex registered but won't connect.** Known. codex-cli 0.130 speaks
streamable HTTP only and BossTerm's MCP SDK serves SSE. Tracked as a
follow-up — the registration is recorded in `mcpAttachedTo` so the entry
auto-refreshes once the SDK is upgraded.

**Port stuck after Force-Quit.** The 1.5 s Ktor shutdown grace is normally
enough, but a `kill -9` leaves the port in `TIME_WAIT`. Wait ~30 s or change
the port temporarily.
