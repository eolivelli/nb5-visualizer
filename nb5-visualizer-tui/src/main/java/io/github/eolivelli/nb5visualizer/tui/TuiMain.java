package io.github.eolivelli.nb5visualizer.tui;

import io.github.eolivelli.nb5visualizer.Main;

/**
 * Entry point of the self-contained TUI jar.
 *
 * <p>With no arguments it opens the interactive terminal UI; with arguments it
 * behaves exactly like the core CLI, so the one jar covers both uses:
 *
 * <pre>
 * java -jar nb5-visualizer-tui.jar                 # interactive
 * java -jar nb5-visualizer-tui.jar metrics-dir ...  # plain CLI
 * </pre>
 */
public final class TuiMain {

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            Main.main(args);
            return;
        }
        new VisualizerTui().run();
    }

    private TuiMain() {
    }
}
