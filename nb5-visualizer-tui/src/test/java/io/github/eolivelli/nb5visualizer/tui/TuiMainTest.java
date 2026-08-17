package io.github.eolivelli.nb5visualizer.tui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(out.contains("--ssh user@host"), "expected SSH usage block, got:\n" + out);
    }

    @Test
    void sshFlagsAreExtractedAndTheRestKeptInOrder() {
        TuiMain.SshArgs ssh = TuiMain.SshArgs.extract(new String[]{
                "run-a", "--ssh", "me@bench:2222", "-o", "out.html", "-i", "/keys/k",
                "--remote-dir", "bench/results", "run-b"});
        assertEquals("me@bench:2222", ssh.spec);
        assertEquals(Paths.get("/keys/k"), ssh.identity);
        assertEquals("bench/results", ssh.remoteDir);
        assertArrayEquals(new String[]{"run-a", "-o", "out.html", "run-b"}, ssh.rest);
    }

    @Test
    void noSshFlagsMeansUntouchedArgs() {
        TuiMain.SshArgs ssh = TuiMain.SshArgs.extract(new String[]{"run-a", "-o", "x.html"});
        assertNull(ssh.spec);
        assertArrayEquals(new String[]{"run-a", "-o", "x.html"}, ssh.rest);
    }

    @Test
    void identityWithoutSshIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TuiMain.SshArgs.extract(new String[]{"-i", "/keys/k", "run-a"}));
        assertThrows(IllegalArgumentException.class,
                () -> TuiMain.SshArgs.extract(new String[]{"--remote-dir", "d", "run-a"}));
        assertThrows(IllegalArgumentException.class,
                () -> TuiMain.SshArgs.extract(new String[]{"--ssh"}));
    }
}
