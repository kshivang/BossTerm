package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Opens the macOS native Share sheet (AirDrop / Messages / Mail / Notes / Copy…) for a
 * share link, so it can be sent straight to a phone. Implemented out-of-process via a
 * small JXA (`osascript -l JavaScript`) helper that presents an `NSSharingServicePicker`
 * anchored at the mouse: keeping it out of our JVM means a failure there can't crash or
 * hang BossTerm, and AppKit threading stays in the helper's own process. macOS only —
 * callers fall back to copying the link elsewhere.
 */
object ShareSheet {
    private val log = LoggerFactory.getLogger(ShareSheet::class.java)

    @Volatile private var scriptFile: File? = null

    /** True on macOS, where the native share sheet exists. */
    fun isSupported(): Boolean = ShellCustomizationUtils.isMacOS()

    /**
     * Launch the share sheet for [url] (non-blocking). Returns true if the helper was
     * started; false on non-macOS or any launch failure, so callers can fall back (copy).
     */
    fun share(url: String): Boolean {
        if (!isSupported() || url.isBlank()) return false
        return try {
            val script = scriptFile ?: writeScript().also { scriptFile = it }
            // watchdog (120s) bounds the helper's lifetime even if the user walks away.
            ProcessBuilder("osascript", "-l", "JavaScript", script.absolutePath, url, "120")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        } catch (t: Throwable) {
            log.warn("macOS share sheet failed to launch: {}", t.message)
            false
        }
    }

    private fun writeScript(): File {
        val f = File.createTempFile("bossterm-share", ".js")
        f.deleteOnExit()
        f.writeText(JXA)
        return f
    }

    // JXA helper. argv[0] = url, argv[1] = watchdog seconds. A transient near-invisible
    // window at the mouse anchors the popover; a delegate quits the helper shortly after
    // the user chooses (so the chosen service's own UI takes over) or cancels; a hard
    // watchdog guarantees it never lingers. ($. / $( ) are literal — not Kotlin templates.)
    private val JXA = """
        ObjC.import('AppKit');
        ObjC.import('Foundation');

        function run(argv) {
          var urlStr = argv[0];
          var watchdog = parseFloat(argv[1] || "120");
          if (!urlStr) return "no-url";

          var app = $.NSApplication.sharedApplication;
          app.setActivationPolicy($.NSApplicationActivationPolicyAccessory);
          var term = $.NSSelectorFromString('terminate:');

          var mouse = $.NSEvent.mouseLocation;
          var rect = $.NSMakeRect(mouse.x, mouse.y, 1, 1);
          var win = $.NSWindow.alloc.initWithContentRectStyleMaskBackingDefer(
            rect, 0, $.NSBackingStoreBuffered, false);
          win.setAlphaValue(0.0);
          win.makeKeyAndOrderFront(null);
          app.activateIgnoringOtherApps(true);

          var url = $.NSURL.URLWithString(urlStr);
          var items = $.NSArray.arrayWithObject(url);

          if (!this.__BT_DELEGATE) {
            ObjC.registerSubclass({
              name: 'BTShareDelegate', superclass: 'NSObject',
              protocols: ['NSSharingServicePickerDelegate'],
              methods: {
                'sharingServicePicker:didChooseSharingService:': {
                  types: ['void', ['id', 'id']],
                  implementation: function(picker, service) {
                    var delay = service.isNil() ? 0.0 : 6.0;
                    $.NSApp.performSelectorWithObjectAfterDelay($.NSSelectorFromString('terminate:'), $(), delay);
                  }
                }
              }
            });
            this.__BT_DELEGATE = true;
          }
          var del = $.BTShareDelegate.alloc.init;

          var picker = $.NSSharingServicePicker.alloc.initWithItems(items);
          picker.delegate = del;
          picker.showRelativeToRectOfViewPreferredEdge($.NSMakeRect(0, 0, 1, 1), win.contentView, 1);

          app.performSelectorWithObjectAfterDelay(term, $(), watchdog);
          app.run();
          return "done";
        }
    """.trimIndent()
}
