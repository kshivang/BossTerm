// Pure, executable state/coordinate helpers shared by viewer.js and the Node regression harness.
(function (root, factory) {
  var api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.BossTermViewerLogic = api;
}(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function nextReconnectAttempt(currentAttempt, maximumAttempts) {
    if (currentAttempt >= maximumAttempts) {
      return { retry: false, attempt: currentAttempt };
    }
    return { retry: true, attempt: currentAttempt + 1 };
  }

  function isTerminalWebSocketClose(code) {
    return code === 1000 || code === 1003 || code === 1008;
  }

  function captureRowOffset(xtermBaseY, hostHistoryLines) {
    return xtermBaseY - hostHistoryLines;
  }

  function visibleImageRow(absoluteRow, capturedOffset, viewportY) {
    return absoluteRow + capturedOffset - viewportY;
  }

  function visibleHostRowRange(viewportY, capturedOffset, terminalRows) {
    var first = viewportY - capturedOffset;
    return { first: first, last: first + Math.max(0, terminalRows - 1) };
  }

  function scrollLinesFromBottom(baseY, viewportY) {
    return Math.max(0, baseY - viewportY);
  }

  function scrollLineForDistance(updatedBaseY, linesFromBottom) {
    return Math.max(0, updatedBaseY - linesFromBottom);
  }

  function queuePaneRepaint(write, readBuffer, scrollToLine, data, complete) {
    var restoreLinesFromBottom = 0;
    // Queue both entries synchronously: later paneOutput writes must land after the repaint.
    write("", function () {
      var active = readBuffer();
      restoreLinesFromBottom = scrollLinesFromBottom(active.baseY, active.viewportY);
    });
    write(data, function () {
      if (restoreLinesFromBottom > 0) {
        scrollToLine(scrollLineForDistance(readBuffer().baseY, restoreLinesFromBottom));
      }
      complete();
    });
  }

  function graphicsMemoryFits(
    paneBytes,
    viewerBytes,
    addedBytes,
    replacedBytes,
    paneLimit,
    viewerLimit
  ) {
    var replaced = replacedBytes || 0;
    return addedBytes <= paneLimit &&
      paneBytes + addedBytes - replaced <= paneLimit &&
      viewerBytes + addedBytes - replaced <= viewerLimit;
  }

  function graphicsAttemptAfterDenied(attempts, requestPending) {
    return requestPending && attempts > 0 ? attempts - 1 : attempts;
  }

  // Floor for xterm.js's minimumContrastRatio, as sent by the host.
  //
  // A CLI that hardcodes a dark-terminal palette emits truecolor the host cannot rewrite
  // (the viewer's live text is a raw pty stream), so on a light theme its white text lands
  // at 1.07:1 and vanishes. xterm.js corrects per cell if given a floor.
  //
  // Validated here rather than trusted: the value crosses the wire, and xterm.js THROWS on
  // some out-of-range option values. Mirrors its own normalisation - clamp to [1, 21], one
  // decimal - so a hostile or stale host cannot take the viewer down, and anything
  // unparseable means "off" rather than a guessed floor.
  function validMinimumContrastRatio(value) {
    var n = Number(value);
    if (!isFinite(n) || n <= 1) return 1;
    return Math.max(1, Math.min(21, Math.round(n * 10) / 10));
  }

  return {
    validMinimumContrastRatio: validMinimumContrastRatio,
    nextReconnectAttempt: nextReconnectAttempt,
    isTerminalWebSocketClose: isTerminalWebSocketClose,
    captureRowOffset: captureRowOffset,
    visibleImageRow: visibleImageRow,
    visibleHostRowRange: visibleHostRowRange,
    scrollLinesFromBottom: scrollLinesFromBottom,
    scrollLineForDistance: scrollLineForDistance,
    queuePaneRepaint: queuePaneRepaint,
    graphicsMemoryFits: graphicsMemoryFits,
    graphicsAttemptAfterDenied: graphicsAttemptAfterDenied
  };
}));
