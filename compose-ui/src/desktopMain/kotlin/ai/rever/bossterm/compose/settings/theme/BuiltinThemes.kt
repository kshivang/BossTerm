package ai.rever.bossterm.compose.settings.theme

/**
 * Built-in terminal color themes.
 *
 * Each theme provides:
 * - Terminal colors (foreground, background, cursor, selection, etc.)
 * - Full 16-color ANSI palette
 */
object BuiltinThemes {

    /**
     * Default XTerm-style dark theme.
     */
    val DEFAULT = Theme(
        id = "default",
        name = "Default",
        foreground = "0xFFFFFFFF",
        background = "0xFF000000",
        cursor = "0xFFFFFFFF",
        cursorText = "0xFF000000",
        selection = "0xFF214283",  // VS Code Dark / BossEditor style
        selectionText = "0xFFFFFFFF",
        searchMatch = "0xFFFFFF00",
        hyperlink = "0xFF5C9FFF",
        // Standard ANSI colors
        black = "0xFF000000",
        red = "0xFFCD0000",
        green = "0xFF00CD00",
        yellow = "0xFFCDCD00",
        blue = "0xFF0000EE",
        magenta = "0xFFCD00CD",
        cyan = "0xFF00CDCD",
        white = "0xFFE5E5E5",
        brightBlack = "0xFF7F7F7F",
        brightRed = "0xFFFF0000",
        brightGreen = "0xFF00FF00",
        brightYellow = "0xFFFFFF00",
        brightBlue = "0xFF5C5CFF",
        brightMagenta = "0xFFFF00FF",
        brightCyan = "0xFF00FFFF",
        brightWhite = "0xFFFFFFFF",
        isBuiltin = true
    )

    /**
     * Dracula theme - Popular dark theme with purple accents.
     * https://draculatheme.com
     */
    val DRACULA = Theme(
        id = "dracula",
        name = "Dracula",
        foreground = "0xFFF8F8F2",
        background = "0xFF282A36",
        cursor = "0xFFF8F8F2",
        cursorText = "0xFF282A36",
        selection = "0xFF44475A",
        selectionText = "0xFFF8F8F2",
        searchMatch = "0xFFF1FA8C",
        hyperlink = "0xFF8BE9FD",
        black = "0xFF21222C",
        red = "0xFFFF5555",
        green = "0xFF50FA7B",
        yellow = "0xFFF1FA8C",
        blue = "0xFFBD93F9",
        magenta = "0xFFFF79C6",
        cyan = "0xFF8BE9FD",
        white = "0xFFF8F8F2",
        brightBlack = "0xFF6272A4",
        brightRed = "0xFFFF6E6E",
        brightGreen = "0xFF69FF94",
        brightYellow = "0xFFFFFFA5",
        brightBlue = "0xFFD6ACFF",
        brightMagenta = "0xFFFF92DF",
        brightCyan = "0xFFA4FFFF",
        brightWhite = "0xFFFFFFFF",
        isBuiltin = true
    )

    /**
     * Nord theme - Arctic blue aesthetic.
     * https://www.nordtheme.com
     */
    val NORD = Theme(
        id = "nord",
        name = "Nord",
        foreground = "0xFFD8DEE9",
        background = "0xFF2E3440",
        cursor = "0xFFD8DEE9",
        cursorText = "0xFF2E3440",
        selection = "0xFF434C5E",
        selectionText = "0xFFD8DEE9",
        searchMatch = "0xFFEBCB8B",
        hyperlink = "0xFF88C0D0",
        black = "0xFF3B4252",
        red = "0xFFBF616A",
        green = "0xFFA3BE8C",
        yellow = "0xFFEBCB8B",
        blue = "0xFF81A1C1",
        magenta = "0xFFB48EAD",
        cyan = "0xFF88C0D0",
        white = "0xFFE5E9F0",
        brightBlack = "0xFF4C566A",
        brightRed = "0xFFBF616A",
        brightGreen = "0xFFA3BE8C",
        brightYellow = "0xFFEBCB8B",
        brightBlue = "0xFF81A1C1",
        brightMagenta = "0xFFB48EAD",
        brightCyan = "0xFF8FBCBB",
        brightWhite = "0xFFECEFF4",
        isBuiltin = true
    )

