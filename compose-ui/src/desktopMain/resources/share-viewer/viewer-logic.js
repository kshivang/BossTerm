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

  return {
    nextReconnectAttempt: nextReconnectAttempt,
    captureRowOffset: captureRowOffset,
    visibleImageRow: visibleImageRow
  };
}));
