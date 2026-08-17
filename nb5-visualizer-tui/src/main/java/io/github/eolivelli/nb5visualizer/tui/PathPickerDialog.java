package io.github.eolivelli.nb5visualizer.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.DialogWindow;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Modal directory browser: arrow keys walk the filesystem, Enter descends into
 * a directory or picks a .zip archive, and a button picks the directory
 * currently shown. Returns the chosen path, or null on cancel.
 */
final class PathPickerDialog extends DialogWindow {

    private final Label location = new Label("");
    private final ActionListBox entries = new ActionListBox(new TerminalSize(56, 14));
    private Path currentDir;
    private Path result;

    private PathPickerDialog(String title, Path startDir) {
        super(title);
        this.currentDir = startDir.toAbsolutePath().normalize();

        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(location);
        panel.addComponent(entries);
        panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

        Panel buttons = new Panel(new LinearLayout(Direction.HORIZONTAL));
        buttons.addComponent(new Button("Choose this directory", () -> {
            result = currentDir;
            close();
        }));
        buttons.addComponent(new Button("Cancel", this::close));
        panel.addComponent(buttons, LinearLayout.createLayoutData(LinearLayout.Alignment.End));

        setComponent(panel);
        rebuild();
    }

    /** Shows the dialog and returns the picked directory or zip, or null. */
    static Path show(WindowBasedTextGUI gui, String title, Path startDir) {
        PathPickerDialog dialog = new PathPickerDialog(title, startDir);
        dialog.showDialog(gui);
        return dialog.result;
    }

    private void rebuild() {
        location.setText(abbreviate(currentDir.toString(), 56));
        entries.clearItems();
        Path parent = currentDir.getParent();
        if (parent != null) {
            entries.addItem("../", () -> {
                currentDir = parent;
                rebuild();
            });
        }
        List<Path> listed;
        try {
            listed = listEntries(currentDir);
        } catch (IOException e) {
            MessageDialog.showMessageDialog(getTextGUI(), "Cannot read directory",
                    e.toString(), MessageDialogButton.OK);
            return;
        }
        for (Path entry : listed) {
            String name = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                entries.addItem(name + "/", () -> {
                    currentDir = entry;
                    rebuild();
                });
            } else {
                entries.addItem(name, () -> {
                    result = entry;
                    close();
                });
            }
        }
        entries.setSelectedIndex(0);
    }

    /**
     * Visible entries of a directory: subdirectories first, then .zip files,
     * each sorted case-insensitively; hidden (dot) entries are skipped.
     */
    static List<Path> listEntries(Path dir) throws IOException {
        List<Path> dirs = new ArrayList<>();
        List<Path> zips = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.collect(Collectors.toList())) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    dirs.add(p);
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    zips.add(p);
                }
            }
        }
        Comparator<Path> byName =
                Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT));
        dirs.sort(byName);
        zips.sort(byName);
        dirs.addAll(zips);
        return dirs;
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - max + 1);
    }
}
