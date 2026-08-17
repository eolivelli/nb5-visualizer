package io.github.eolivelli.nb5visualizer.tui;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.SimpleTheme;
import com.googlecode.lanterna.graphics.Theme;

/**
 * Flat dark look: plain black background, light gray text, dark gray input
 * fields, and an orange accent on whatever holds the focus — instead of
 * Lanterna's default blue-desktop curses theme.
 */
final class TuiTheme {

    static final TextColor BACKGROUND = TextColor.ANSI.BLACK;
    /** Default text: light gray, easier than stark white on black. */
    static final TextColor FOREGROUND = new TextColor.Indexed(252);
    /** Secondary text: hints, footers, current-location lines. */
    static final TextColor DIM = new TextColor.Indexed(244);
    /** Focus/selection color. */
    static final TextColor ACCENT = new TextColor.Indexed(208);
    /** Editable fields sit on a slightly lighter gray than the background. */
    static final TextColor INPUT_BACKGROUND = new TextColor.Indexed(236);

    static Theme theme() {
        return SimpleTheme.makeTheme(
                true,                  // bold the active element
                FOREGROUND, BACKGROUND,
                FOREGROUND, INPUT_BACKGROUND,
                TextColor.ANSI.BLACK, ACCENT,  // selected: black text on accent
                BACKGROUND);
    }

    private TuiTheme() {
    }
}
