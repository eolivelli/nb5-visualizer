package io.github.eolivelli.nb5visualizer.ssh;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshConnectionTest {

    private static final Prompts.HostKeyPrompt ACCEPT = (host, type, fingerprint) -> true;
    private static final Prompts.HostKeyPrompt MUST_NOT_ASK = (host, type, fingerprint) -> {
        throw new AssertionError("known host should not be confirmed again");
    };
    private static final Prompts.PassphrasePrompt NO_PASSPHRASE = name -> {
        throw new AssertionError("test key is not encrypted");
    };

    @Test
    void connectBrowseAndDownload(@TempDir Path fixture, @TempDir Path work) throws Exception {
        Files.createDirectories(fixture.resolve("run-b/csv"));
        Files.createDirectories(fixture.resolve("run-a"));
        Files.write(fixture.resolve("run-b/csv/metric.csv"),
                "t,count\n1,2\n".getBytes(StandardCharsets.UTF_8));
        Files.write(fixture.resolve("archive.zip"), new byte[]{1, 2, 3, 4});
        Files.write(fixture.resolve("ignored.txt"), new byte[]{9});

        Path knownHosts = work.resolve("known_hosts");
        try (SshTestServer server = new SshTestServer(fixture, work, work.resolve("hostkey.ser"), 0);
             SshConnection conn = SshConnection.open(server.target(), server.clientKeyFile,
                     knownHosts, ACCEPT, NO_PASSPHRASE)) {

            // remote browsing uses the plain java.nio Files API on SftpPaths —
            // the same calls the TUI file browser makes
            List<String> names;
            try (var listing = Files.list(conn.home())) {
                names = listing.map(p -> p.getFileName().toString())
                        .sorted()
                        .collect(Collectors.toList());
            }
            assertEquals(List.of("archive.zip", "ignored.txt", "run-a", "run-b"), names);
            assertTrue(Files.isDirectory(conn.home().resolve("run-a")));
            assertFalse(Files.isDirectory(conn.home().resolve("archive.zip")));

            // a subdirectory as the starting point (the --remote-dir case)
            Path subdir = SshLauncher.resolveBaseDir(conn, "run-b");
            assertTrue(Files.isDirectory(subdir));
            assertThrows(IOException.class, () -> SshLauncher.resolveBaseDir(conn, "nope"));

            // recursive dir download preserves tree and bytes
            Path localRoot = work.resolve("downloads");
            Files.createDirectories(localRoot);
            Path dir = RemoteDownloader.download(
                    conn.fileSystem().getPath("/run-b"), localRoot, 0);
            assertEquals("run-b", dir.getFileName().toString());
            assertArrayEquals(Files.readAllBytes(fixture.resolve("run-b/csv/metric.csv")),
                    Files.readAllBytes(dir.resolve("csv/metric.csv")));

            // single zip download
            Path zip = RemoteDownloader.download(
                    conn.fileSystem().getPath("/archive.zip"), localRoot, 1);
            assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(zip));
        }
    }

    @Test
    void acceptNewPersistsAndChangedKeyFails(@TempDir Path fixture, @TempDir Path work)
            throws Exception {
        Path knownHosts = work.resolve("known_hosts");
        int port;
        try (SshTestServer server = new SshTestServer(fixture, work, work.resolve("hostkey1.ser"), 0)) {
            port = server.port();
            try (SshConnection conn = SshConnection.open(server.target(), server.clientKeyFile,
                    knownHosts, ACCEPT, NO_PASSPHRASE)) {
                assertTrue(Files.size(knownHosts) > 0, "accepted key must be persisted");
            }
            // second connection: key is known, the prompt must not fire
            try (SshConnection conn = SshConnection.open(server.target(), server.clientKeyFile,
                    knownHosts, MUST_NOT_ASK, NO_PASSPHRASE)) {
                assertTrue(Files.isDirectory(conn.home()));
            }
        }
        // same host:port, different host key -> hard failure, never a prompt
        try (SshTestServer changed = new SshTestServer(fixture, work, work.resolve("hostkey2.ser"), port)) {
            IOException e = assertThrows(IOException.class,
                    () -> SshConnection.open(changed.target(), changed.clientKeyFile,
                            knownHosts, ACCEPT, NO_PASSPHRASE).close());
            assertTrue(e.getMessage().contains("IDENTIFICATION HAS CHANGED"),
                    "unexpected error: " + e.getMessage());
        }
    }

    @Test
    void decliningUnknownHostFails(@TempDir Path fixture, @TempDir Path work) throws Exception {
        Path knownHosts = work.resolve("known_hosts");
        try (SshTestServer server = new SshTestServer(fixture, work, work.resolve("hostkey.ser"), 0)) {
            assertThrows(IOException.class,
                    () -> SshConnection.open(server.target(), server.clientKeyFile,
                            knownHosts, (host, type, fingerprint) -> false, NO_PASSPHRASE).close());
            assertFalse(Files.exists(knownHosts) && Files.size(knownHosts) > 0,
                    "declined key must not be persisted");
        }
    }
}
