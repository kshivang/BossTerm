package ai.rever.bossterm.terminal

import java.util.*
import java.util.concurrent.ConcurrentHashMap

open class TextStyle @JvmOverloads constructor(
    val foreground: TerminalColor? = null,
    val background: TerminalColor? = null,
    options: Set<Option> = NO_OPTIONS
) {
    // Immutable options set (issue #144 - reduce object allocation)
    private val myOptions: Set<Option>

    init {
        // Reuse NO_OPTIONS for empty sets to avoid allocation
        myOptions = if (options.isEmpty()) NO_OPTIONS else options.toSet()
    }

    fun createEmptyWithColors(): TextStyle {
        return TextStyle(this.foreground, this.background)
    }

    fun hasOption(option: Option?): Boolean {
        return myOptions.contains(option)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val textStyle = other as TextStyle
        return this.foreground == textStyle.foreground &&
                this.background == textStyle.background &&
                myOptions == textStyle.myOptions
    }

    override fun hashCode(): Int {
        return Objects.hash(this.foreground, this.background, myOptions)
    }

    open fun toBuilder(): Builder {
        return Builder(this)
    }

    enum class Option {
        BOLD,
        ITALIC,
        SLOW_BLINK,
        RAPID_BLINK,
        DIM,
        INVERSE,
        UNDERLINED,
        HIDDEN,
        PROTECTED;

        fun set(options: MutableSet<Option>, `val`: Boolean) {
            if (`val`) {
                options.add(this)
            } else {
                options.remove(this)
            }
        }
    }

    open class Builder {
        private var myForeground: TerminalColor?
        private var myBackground: TerminalColor?
        private val myOptions: MutableSet<Option>

        constructor(textStyle: TextStyle) {
            myForeground = textStyle.foreground
            myBackground = textStyle.background
            myOptions = textStyle.myOptions.toMutableSet()
        }

        constructor() {
            myForeground = null
            myBackground = null
            myOptions = mutableSetOf()
        }

        fun setForeground(foreground: TerminalColor?): Builder {
            myForeground = foreground
            return this
        }

        fun setBackground(background: TerminalColor?): Builder {
            myBackground = background
            return this
        }

        fun setOption(option: Option, `val`: Boolean): Builder {
            option.set(myOptions, `val`)
            return this
        }

        open fun build(): TextStyle {
            return getOrCreate(myForeground, myBackground, myOptions)
        }
    }

    companion object {
        private val NO_OPTIONS: Set<Option> = emptySet()

        val EMPTY: TextStyle = TextStyle()

        /**
         * Cache for common TextStyle combinations (issue #144).
         * Uses ConcurrentHashMap for thread-safe interning of indexed color styles.
         * Only caches styles with indexed colors (0-255) to bound memory usage.
         */
        private val COMMON_STYLES = ConcurrentHashMap<Int, TextStyle>(128)

        /**
         * Compute a unique key for a style combination.
         * Packs fgIndex (9 bits) + bgIndex (9 bits) + options bitmask (9 bits) into an Int.
         * Uses -1 offset (+1) to handle null colors (represented as -1).
         */
        private fun computeKey(fgIndex: Int, bgIndex: Int, optionsBitmask: Int): Int {
            return ((fgIndex + 1) and 0x1FF) or
                   (((bgIndex + 1) and 0x1FF) shl 9) or
                   ((optionsBitmask and 0x1FF) shl 18)
        }

        /**
         * Get a cached TextStyle or create a new one.
         * Only caches styles with indexed colors (0-255) to bound memory.
         * RGB/truecolor styles are always created fresh (unbounded color space).
         */
        fun getOrCreate(fg: TerminalColor?, bg: TerminalColor?, options: Set<Option>): TextStyle {
            // Only intern indexed colors to bound cache size
            val fgIndex = if (fg?.isIndexed == true) fg.colorIndex else -1
            val bgIndex = if (bg?.isIndexed == true) bg.colorIndex else -1

            // Don't cache RGB colors (unbounded color space)
            if ((fg != null && !fg.isIndexed) || (bg != null && !bg.isIndexed)) {
                return TextStyle(fg, bg, options)
            }

            // Don't cache out-of-range indices (shouldn't happen, but be safe)
            if (fgIndex > 255 || bgIndex > 255) {
                return TextStyle(fg, bg, options)
            }

            val optionsBitmask = options.fold(0) { acc, opt -> acc or (1 shl opt.ordinal) }
            val key = computeKey(fgIndex, bgIndex, optionsBitmask)

            return COMMON_STYLES.computeIfAbsent(key) { TextStyle(fg, bg, options) }
        }
    }
}