    /**
     * Solarized Dark theme - Ethan Schoonover's classic.
     * https://ethanschoonover.com/solarized
     */
    val SOLARIZED_DARK = Theme(
        id = "solarized-dark",
        name = "Solarized Dark",
        foreground = "0xFF839496",
        background = "0xFF002B36",
        cursor = "0xFF839496",
        cursorText = "0xFF002B36",
        selection = "0xFF073642",
        selectionText = "0xFF93A1A1",
        searchMatch = "0xFFB58900",
        hyperlink = "0xFF268BD2",
        black = "0xFF073642",
        red = "0xFFDC322F",
        green = "0xFF859900",
        yellow = "0xFFB58900",
        blue = "0xFF268BD2",
        magenta = "0xFFD33682",
        cyan = "0xFF2AA198",
        white = "0xFFEEE8D5",
        brightBlack = "0xFF002B36",
        brightRed = "0xFFCB4B16",
        brightGreen = "0xFF586E75",
        brightYellow = "0xFF657B83",
        brightBlue = "0xFF839496",
        brightMagenta = "0xFF6C71C4",
        brightCyan = "0xFF93A1A1",
        brightWhite = "0xFFFDF6E3",
        isBuiltin = true
    )

    /**
     * Solarized Light theme.
     */
    val SOLARIZED_LIGHT = Theme(
        id = "solarized-light",
        name = "Solarized Light",
        foreground = "0xFF657B83",
        background = "0xFFFDF6E3",
        cursor = "0xFF657B83",
        cursorText = "0xFFFDF6E3",
        selection = "0xFFEEE8D5",
        selectionText = "0xFF586E75",
        searchMatch = "0xFFB58900",
        hyperlink = "0xFF268BD2",
        black = "0xFFEEE8D5",
        red = "0xFFDC322F",
        green = "0xFF859900",
        yellow = "0xFFB58900",
        blue = "0xFF268BD2",
        magenta = "0xFFD33682",
        cyan = "0xFF2AA198",
        white = "0xFF073642",
        brightBlack = "0xFFFDF6E3",
        brightRed = "0xFFCB4B16",
        brightGreen = "0xFF93A1A1",
        brightYellow = "0xFF839496",
        brightBlue = "0xFF657B83",
        brightMagenta = "0xFF6C71C4",
        brightCyan = "0xFF586E75",
        brightWhite = "0xFF002B36",
        isBuiltin = true
    )

    /**
     * Gruvbox Dark theme - Retro warm colors.
     * https://github.com/morhetz/gruvbox
     */
    val GRUVBOX_DARK = Theme(
        id = "gruvbox-dark",
        name = "Gruvbox Dark",
        foreground = "0xFFEBDBB2",
        background = "0xFF282828",
        cursor = "0xFFEBDBB2",
        cursorText = "0xFF282828",
        selection = "0xFF504945",
        selectionText = "0xFFEBDBB2",
        searchMatch = "0xFFFABD2F",
        hyperlink = "0xFF83A598",
        black = "0xFF282828",
        red = "0xFFCC241D",
        green = "0xFF98971A",
        yellow = "0xFFD79921",
        blue = "0xFF458588",
        magenta = "0xFFB16286",
        cyan = "0xFF689D6A",
        white = "0xFFA89984",
        brightBlack = "0xFF928374",
        brightRed = "0xFFFB4934",
        brightGreen = "0xFFB8BB26",
        brightYellow = "0xFFFABD2F",
        brightBlue = "0xFF83A598",
        brightMagenta = "0xFFD3869B",
        brightCyan = "0xFF8EC07C",
        brightWhite = "0xFFEBDBB2",
        isBuiltin = true
    )

    /**
     * One Dark theme - Atom's default dark theme.
     * https://github.com/atom/atom/tree/master/packages/one-dark-syntax
     */
    val ONE_DARK = Theme(
        id = "one-dark",
        name = "One Dark",
        foreground = "0xFFABB2BF",
        background = "0xFF282C34",
        cursor = "0xFF528BFF",
        cursorText = "0xFF282C34",
        selection = "0xFF3E4451",
        selectionText = "0xFFABB2BF",
        searchMatch = "0xFFE5C07B",
        hyperlink = "0xFF61AFEF",
        black = "0xFF282C34",
        red = "0xFFE06C75",
        green = "0xFF98C379",
        yellow = "0xFFE5C07B",
        blue = "0xFF61AFEF",
        magenta = "0xFFC678DD",
        cyan = "0xFF56B6C2",
        white = "0xFFABB2BF",
        brightBlack = "0xFF5C6370",
        brightRed = "0xFFE06C75",
        brightGreen = "0xFF98C379",
        brightYellow = "0xFFE5C07B",
        brightBlue = "0xFF61AFEF",
        brightMagenta = "0xFFC678DD",
        brightCyan = "0xFF56B6C2",
        brightWhite = "0xFFFFFFFF",
        isBuiltin = true
    )

