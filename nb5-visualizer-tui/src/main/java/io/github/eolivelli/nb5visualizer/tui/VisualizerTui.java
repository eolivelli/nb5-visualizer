package io.github.eolivelli.nb5visualizer.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import io.github.eolivelli.nb5visualizer.Nb5Visualizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen terminal UI: browse to one metrics directory or zip (or two, for
 * a comparison), choose the output file, and render the report. Runs in any
 * ANSI terminal; without one (e.g. double-clicked from a file manager) Lanterna
 * falls back to a Swing terminal window.
 */
final class VisualizerTui {

    private final TextBox runA = pathBox();
    private final TextBox runB = pathBox();
    private final TextBox output = pathBox();
    private final TextBox title = pathBox();

    void run() throws IOException {
        output.setText("nb5-report.html");
        try (Screen screen = new DefaultTerminalFactory().createScreen()) {
            screen.startScreen();
            WindowBasedTextGUI gui = new MultiWindowTextGUI(screen);
            gui.addWindowAndWait(buildWindow(gui));
        }
    }

    private Window buildWindow(WindowBasedTextGUI gui) {
        BasicWindow window = new BasicWindow("NoSQLBench 5 Visualizer");
        window.setHints(List.of(Window.Hint.CENTERED));

        Panel form = new Panel(new GridLayout(3));

        form.addComponent(new Label("Run A"));
        form.addComponent(runA);
        form.addComponent(browseButton(gui, runA, "Pick run A (directory or zip)"));

        form.addComponent(new Label("Run B"));
        form.addComponent(runB);
        form.addComponent(browseButton(gui, runB, "Pick run B (optional, for comparison)"));

        form.addComponent(new Label("Output"));
        form.addComponent(output);
        form.addComponent(new EmptySpace());

        form.addComponent(new Label("Title"));
        form.addComponent(title);
        form.addComponent(new EmptySpace());

        form.addComponent(new EmptySpace(new TerminalSize(0, 1)),
                GridLayout.createHorizontallyFilledLayoutData(3));

        Panel actions = new Panel(new GridLayout(2));
        actions.addComponent(new Button("Generate report", () -> generate(gui)));
        actions.addComponent(new Button("Quit", window::close));
        form.addComponent(actions, GridLayout.createHorizontallyFilledLayoutData(3));

        Panel root = new Panel();
        root.addComponent(new Label("Pick one run for a report, two runs for a comparison."));
        root.addComponent(new EmptySpace(new TerminalSize(0, 1)));
        root.addComponent(form);
        window.setComponent(root);
        return window;
    }

    private Button browseButton(WindowBasedTextGUI gui, TextBox target, String dialogTitle) {
        return new Button("Browse…", () -> {
            Path picked = PathPickerDialog.show(gui, dialogTitle, startDirFor(target));
            if (picked != null) {
                target.setText(picked.toString());
            }
        });
    }

    /** Starts browsing from the box's current path (or its parent), else the cwd. */
    private static Path startDirFor(TextBox target) {
        String text = target.getText().trim();
        if (!text.isEmpty()) {
            Path p = Paths.get(text).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
            if (p.getParent() != null && Files.isDirectory(p.getParent())) {
                return p.getParent();
            }
        }
        return Paths.get("").toAbsolutePath();
    }

    private void generate(WindowBasedTextGUI gui) {
        List<Path> inputs = new ArrayList<>();
        for (TextBox box : List.of(runA, runB)) {
            String text = box.getText().trim();
            if (text.isEmpty()) {
                continue;
            }
            Path p = Paths.get(text);
            if (!Files.exists(p)) {
                error(gui, "Input does not exist:\n" + p);
                return;
            }
            inputs.add(p);
        }
        if (inputs.isEmpty()) {
            error(gui, "Pick at least Run A (a metrics directory or .zip).");
            return;
        }
        String outText = output.getText().trim();
        if (outText.isEmpty()) {
            error(gui, "Output file name must not be empty.");
            return;
        }
        String titleText = title.getText().trim();

        Nb5Visualizer.Result result;
        try {
            result = new Nb5Visualizer().generate(inputs, Paths.get(outText),
                    titleText.isEmpty() ? null : titleText, null);
        } catch (IOException | IllegalArgumentException e) {
            error(gui, e.getMessage() != null ? e.getMessage() : e.toString());
            return;
        }

        StringBuilder message = new StringBuilder("Report written to\n" + result.output());
        for (String note : result.notes()) {
            message.append("\n\n").append(note);
        }
        for (String warning : result.warnings()) {
            message.append("\n\nWarning: ").append(warning);
        }
        message.append("\n\nOpen it in a browser now?");
        MessageDialogButton choice = MessageDialog.showMessageDialog(gui, "Done",
                message.toString(), MessageDialogButton.Yes, MessageDialogButton.No);
        if (choice == MessageDialogButton.Yes) {
            openInBrowser(gui, result.output());
        }
    }

    /** Best-effort: hand the report to the desktop browser; report failure, never crash. */
    private static void openInBrowser(WindowBasedTextGUI gui, Path report) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            String opener = os.contains("mac") ? "open"
                    : os.contains("win") ? "rundll32"
                    : "xdg-open";
            List<String> command = opener.equals("rundll32")
                    ? List.of("rundll32", "url.dll,FileProtocolHandler", report.toString())
                    : List.of(opener, report.toString());
            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            error(gui, "Could not open a browser: " + e.getMessage()
                    + "\nOpen the file manually:\n" + report);
        }
    }

    private static void error(WindowBasedTextGUI gui, String message) {
        MessageDialog.showMessageDialog(gui, "Error", message, MessageDialogButton.OK);
    }

    private static TextBox pathBox() {
        return new TextBox(new TerminalSize(48, 1));
    }
}
