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

  return {
    nextReconnectAttempt: nextReconnectAttempt,
    captureRowOffset: captureRowOffset,
    visibleImageRow: visibleImageRow,
    visibleHostRowRange: visibleHostRowRange,
    scrollLinesFromBottom: scrollLinesFromBottom,
    scrollLineForDistance: scrollLineForDistance,
    graphicsMemoryFits: graphicsMemoryFits,
    graphicsAttemptAfterDenied: graphicsAttemptAfterDenied
  };
}));