    /**
     * Monokai theme - Sublime Text classic.
     */
    val MONOKAI = Theme(
        id = "monokai",
        name = "Monokai",
        foreground = "0xFFF8F8F2",
        background = "0xFF272822",
        cursor = "0xFFF8F8F2",
        cursorText = "0xFF272822",
        selection = "0xFF49483E",
        selectionText = "0xFFF8F8F2",
        searchMatch = "0xFFE6DB74",
        hyperlink = "0xFF66D9EF",
        black = "0xFF272822",
        red = "0xFFF92672",
        green = "0xFFA6E22E",
        yellow = "0xFFF4BF75",
        blue = "0xFF66D9EF",
        magenta = "0xFFAE81FF",
        cyan = "0xFFA1EFE4",
        white = "0xFFF8F8F2",
        brightBlack = "0xFF75715E",
        brightRed = "0xFFF92672",
        brightGreen = "0xFFA6E22E",
        brightYellow = "0xFFF4BF75",
        brightBlue = "0xFF66D9EF",
        brightMagenta = "0xFFAE81FF",
        brightCyan = "0xFFA1EFE4",
        brightWhite = "0xFFF9F8F5",
        isBuiltin = true
    )

    /**
     * Tokyo Night theme - Modern Japanese aesthetic.
     * https://github.com/enkia/tokyo-night-vscode-theme
     */
    val TOKYO_NIGHT = Theme(
        id = "tokyo-night",
        name = "Tokyo Night",
        foreground = "0xFFA9B1D6",
        background = "0xFF1A1B26",
        cursor = "0xFFC0CAF5",
        cursorText = "0xFF1A1B26",
        selection = "0xFF33467C",
        selectionText = "0xFFA9B1D6",
        searchMatch = "0xFFE0AF68",
        hyperlink = "0xFF7AA2F7",
        black = "0xFF15161E",
        red = "0xFFF7768E",
        green = "0xFF9ECE6A",
        yellow = "0xFFE0AF68",
        blue = "0xFF7AA2F7",
        magenta = "0xFFBB9AF7",
        cyan = "0xFF7DCFFF",
        white = "0xFFA9B1D6",
        brightBlack = "0xFF414868",
        brightRed = "0xFFF7768E",
        brightGreen = "0xFF9ECE6A",
        brightYellow = "0xFFE0AF68",
        brightBlue = "0xFF7AA2F7",
        brightMagenta = "0xFFBB9AF7",
        brightCyan = "0xFF7DCFFF",
        brightWhite = "0xFFC0CAF5",
        isBuiltin = true
    )

    /**
     * Catppuccin Mocha theme - Soothing pastel colors.
     * https://github.com/catppuccin/catppuccin
     */
    val CATPPUCCIN_MOCHA = Theme(
        id = "catppuccin-mocha",
        name = "Catppuccin Mocha",
        foreground = "0xFFCDD6F4",
        background = "0xFF1E1E2E",
        cursor = "0xFFF5E0DC",
        cursorText = "0xFF1E1E2E",
        selection = "0xFF45475A",
        selectionText = "0xFFCDD6F4",
        searchMatch = "0xFFF9E2AF",
        hyperlink = "0xFF89B4FA",
        black = "0xFF45475A",
        red = "0xFFF38BA8",
        green = "0xFFA6E3A1",
        yellow = "0xFFF9E2AF",
        blue = "0xFF89B4FA",
        magenta = "0xFFF5C2E7",
        cyan = "0xFF94E2D5",
        white = "0xFFBAC2DE",
        brightBlack = "0xFF585B70",
        brightRed = "0xFFF38BA8",
        brightGreen = "0xFFA6E3A1",
        brightYellow = "0xFFF9E2AF",
        brightBlue = "0xFF89B4FA",
        brightMagenta = "0xFFF5C2E7",
        brightCyan = "0xFF94E2D5",
        brightWhite = "0xFFA6ADC8",
        isBuiltin = true
    )

