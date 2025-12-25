# Troubleshooting

Solutions to common BossTerm issues.

---

## Terminal Issues

### Terminal Not Starting

**Symptom**: Blank screen, no prompt appears.

**Solutions**:

1. **Check SHELL environment variable**:
   ```bash
   echo $SHELL
   ```
   Should output `/bin/bash`, `/bin/zsh`, or similar.

2. **Try explicit shell**:
   ```kotlin
   EmbeddableTerminal(
       command = "/bin/zsh"
   )
   ```

3. **Check working directory exists**:
   ```kotlin
   EmbeddableTerminal(
       workingDirectory = System.getProperty("user.home")
   )
   ```

### Terminal Exits Immediately

**Symptom**: Terminal shows briefly then closes.

**Causes**:
- Shell configuration error (`.bashrc`, `.zshrc`)
- Non-interactive shell

**Solutions**:

1. Test shell directly:
   ```bash
   bash -l -c "echo OK"
   zsh -l -c "echo OK"
   ```

2. Check shell config files for syntax errors.

3. View exit code:
   ```kotlin
   EmbeddableTerminal(
       onExit = { code -> println("Exit: $code") }
   )
   ```

---

## Rendering Issues

### Missing Characters/Glyphs

**Symptom**: Boxes or blank spaces instead of characters.

**Solutions**:

1. BossTerm includes MesloLGS Nerd Font by default with powerline symbols.

2. For custom fonts, use one with good Unicode coverage:
   ```kotlin
   EmbeddableTerminal(
       settings = TerminalSettings(fontName = "JetBrains Mono")
   )
   ```

3. Use a [Nerd Font](https://www.nerdfonts.com/) for maximum glyph coverage.

### Emoji Display Issues

**Symptom**: Emoji appear as separate characters or wrong symbols.

BossTerm handles emoji with variation selectors (☁️). If issues persist:

1. Update to the latest BossTerm version
2. Some complex emoji sequences (ZWJ families) may have limitations

### Colors Not Working

**Symptom**: Terminal apps show wrong or no colors.

**Solutions**:

1. Verify TERM environment:
   ```bash
   echo $TERM  # Should be xterm-256color
   ```

2. Force color support:
   ```bash
   export COLORTERM=truecolor
   ```

---

## Focus Management Issues

### Terminal Not Receiving Keyboard Input

**Symptom**: Click on terminal doesn't give it focus, keys ignored.

**Cause**: Parent containers with `.focusable()` or `.clickable { requestFocus() }` steal focus.

**Solution**: Remove competing focus modifiers:

```kotlin
// WRONG - steals focus
Column(
    modifier = Modifier
        .focusable()
        .clickable { requestFocus() }
) {
    EmbeddableTerminal()
}

// CORRECT - observe without competing
Column(
    modifier = Modifier
        .onFocusChanged { state ->
            // Track focus state
        }
) {
    EmbeddableTerminal()
}
```

### Focus Lost After Toolbar Click

**Solution**: Return focus to terminal after toolbar actions:

```kotlin
val focusRequester = remember { FocusRequester() }

Column {
    Button(onClick = {
        terminalState.write("command\n")
        focusRequester.requestFocus()  // Return focus
    }) { Text("Run") }

    Box(modifier = Modifier.focusRequester(focusRequester)) {
        EmbeddableTerminal(state = terminalState)
    }
}
```

---

## Performance Issues

### High CPU During Output

**Symptom**: CPU spikes when terminal has heavy output.

**Solutions**:

1. Switch to throughput mode:
   ```json
   {
     "performanceMode": "throughput"
   }
   ```

2. Reduce scrollback buffer:
   ```json
   {
     "bufferMaxLines": 5000
   }
   ```

### Slow Response for Interactive Commands

**Symptom**: Lag when typing in vim, ssh, etc.

**Solution**: Switch to latency mode:
```json
{
  "performanceMode": "latency"
}
```

---

## Notification Issues

### Notifications Not Appearing

**Checklist**:

1. ☐ OSC 133 configured in shell ([[Shell-Integration]])
2. ☐ `notifyOnCommandComplete: true` in settings
3. ☐ Window was unfocused when command completed
4. ☐ Command ran > `notifyMinDurationSeconds` (default: 5)
5. ☐ macOS notification permissions granted

### macOS Permission Request

On first notification, macOS asks for permission. If denied:

1. Open **System Settings > Notifications**
2. Find **BossTerm**
3. Enable notifications

---

## Shell Integration Issues

### OSC 7 Not Working

**Symptom**: New tabs don't inherit working directory.

**Solutions**:

1. Verify shell integration configured ([[Shell-Integration]])
2. Test emission:
   ```bash
   cd /tmp
   # New tab should open in /tmp
   ```
3. Use debug panel (Ctrl/Cmd+Shift+D) to check for OSC 7 sequences

### OSC 133 Not Working

**Symptom**: No command notifications.

**Solutions**:

1. Add shell integration code ([[Shell-Integration]])
2. Verify with long command:
   ```bash
   sleep 6  # Then switch to another app
   ```
3. Check debug panel for OSC 133 sequences

---

## Debug Tools

### Enable Debug Panel

Press **Ctrl/Cmd+Shift+D** to open the debug panel.

Features:
- View incoming escape sequences
- Inspect buffer state
- Track I/O data

### Enable Debug Logging

```json
{
  "debugModeEnabled": true
}
```

---

## Getting Help

If issues persist:

1. Check [GitHub Issues](https://github.com/kshivang/BossTerm/issues)
2. Enable debug mode and collect logs
3. Open a new issue with:
   - BossTerm version
   - OS and version
   - Steps to reproduce
   - Debug panel output (if applicable)

---

## See Also

- [[Configuration]] - All settings
- [[Shell-Integration]] - OSC setup
- [[Frequently-Asked-Questions]] - Common questions
