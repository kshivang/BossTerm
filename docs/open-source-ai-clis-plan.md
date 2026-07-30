# Plan: Open-Source AI CLIs as First-Class Citizens

Worktree: `.worktrees/bossterm-oss-clis`, branch `oss-ai-clis`, based on `origin/master` @ `7d2e187b`.

This document is kept for the **verified-facts table** and the method behind it. The phase-by-phase
plan below is history — the work landed in PR #354 and the code is the current authority. Don't treat
the PR breakdown as a to-do list.

Still unverified against a running binary, and therefore the only genuinely open risks:

- **`openclaw`'s `mcp` and `exec-policy` subcommands.** The installed build (2026.3.13) has neither;
  npm latest is 2026.7.1-2, so the docs are ahead of it. An older build exits non-zero and the
  clipboard fallback fires with the right snippet, which is the correct degradation.
- **`hermes mcp add` end-to-end through the attach button.** Only `--help` was inspected. Its
  behavior under a closed stdin (it can prompt for tool selection) is still an assumption.

## Goal

BossTerm supports four AI CLIs today (Claude Code, Codex, Gemini CLI, OpenCode). This plan adds
**Kimi Code CLI**, **Grok Build**, **Hermes Agent**, and **Ollama**, and reorders the whole feature
so the open-source stack leads instead of trailing.

"Open source first" is made concrete as four things:

1. **Ordering** — the AI menu, the settings list, and the onboarding step list OSS CLIs first, in a
   labelled group, rather than in registration order.
2. **Provenance is visible** — every entry carries its license + source repo and shows a badge, so
   the openness claim is verifiable in the UI, not just implied.
3. **Local models are a supported path** — Ollama becomes a first-class entry (local model runtime),
   and the CLIs that can run against it are marked as such.
4. **Onboarding defaults favour OSS** — the wizard pre-selects the open-source set.

## Verified CLI facts

Everything below was verified against installed binaries, not taken from docs or memory.

The methods, because they're reusable and each one caught something a plausible guess got wrong:

