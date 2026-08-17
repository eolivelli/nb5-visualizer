package io.github.eolivelli.nb5visualizer.tui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathPickerDialogTest {

    @Test
    void listsDirectoriesThenZipsSortedAndSkipsOtherFiles(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("beta-run"));
        Files.createDirectory(dir.resolve("Alpha-run"));
        Files.createFile(dir.resolve("zzz.zip"));
        Files.createFile(dir.resolve("AAA.ZIP"));
        Files.createFile(dir.resolve("report.html"));
        Files.createFile(dir.resolve("notes.txt"));
        Files.createDirectory(dir.resolve(".hidden"));
        Files.createFile(dir.resolve(".secret.zip"));

        List<String> names = PathPickerDialog.listEntries(dir).stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toList());

        assertEquals(List.of("Alpha-run", "beta-run", "AAA.ZIP", "zzz.zip"), names);
    }

    @Test
    void emptyDirectoryListsNothing(@TempDir Path dir) throws IOException {
        assertEquals(List.of(), PathPickerDialog.listEntries(dir));
    }
}
