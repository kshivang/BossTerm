#!/usr/bin/env python3
"""
Tiny MCP client used by the `bossterm` CLI. Speaks the SSE-based transport
used by the Kotlin MCP SDK 0.8.3 that BossTerm currently ships with.

Stdlib only — must run on whatever Python 3 ships with macOS / a typical
Linux. Don't add dependencies here.

Subcommands:
  bossterm-mcp.py call <port> <tool_name> <args_json>
      One-shot tool call. Prints the first TextContent.text from the result
      to stdout. Exit non-zero on error (and prints the error to stderr).

  bossterm-mcp.py ping <port>
      Returns 0 if a BossTerm MCP server appears to be reachable on that
      port (initialize handshake completes), 1 otherwise.

Transport summary (SDK 0.8.3 quirk):
  - GET /sse with Accept: text/event-stream → server emits an `endpoint`
    SSE event whose data line is the session-scoped POST URL.
  - POST that URL with JSON-RPC `initialize` → response arrives on the SSE
    stream as a `message` event.
  - POST `notifications/initialized` (no response expected).
  - POST `tools/call` with `{name, arguments}` → response on SSE stream.

DNS-rebinding defense in the server only accepts loopback Host headers, so
every request sets `Host: 127.0.0.1` explicitly.
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
import urllib.parse
import urllib.request
from queue import Empty, Queue


MCP_PROTOCOL_VERSION = "2024-11-05"
CLIENT_NAME = "bossterm-cli"
CLIENT_VERSION = "1.0"

# Per-call connect/read budget. Keep tight — the CLI is human-facing.
SSE_OPEN_TIMEOUT_SEC = 3.0
RPC_RESPONSE_TIMEOUT_SEC = 10.0


# ---------------------------------------------------------------------------
# SSE reader
# ---------------------------------------------------------------------------


class SseReader(threading.Thread):
    """Drains an SSE stream into a queue of (event, data) pairs."""

    def __init__(self, url: str):
        super().__init__(daemon=True)
        self.url = url
        self.queue: Queue = Queue()
        self._stop = threading.Event()
        self._resp = None
        self.error: str | None = None

    def run(self) -> None:
        req = urllib.request.Request(
            self.url,
            headers={
                "Host": "127.0.0.1",
                "Accept": "text/event-stream",
                "Cache-Control": "no-cache",
            },
        )
        try:
            self._resp = urllib.request.urlopen(req, timeout=SSE_OPEN_TIMEOUT_SEC)
        except Exception as e:  # urlopen, timeout, connection refused
            self.error = f"failed to open SSE stream: {e}"
            self.queue.put(("__error__", self.error))
            return

        event = "message"
        data_lines: list[str] = []
        try:
            for raw in self._resp:
                if self._stop.is_set():
                    break
                line = raw.decode("utf-8", errors="replace").rstrip("\n").rstrip("\r")
                if line == "":
                    if data_lines:
                        self.queue.put((event, "\n".join(data_lines)))
                    event = "message"
                    data_lines = []
                    continue
                if line.startswith(":"):
                    continue  # SSE comment
                if line.startswith("event:"):
                    event = line[len("event:"):].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[len("data:"):].lstrip())
                # Ignore id:/retry: for our purposes.
        except Exception as e:
            self.error = f"SSE read failed: {e}"
            self.queue.put(("__error__", self.error))

    def close(self) -> None:
        # Only flip the stop flag. Closing the urllib HTTPResponse from a
        # different thread isn't documented as safe — on CPython it tends
        # to work because of the GIL, but the standard library makes no
        # promise. The reader thread is daemonic and will be torn down at
        # process exit; for one-shot CLI invocations the cleanup latency
        # is bounded by the bash script's own exit.
        self._stop.set()


# ---------------------------------------------------------------------------
# JSON-RPC over POST
# ---------------------------------------------------------------------------


def post_json(url: str, body: dict) -> None:
    """Fire-and-(mostly)-forget POST. The real response comes back on SSE."""
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={
            "Host": "127.0.0.1",
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        },
    )
    # The SDK returns an immediate 202 Accepted (response goes via SSE).
    # Surface non-2xx as an exception so callers can fail loudly.
    with urllib.request.urlopen(req, timeout=RPC_RESPONSE_TIMEOUT_SEC) as resp:
        if resp.status >= 400:
            raise RuntimeError(f"POST {url} returned HTTP {resp.status}")


def wait_for_response(
    reader: SseReader, request_id: int, deadline_sec: float
) -> dict:
    """Block until a JSON-RPC message with the given id arrives on SSE."""
    import time
    end = time.monotonic() + deadline_sec
    while True:
        remaining = end - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f"timed out waiting for response id={request_id}")
        try:
            event, data = reader.queue.get(timeout=remaining)
        except Empty:
            raise TimeoutError(f"timed out waiting for response id={request_id}")
        if event == "__error__":
            raise RuntimeError(data)
        if event == "endpoint":
            # Late endpoint event after initial — ignore.
            continue
        try:
            msg = json.loads(data)
        except Exception:
            continue
        if msg.get("id") == request_id:
            if "error" in msg:
                err = msg["error"]
                raise RuntimeError(
                    f"JSON-RPC error {err.get('code', '?')}: {err.get('message', '')}"
                )
            return msg.get("result", {})
        # Other notifications / unrelated responses — keep waiting.


# ---------------------------------------------------------------------------
# Session helpers
# ---------------------------------------------------------------------------


def open_session(port: int) -> tuple[SseReader, str]:
    """Open the SSE stream and grab the session-scoped POST endpoint URL."""
    sse_url = f"http://127.0.0.1:{port}/sse"
    reader = SseReader(sse_url)
    reader.start()
    try:
        event, data = reader.queue.get(timeout=SSE_OPEN_TIMEOUT_SEC)
    except Empty:
        reader.close()
        raise RuntimeError("timed out waiting for SSE endpoint event")
    if event == "__error__":
        reader.close()
        raise RuntimeError(data)
    if event != "endpoint":
        reader.close()
        raise RuntimeError(f"expected 'endpoint' SSE event, got '{event}'")
    # The endpoint event's data is the session-scoped URL (often a relative
    # path like `/?sessionId=abc`). Make it absolute against the server.
    base = urllib.parse.urlparse(sse_url)
    post_url = urllib.parse.urljoin(f"{base.scheme}://{base.netloc}", data)
    return reader, post_url


def initialize(reader: SseReader, post_url: str) -> None:
    post_json(post_url, {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": MCP_PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": CLIENT_NAME, "version": CLIENT_VERSION},
        },
    })
    wait_for_response(reader, 1, RPC_RESPONSE_TIMEOUT_SEC)
    post_json(post_url, {
        "jsonrpc": "2.0",
        "method": "notifications/initialized",
    })


def call_tool(reader: SseReader, post_url: str, name: str, args: dict) -> dict:
    post_json(post_url, {
        "jsonrpc": "2.0",
        "id": 2,
        "method": "tools/call",
        "params": {"name": name, "arguments": args},
    })
    return wait_for_response(reader, 2, RPC_RESPONSE_TIMEOUT_SEC)


# ---------------------------------------------------------------------------
# CLI entrypoints
# ---------------------------------------------------------------------------


def cmd_ping(port: int) -> int:
    try:
        reader, post_url = open_session(port)
    except Exception as e:
        print(f"ping failed: {e}", file=sys.stderr)
        return 1
    try:
        initialize(reader, post_url)
    except Exception as e:
        print(f"ping failed: {e}", file=sys.stderr)
        return 1
    finally:
        reader.close()
    return 0


def cmd_call(port: int, tool: str, args_json: str) -> int:
    try:
        args = json.loads(args_json)
    except Exception as e:
        print(f"invalid args JSON: {e}", file=sys.stderr)
        return 2
    if not isinstance(args, dict):
        print("args JSON must be an object", file=sys.stderr)
        return 2
    try:
        reader, post_url = open_session(port)
    except Exception as e:
        print(f"connect failed: {e}", file=sys.stderr)
        return 1
    try:
        initialize(reader, post_url)
        result = call_tool(reader, post_url, tool, args)
    except Exception as e:
        print(f"tool call failed: {e}", file=sys.stderr)
        return 1
    finally:
        reader.close()

    # BossTerm tool results are CallToolResult { content: [TextContent{text}] }.
    # The text is itself a JSON string we want to surface to the caller.
    content = result.get("content") or []
    for chunk in content:
        if chunk.get("type") == "text" and "text" in chunk:
            print(chunk["text"])
            break
    else:
        # No text content; surface the structured result as a courtesy.
        print(json.dumps(result))
    if result.get("isError"):
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(prog="bossterm-mcp")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_ping = sub.add_parser("ping", help="verify the MCP server responds")
    p_ping.add_argument("port", type=int)
    p_call = sub.add_parser("call", help="invoke a tool")
    p_call.add_argument("port", type=int)
    p_call.add_argument("tool")
    p_call.add_argument("args_json", help='JSON object, e.g. \'{"tab_id":"..."}\'')
    ns = parser.parse_args()
    if ns.cmd == "ping":
        return cmd_ping(ns.port)
    if ns.cmd == "call":
        return cmd_call(ns.port, ns.tool, ns.args_json)
    return 2


if __name__ == "__main__":
    sys.exit(main())
