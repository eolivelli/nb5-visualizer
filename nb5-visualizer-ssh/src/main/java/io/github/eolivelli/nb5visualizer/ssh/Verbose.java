package io.github.eolivelli.nb5visualizer.ssh;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Opt-in progress logging for the SSH flow ({@code -v}/{@code --verbose}).
 * Writes to stderr so piped report output stays clean. The TUI turns it off
 * before the full-screen UI starts — stray lines would corrupt the screen.
 */
public final class Verbose {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static volatile boolean enabled;

    public static void enable() {
        enabled = true;
    }

    public static void disable() {
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void log(String message) {
        if (enabled) {
            System.err.println(LocalTime.now().format(TIME) + " [ssh] " + message);
        }
    }

    private Verbose() {
    }
}