    /**
     * BOSS Operator — the unified "Operator's Console" identity.
     *
     * Shares the BossConsole host floor (ink #0E1217) so the terminal and the
     * chrome around it read as one continuous surface. The cursor is the amber
     * signal — the single "live / now" color of the whole design system.
     */
    val BOSS_OPERATOR = Theme(
        id = "boss-operator",
        name = "BOSS Operator",
        foreground = "0xFFD7DEE6",
        background = "0xFF0E1217",   // shared `ink` floor
        cursor = "0xFFF2A93B",       // amber signal — the signature
        cursorText = "0xFF0E1217",
        selection = "0xFF21405A",
        selectionText = "0xFFE9EEF3",
        searchMatch = "0xFFF0B429",
        hyperlink = "0xFF56C7E0",    // cyan `data`
        // ANSI 16 — tuned to sit calmly on the ink floor
        black = "0xFF15202B",
        red = "0xFFF2685F",
        green = "0xFF6FD08C",
        yellow = "0xFFF2A93B",
        blue = "0xFF5C9FE0",
        magenta = "0xFFC792EA",
        cyan = "0xFF56C7E0",
        white = "0xFFC7D1DB",
        brightBlack = "0xFF3A4B5C",
        brightRed = "0xFFFF8A80",
        brightGreen = "0xFF8FE0A6",
        brightYellow = "0xFFFFC560",
        brightBlue = "0xFF82B7F0",
        brightMagenta = "0xFFDDB0F5",
        brightCyan = "0xFF7FD9EE",
        brightWhite = "0xFFE9EEF3",
        isBuiltin = true
    )

    /**
     * BOSS Blueprint — the bossconsole.ai identity, and the default.
     *
     * Shares the BossConsole host floor (ink #05070B, the site's `--ink`) so the
     * terminal and the chrome around it read as one continuous surface.
     *
     * The cursor is the site's `--blue-bright` rather than `--blue` because a
     * block cursor has to be visible against the FLOOR: 4.8:1 vs `--blue`'s
     * 3.8:1.
     *
     * `cursorText` IS wired: an opaque block cursor repaints the character it
     * covers in this colour, so `cursorText`-on-`cursor` is a real contrast pair.
     * `selectionText` (`Theme.selectionTextColor`) is still unwired and has no
     * readers.
     */
    val BOSS_BLUEPRINT = Theme(
        id = "boss-blueprint",
        name = "BOSS Blueprint",
        foreground = "0xFFD5DBE5",   // site `.console-thread p`
        background = "0xFF05070B",   // shared `ink` floor / site `--ink`
        cursor = "0xFF2F74FF",       // site `--blue-bright`
        // Ink, not white: now that cursorText actually paints, this is a real
        // glyph-on-fill pair and white gave only 4.15:1 against the cursor. Ink
        // gives 4.80:1 - the same ratio as cursor-against-floor, by symmetry.
        cursorText = "0xFF05070B",
        selection = "0xFF123A7A",
        selectionText = "0xFFE7EDFA",
        searchMatch = "0xFFF1DF9E",  // the site's one warm color
        hyperlink = "0xFF88A9FF",    // `data` — site `.audit-line svg` #8af
        // ANSI 16 — cool-tuned to sit on the ink floor
        black = "0xFF0B1019",        // site `--ink-2`
        red = "0xFFFF5D5D",
        green = "0xFF2FD98A",
        yellow = "0xFFF0B429",
        blue = "0xFF2F74FF",         // site `--blue-bright`
        magenta = "0xFFB48CFF",
        cyan = "0xFF56C7E0",
        white = "0xFFC3CCDA",
        brightBlack = "0xFF2E3B4F",
        brightRed = "0xFFFF8A8A",
        brightGreen = "0xFF6BE8AC",
        brightYellow = "0xFFFFD98A",
        brightBlue = "0xFF7EA6FF",
        brightMagenta = "0xFFD0B6FF",
        brightCyan = "0xFF8FDDEC",
        brightWhite = "0xFFE7EDFA",
        isBuiltin = true
    )

