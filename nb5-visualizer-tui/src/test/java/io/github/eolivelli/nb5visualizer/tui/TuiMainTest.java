package io.github.eolivelli.nb5visualizer.tui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiMainTest {

    /** With arguments the TUI jar must behave as the plain CLI, not open a UI. */
    @Test
    void argsAreDelegatedToTheCli() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, "UTF-8"));
        try {
            TuiMain.main(new String[]{"--help"});
        } finally {
            System.setOut(originalOut);
        }
        String out = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(out.contains("Usage: java -jar nb5-visualizer"), "expected CLI usage, got:\n" + out);
    }
}
