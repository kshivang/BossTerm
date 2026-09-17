# Recording terminal output for regression tests

Start the desktop app with recording enabled:

```sh
BOSSTERM_PTY_LOG=1 ./gradlew :bossterm-app:run --no-daemon
```

Logs go to `~/.bossterm/pty-log/`. Set the variable to a directory to choose another
location. Unset it, or use `0` or `false`, to disable recording. Each local terminal
gets a unique file, including split and pre-connect sessions. Files close when
the terminal's background work finishes. An I/O failure stops recording and prints a
warning identifying the potentially incomplete log. Recording is opt-in and logs can contain
terminal output and typed input; inspect fixtures before committing them.

In desktop tests, call `PtyReplay.replayLog(log, width, height)` and assert the
expected rows. Only PTY output enters the emulator; user input and diagnostics
are ignored. The log stores decoded characters, not raw PTY bytes. UTF-8 and
escaped control characters preserve those characters, including surrogate code
units split between chunks.

## Scope

The replay reconstructs the final text grid at the supplied dimensions. Keep
the terminal size fixed during a capture and supply that size to the test.
Resize events, initial terminal state before capture, rendering frames, fonts,
and wall-clock timing are not recorded or reproduced. A screenshot-only rendering
glitch may therefore still need separate rendering tests. Passing replay does
not prove that every displayed frame was correct.

The parser accepts recordings produced by this version. Older unescaped logs
cannot reliably distinguish a literal backslash followed by `e` from ESC.