- **Sandboxed HOME for config-writing subcommands.** `GROK_HOME=/tmp/probe grok mcp add …` exercises
  the real code path without touching the user's config; verify the real file afterwards. Same for
  `KIMI_CODE_HOME`, `HERMES_HOME`, `OPENCLAW_CONFIG_PATH`. (`CliConfigPaths` honors these in
  production but ignores them when a test passes an explicit home, so a developer with one exported
  doesn't get their config rewritten by a test run.)
- **Flag validity by probing the TUI**, not by reading docs: run the binary with the candidate flag
  and `</dev/null` under `perl -e 'alarm 10; exec @ARGV'` (macOS has no `timeout`). An immediate
  usage banner means rejected; hanging with no output means accepted. Always include a known-good
  flag as a positive control, or "banner" might just mean "not a TTY".
- **Ask the CLI to resolve its own config** where it can: `opencode debug config` is what caught the
  `OPENCODE_PERMISSION` character-spread bug.
- **Read the binary's own schema** when there's no such command. Kimi Code is a bundled single file,
  so `strings` yields its zod definitions verbatim — that's how the `mcp.json` shape below is
  confirmed rather than assumed. Note `kimi doctor` validates only `config.toml` and `tui.toml`, so
  its "all config files are valid" says nothing about `mcp.json`; trusting it would have been a
  false positive.
- **Confirm the endpoint AND the publisher.** Every install URL was fetched (200 + real content) and
  every npm name resolved with `npm view`: `@moonshot-ai/kimi-code` → 0.30.0 from
  `MoonshotAI/kimi-code`, matching the installed binary; `openclaw` → `openclaw/openclaw`.

| CLI | id | binary | License / repo | Install | Auto/YOLO flag |
|---|---|---|---|---|---|
| Kimi Code CLI | `kimi-code` | `kimi` | MIT — `MoonshotAI/kimi-code` | `curl -fsSL https://code.kimi.com/kimi-code/install.sh \| bash` → `~/.kimi-code/bin/kimi` (single binary, no Node) · npm `@moonshot-ai/kimi-code` | `-y` / `--yolo` — **verified v0.30.0** (`--auto` = fully autonomous) |
| Grok Build | `grok-build` | `grok` | Apache-2.0 — `xai-org/grok-build` (Rust, 23k★) | `curl -fsSL https://x.ai/cli/install.sh \| bash` · npm `@xai-official/grok` | `--always-approve` |
| Hermes Agent | `hermes` | `hermes` | MIT — `NousResearch/hermes-agent` (Python) | `curl -fsSL https://hermes-agent.nousresearch.com/install.sh \| bash` | `--yolo` |
| Ollama | `ollama` | `ollama` | MIT — `ollama/ollama` (Go) | `curl -fsSL https://ollama.com/install.sh \| sh` · `brew install ollama` | n/a — not an agent |

MCP integration, per CLI:

| CLI | Non-interactive `mcp add` | Config file the scanner must read |
|---|---|---|
| Grok Build | ✅ `grok mcp add {NAME} --url {URL} --type sse` / `grok mcp remove {NAME}` (also `list`, `doctor`) — **verified v0.2.3** | `~/.grok/config.toml` → `[mcp_servers.<name>]` with `url` / `type` / `enabled`. **Byte-identical in shape to Codex's TOML** — the existing minimal TOML scan is reusable as-is with a different path (now `McpRegistrationScanner.tomlMcpServersLoopbackUrl`). |
| Hermes Agent | ✅ `hermes mcp add {NAME} --url {URL}` / `hermes mcp remove {NAME}` (also `list`, `test`, `configure`) — **verified v0.13.0** | `~/.hermes/config.yaml` → `mcp_servers:` → `<name>:` → `url:` (YAML) |
| Kimi Code CLI | ❌ **no `mcp` subcommand at all** (verified v0.30.0) — MCP is configured conversationally via `/mcp-config`, so BossTerm merges the JSON in-process | `~/.kimi-code/mcp.json` → `mcpServers.<name>` = `{ "transport": "sse", "url": … }`. Confirmed from the binary's own zod schema: `McpServerSseConfigSchema = object({ transport: literal("sse"), url: string().url(), … })`, and the preprocessor defaults a bare `url` to `"http"` — so `transport` must be written explicitly. |
| OpenCode | ❌ none (TUI prompt) — also merged in-process, since `curl \| bash` is now its default install and `node` can no longer be assumed | `~/.config/opencode/opencode.json` → `mcp.<name>` = `{ "type": "remote", "url": …, "enabled": true }` |
| Ollama | n/a — Ollama is an MCP *nothing*; it serves models, it doesn't consume tools | n/a |

### Openness ranking (drives the ordering)

License alone doesn't separate these — Codex (Apache-2.0) and Gemini CLI (Apache-2.0) are also open
source; only Claude Code is proprietary. The axis that actually matters is **whether you can run the
whole stack on open weights without a proprietary vendor account**. So the registry carries two
fields and sorts on both:

| Tier | CLIs | Rationale |
|---|---|---|
| 1 — OSS CLI + open/local models | Hermes (MIT), Kimi Code (MIT), OpenClaw (MIT), OpenCode (MIT), Ollama (MIT) | OSS client, provider-agnostic, runs on local/open weights |
| 2 — OSS CLI, vendor models | Codex (Apache-2.0), Gemini CLI (Apache-2.0), Grok Build (Apache-2.0) | source is open, the model requires a vendor account |
| 3 — proprietary | Claude Code | closed client and model |

Within a tier the order is alphabetical by display name, which is what
`AI_ASSISTANTS_OSS_FIRST` and `McpAttachTarget.ossFirst` produce.

This is honest about Grok Build: the user asked for it alongside the OSS CLIs, and its client *is*
Apache-2.0, but it is not an open-weights path — tier 2, not tier 1.

## Where a CLI has to be registered today

Adding one CLI currently means touching seven places, five of which are hardcoded `when`/list
duplicates of the registry. This is the reason the plan front-loads a refactor rather than pasting
four more entries into each site.

| # | Site | Location |
|---|---|---|
| 1 | `AIAssistantIds` constants + `AIAssistants.BUILTIN` | `ai/AIAssistantDefinition.kt:88`, `:170` |
| 2 | `AIAssistantDetector.getAssistantSpecificPaths()` — `when (assistantId)` | `ai/AIAssistantDetector.kt:139` |
| 3 | `McpAttachTarget` enum (add/remove/clipboard/registrationUrl) | `mcp/McpCliAttacher.kt:65` |
| 4 | `McpRegistrationScanner.registeredLoopbackUrl()` — `when (target)` | `mcp/McpRegistrationScanner.kt:72` |
| 5 | Onboarding: `InstalledTools` booleans, `detectInstalledTools()`, and **six** `when (id)` blocks | `onboarding/OnboardingWizard.kt:147`, `:627`, `:668`, `:1043`, `:1050`; `onboarding/OnboardingSteps.kt:905`, `:1020`, `:1083` |
| 6 | Menu: one `onLaunchX` callback per CLI + its ~20-line wiring block + app menu item | `menu/MenuActions.kt:44-47`, `TabbedTerminal.kt:504-586`, `bossterm-app/.../Main.kt:559-573` |
| 7 | Remote/share mirrors of the CLI list | `tabs/TabBar.kt:259`, `TabbedTerminal.kt:1855`, `share-viewer/viewer.js:125-130` + `:687-692` |

Pre-flight check already done: **no embedder outside `bossterm-app` references `MenuActions.onLaunchClaudeCode`
and friends** (grepped `BossConsole` and `boss_plugins/terminal-tab`), so #6 can be collapsed without a
compatibility shim — consistent with AGENTS.md's "no backwards-compatibility hacks".

## Phased PRs

### PR 1 — Make the registry the single source of truth (no new CLIs)

- Extend `AIAssistantDefinition` with `license: String`, `sourceUrl: String`, `openSource: Boolean`,
  `localModels: Boolean`, and `extraDetectPaths: List<String>` (with `~` expansion).
- Move the `when (assistantId)` path table from `AIAssistantDetector.kt:139` into those
  `extraDetectPaths` and delete the `when`.
- Add `AIAssistants.AI_ASSISTANTS_OSS_FIRST` — sorted by (tier asc, displayName asc).
- Collapse `MenuActions.onLaunchClaudeCode/Gemini/Codex/OpenCode` into
  `onLaunchAssistant: ((assistantId: String) -> Unit)?`, replace the four near-identical wiring
  blocks in `TabbedTerminal.kt` with one id-parameterised function, and build the app's `Tools ▸ AI`
  submenu by iterating `AI_ASSISTANTS_OSS_FIRST` in `Main.kt`.
- Derive `TabBar.REMOTE_AI_ASSISTANTS` and the `TabbedTerminal.kt:1855` map from the registry.

Net effect: sites #2, #6, #7 (host side) stop being per-CLI code.

### PR 2 — Generalise onboarding

- Replace `InstalledTools`' four AI booleans with `aiAssistants: Map<String, Boolean>`, populated by
  iterating the registry in `detectInstalledTools()`.
- Delete the six `when (id)` blocks; take npm package / install script from the definition, so a new
  registry entry appears in the wizard with no wizard edit at all.
- Pre-select tier-1 OSS entries by default.

### PR 3 — Register the four CLIs (launch / detect / install / onboarding)

Registry entries per the verified table, plus detection paths:

- `kimi` → `~/.kimi-code/bin/kimi`, `~/.local/bin/kimi`
- `grok` → `~/.grok/bin/grok` (**confirmed** — that is where the local install lives), `~/.local/bin/grok`
- `hermes` → `~/.local/bin/hermes` (**confirmed**), `~/.hermes/bin/hermes`
- `ollama` → `/usr/local/bin/ollama` (**confirmed**), `/opt/homebrew/bin/ollama`, `/Applications/Ollama.app/Contents/Resources/ollama`

Also in this PR, a detection-cost fix that adding five tools makes urgent: `AIAssistantDetector`
strategy 5 spawns one `$SHELL -l -c which <cmd>` **per tool**, and `detectAll()` fans out across
`Dispatchers.IO`. Going from 20 to 25 tools multiplies login-shell spawns on a dispatcher this repo
has already hit the 64-thread cap on (see the IO-dispatcher-exhaustion history). Batch the fallback
into a single `command -v a b c …` invocation and parse the result.

### PR 4 — MCP attach for the new CLIs

New `McpAttachTarget` entries with frozen persistence keys `GROK`, `HERMES`, `KIMI_CODE`:

- **GROK** — `grok mcp add {NAME} --url {URL} --type sse`, remove `grok mcp remove {NAME}`; SSE root
  URL (default `registrationUrl`). Scanner: reuse the Codex TOML scan against `~/.grok/config.toml`,
  renamed `tomlMcpServersLoopbackUrl` now that it serves two CLIs.
- **HERMES** — `hermes mcp add {NAME} --url {URL}` / `hermes mcp remove {NAME}`. Scanner: minimal
  indent-aware YAML scan of `~/.hermes/config.yaml` (`mcp_servers:` → `<name>:` → `url:`), same
  spirit as the existing hand-rolled TOML scan — no new YAML dependency.
- **KIMI_CODE** — no scriptable `mcp add`. **Do not copy the OpenCode `node -e` trick**: OpenCode can
  assume Node because it ships as an npm package, but Kimi Code is a single binary explicitly
  advertised as Node-free. Instead add an in-process JSON merge path to `McpCliAttacher` — a
  `ConfigFileEditor` that reads/merges/writes `~/.kimi-code/mcp.json` from the JVM using the
  `kotlinx.serialization` Json already imported by the scanner. Scanner: `jsonLoopbackUrl(file,
  "mcpServers", name)` works unchanged.

Two contract details this PR must handle:

- `McpCliAttacher.PROCESS_TIMEOUT_SECONDS` is a flat 15s. `hermes mcp add` is Python and performs
  *automatic tool discovery* against the URL, so it is the first target with a plausible >15s
  cold-start. Make the timeout per-target and give Hermes a longer one.
- `hermes mcp add` can prompt (docs mention a `select` prompt for tool filtering). `runProcess`
  closes stdin, which should make it take the default or exit non-zero into the clipboard fallback —
  but this needs a live run to confirm before release, not an assumption.

The in-process editor also lets OpenCode drop its Node dependency in a later cleanup; not in scope
here.

### PR 5 — Ollama as a first-class local-model runtime

Ollama is not a coding agent, and modelling it as one would put a broken "Attach MCP" button next to
it. Give it its own shape:

- New `ToolCategory.LOCAL_MODEL_RUNTIME`, so it is excluded from `AI_ASSISTANTS` (and therefore from
  `McpAttachTarget` coverage) but still detected, installed, and launchable.
- Menu: `Ollama ▸` submenu listing installed models from `ollama list`, each running
  `ollama run <model>`; plus `Start server` (`ollama serve`) and `Pull a model…`.
- The payoff for "OSS first": a **Use local models** action that exports the OpenAI-compatible
  endpoint (`OPENAI_BASE_URL=http://127.0.0.1:11434/v1`) into the pane before launching a tier-1
  agent, so OpenCode/Kimi/Hermes can run fully offline. Ship this behind the existing per-assistant
  custom-command setting rather than new bespoke config.

### PR 6 — Surface openness in the UI

- AI menu, settings section, and onboarding render `AI_ASSISTANTS_OSS_FIRST` with an "Open Source"
  group header and per-entry license badge (`MIT`, `Apache-2.0`) + a `Local models` chip for tier 1.
- Fix `AIAssistantSettingsSection.kt:128`: it iterates `AIAssistants.BUILTIN`, so the "AI Assistants"
  settings section currently renders **all 20 tools** — git, brew, docker, tmux, fzf included. It
  should iterate `AI_ASSISTANTS`. Worth doing in this PR because five more entries make the wrong
  list markedly worse. (Behaviour change: per-tool custom-command editing for non-AI tools disappears
  from that section — call it out in the release notes.)
- Update the share-viewer mirrors (`viewer.js` `MCP_TARGETS` at `:125` and `AI_ASSISTANTS` at `:687`)
  to the new set and ordering.

### PR 7 — Docs

`AGENTS.md` ("AI Menu" line), `docs/mcp-server.md` (the tested-CLI matrix in the `McpAttachTarget`
kdoc is mirrored there), `docs/onboarding.md`, `docs/wiki/Features.md`, and a release-notes entry.

## Tests

- `McpRegistrationScannerTest` — fixtures for Grok TOML, Kimi `mcp.json`, Hermes YAML, including the
  conservative-match cases the existing suite already covers (remote host ⇒ ABSENT, malformed ⇒
  UNKNOWN).
- `McpRegistrationUrlTest` — Grok/Hermes get the plain SSE root; only Claude Code keeps the
  `${VAR:-port}` form and only Codex the `/mcp` suffix.
- `AIAssistantLaunchCommandTest` — `--yolo` / `--always-approve` land in the launch command, and
  disabling auto-mode drops them.
- **New** `AttachTargetCoverageTest` — every `AI_ASSISTANT`-category id that declares MCP support has
  a matching `McpAttachTarget` and vice versa; every `persistenceKey` is unique. This is the guard
  against the drift the seven-site duplication has been causing.
- `OnboardingInstallScriptTest` — new entries produce runnable install commands on all three platforms.

## Risks and open questions

1. **`kimi` binary collision.** Two live Moonshot projects both install a `kimi` binary:
   `MoonshotAI/kimi-code` (MIT, TypeScript/single-binary, `~/.kimi-code`, `/mcp-config` only) and
   `MoonshotAI/kimi-cli` (Apache-2.0, Python, 11k★, and it *does* have
   `kimi mcp add --transport http <name> <url>`). This plan targets **kimi-code**, the one the user's
   "kimicode" names and the more actively pushed of the two. Detection must therefore not stop at
   "is `kimi` on PATH": branch on `~/.kimi-code` existence, and if only the Python CLI is present,
   prefer its native `mcp add`. Needs a live install of both to settle — flagged, not guessed.
2. **Kimi rows are docs-derived.** Unlike grok/hermes/ollama, `kimi` isn't installed here, so its
   flag names and `mcp.json` schema are unverified against a real binary. Install it and re-check
   before PR 3 lands.
3. **Grok `--type` is not validated at add time** — `--type bogus` was accepted and written to config
   in the sandboxed probe. `sse` is what we write; failures would surface only at connect time, so
   the docs should point at `grok mcp doctor`.
4. **Hermes weight.** Python + tool discovery on add; see the per-target timeout note in PR 4.
5. **`MenuActions` is public library API.** `bossterm-compose` is published to Maven Central, so
   collapsing the four callbacks is a breaking change for any external embedder even though nothing
   in this workspace uses them. Minor-version bump + release note.
6. **Downstream mirrors exist outside this repo** and will drift: `boss_plugins/tool-creator` and
   `boss_plugins/tool-evolver` each hardcode a `CliAgent` enum with exactly the old four
   (`CLAUDE_CODE`, `CODEX`, `GEMINI`, `OPENCODE`). Out of scope here; follow-up issues in those repos
   once the BossTerm set is settled.

## Out of scope

BossConsole and the plugin-side `CliAgent` enums, and any change to how the BossTerm MCP server
itself exposes tools. This plan is BossTerm-only.