    /**
     * BOSS Blueprint Light - the paper half of the bossconsole.ai identity.
     *
     * Mirrors the host's `BossBlueprintLightColorScheme` (the site's `--paper`
     * sections), so the terminal and the chrome around it read as one surface on
     * a light host theme exactly as they do on a dark one.
     *
     * Three rules govern a light ANSI palette, and all three are the opposite of
     * the dark case (`LightThemeContrastTest` enforces them):
     *
     * 1. Every `bright*` slot is DARKER than its base, not lighter. Bold text is
     *    ANSI 8-15; brighten those on paper and every bold line disappears.
     * 2. `white` (ANSI 7) is a mid grey, never near-white. This is the bug fixed
     *    in `terminal-tab` PR #72: a near-white ANSI 7 is 1.27:1 on paper, and no
     *    render-time guard reaches it because the palette IS the source.
     * 3. The cursor is an opaque block, so it needs contrast against the FLOOR,
     *    not against the text. `--blue` gives 4.90:1 here, which is why it can be
     *    the cursor on paper while `boss-daylight` has to darken its amber.
     */
    val BOSS_BLUEPRINT_LIGHT = Theme(
        id = "boss-blueprint-light",
        name = "BOSS Blueprint Light",
        // Softened off pure ink, the same way dark Blueprint's foreground sits a
        // shade under its `chalk`: body copy, not headline weight. 14.6:1.
        foreground = "0xFF141A24",
        background = "0xFFF5F7FB",   // host `ink` / site `--paper`
        cursor = "0xFF0F5BFF",       // host `signal` (--blue), 4.90:1 on paper
        cursorText = "0xFFFFFFFF",   // host `onSignal`, 5.26:1 on the cursor fill
        // Deepened from the host's `signalWash` #DCE7FF (1.16:1 on paper). That
        // wash sits BEHIND a 2.dp indicator on a selected sidebar row; a terminal
        // selection has no indicator, so the fill carries the whole signal alone.
        // 1.30:1, and ink text still reads on it at 12.5:1.
        selection = "0xFFC9DBFF",
        selectionText = "0xFF05070B",// host `textPrimary`
        // A warm fill has to do two jobs at once: separate from paper (3.1:1) and
        // still carry ink-coloured text (11.9:1). The dark theme's pale #F1DF9E
        // does neither on paper.
        searchMatch = "0xFFFFC93B",
        hyperlink = "0xFF0C3FBF",    // host `data` - deeper than signal, so links stay distinct
        // ANSI 16 - warm-neutral hues re-grounded for paper
        black = "0xFF1B2330",
        red = "0xFFD33B4A",          // host `alert`
        // Darkened off the host's `ok` #1E9E63, which is only 3.20:1 on paper.
        // Green on white is the classic light-palette trap. 4.24:1.
        green = "0xFF178750",
        yellow = "0xFFA8710A",       // host `warn`
        blue = "0xFF0F5BFF",         // host `signal`
        magenta = "0xFF8B2FBF",
        cyan = "0xFF0E7490",
        white = "0xFF687081",        // host `textSecondary` - see rule 2 above
        // ANSI 8 is the dim companion of ANSI 0, and on paper ANSI 0 is the ink,
        // so 8 is necessarily LIGHTER than its base - the one slot exempt from
        // rule 1. It still has to clear the 3:1 UI floor, which the host's
        // `textMuted` #9AA3B2 does not (2.37:1). Dimmer than ANSI 7, readable. 3.38:1.
        brightBlack = "0xFF7E8797",
        brightRed = "0xFFB02A38",
        brightGreen = "0xFF106340",
        brightYellow = "0xFF87590A",
        brightBlue = "0xFF0C3FBF",
        brightMagenta = "0xFF6E1F99",
        brightCyan = "0xFF0B5B73",
        brightWhite = "0xFF05070B",  // host `textPrimary` - the strongest text
        isBuiltin = true
    )

    /**
     * BOSS Daylight - the paper half of the Operator identity.
     *
     * Mirrors the host's `BossLightColorScheme`. Same three light-palette rules as
     * [BOSS_BLUEPRINT_LIGHT], plus one identity compromise that is deliberate:
     *
     * **The cursor is the darkened amber #95580A, not the identity's #D9871A.**
     * The host scheme's own note records why - the amber signal is 2.6:1 on this
     * floor, which is exactly why the host carries a separate `signalText` of
     * #95580A for amber glyphs. An opaque block cursor at 2.6:1 is a smear, so the
     * terminal takes the darker amber (5.29:1) and ANSI 3 follows it. The chrome
     * still FILLS with #D9871A, so the signature survives where a fill can carry
     * it. Amber on paper is a hard combination; this is the trade, not an oversight.
     */
    val BOSS_DAYLIGHT = Theme(
        id = "boss-daylight",
        name = "BOSS Daylight",
        foreground = "0xFF1D242E",   // softened off the host's `textPrimary` #131820
        background = "0xFFF5F7FA",   // host `ink`
        cursor = "0xFF95580A",       // host `signalText`, 5.29:1 - see the note above
        cursorText = "0xFFFFFFFF",   // 5.68:1 on the cursor fill
        // Deepened from the host's `signalWash` #FBEFD8, which is 1.06:1 here and
        // effectively invisible as a standalone selection. Same reasoning as
        // BOSS_BLUEPRINT_LIGHT's selection. 1.41:1, text on it 10.3:1.
        selection = "0xFFEFCE8C",
        selectionText = "0xFF131820",// host `textPrimary`
        searchMatch = "0xFFFFC44D",
        // Darkened off the host's `data` #1E7FA8: that value is 4.20:1 on this
        // floor, which clears the 3:1 UI-component floor but not the 4.5:1 text
        // floor - and a hyperlink is text. 5.60:1.
        hyperlink = "0xFF186A8E",
        // ANSI 16 - the Operator hues re-grounded for paper
        black = "0xFF20262E",
        red = "0xFFD2453B",          // host `alert`
        // Darkened off the host's `ok` #2F9E54 (3.19:1 here), as in
        // BOSS_BLUEPRINT_LIGHT. 4.66:1.
        green = "0xFF257F44",
        // The darker amber again, not the host's `warn` #B87D0A: warn is 3.3:1 on
        // this floor, which passes as a fill but not as a glyph, and ANSI 3 is text.
        yellow = "0xFF95580A",
        blue = "0xFF186A8E",         // the same darkened `data` as `hyperlink`
        magenta = "0xFF9B2C8F",
        cyan = "0xFF127C86",
        white = "0xFF5A6675",        // host `textSecondary`
        // The dim slot, exempt from rule 1 for the reason recorded on
        // BOSS_BLUEPRINT_LIGHT. 3.06:1; the host's `textMuted` is 2.48:1.
        brightBlack = "0xFF848F9E",
        brightRed = "0xFFA8342C",
        brightGreen = "0xFF1C6B39",
        brightYellow = "0xFF7A4708",
        brightBlue = "0xFF175F7E",
        brightMagenta = "0xFF77206D",
        brightCyan = "0xFF0D5C64",
        brightWhite = "0xFF131820",  // host `textPrimary`
        isBuiltin = true
    )

    /**
     * All built-in themes.
     *
     * BOSS_BLUEPRINT stays first: `ThemeDefaultsTest` asserts the default leads
     * both builtin lists. Each light identity sits next to its dark sibling.
     */
    val ALL = listOf(
        BOSS_BLUEPRINT,
        BOSS_BLUEPRINT_LIGHT,
        BOSS_OPERATOR,
        BOSS_DAYLIGHT,
        DEFAULT,
        DRACULA,
        NORD,
        SOLARIZED_DARK,
        SOLARIZED_LIGHT,
        GRUVBOX_DARK,
        ONE_DARK,
        MONOKAI,
        TOKYO_NIGHT,
        CATPPUCCIN_MOCHA
    )

    /**
     * Get a built-in theme by ID.
     */
    fun getById(id: String): Theme? = ALL.find { it.id == id }

    /**
     * Default theme ID — the unified BOSS identity.
     *
     * Changing this is not a one-line edit: [UiTheme.fromTheme] keys its
     * hand-authored chrome tokens off theme id (not off this constant), and
     * `TerminalSettings`' fresh-install color defaults must equal this theme's
     * — `ThemeDefaultsTest` fails if they drift.
     */
    const val DEFAULT_THEME_ID = "boss-blueprint"

    /**
     * The theme [DEFAULT_THEME_ID] names, for callers that need the object and
     * would otherwise hardcode it. Note this is NOT [DEFAULT] — that is the
     * stark black-on-white XTerm theme, which is a *named* theme and not the
     * product default. `ThemeDefaultsTest` pins the two to agree.
     */
    val PRODUCT_DEFAULT: Theme = BOSS_BLUEPRINT
}
