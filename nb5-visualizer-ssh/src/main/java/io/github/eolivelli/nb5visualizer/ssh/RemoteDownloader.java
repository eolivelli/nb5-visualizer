package io.github.eolivelli.nb5visualizer.ssh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Copies a remote input (a metrics directory tree, or a single .zip file) to a
 * local temp directory so the unchanged local pipeline can render it.
 *
 * <p>Directories are fetched as one compressed archive: {@code tar -czf} runs
 * on the remote host over the SSH exec channel, the single .tgz is downloaded,
 * extracted with the local {@code tar}, and the remote temp file is removed —
 * far fewer round trips than per-file SFTP, which matters on high-latency
 * links. When any of that is unavailable (no exec channel, no tar on either
 * side) it falls back to the per-file SFTP copy.
 *
 * <p>Remote zips are always downloaded as-is rather than opened in place: the
 * JDK 11 zip filesystem only opens archives on the default filesystem.
 */
public final class RemoteDownloader {

    /**
     * Downloads {@code remote} under {@code localRoot/<index>/<basename>} and
     * returns that local path. The per-input {@code index} subdirectory avoids
     * collisions between two runs with the same basename, while keeping the
     * basename itself — the report's default labels derive from it.
     */
    public static Path download(SshConnection conn, Path remote, Path localRoot, int index)
            throws IOException {
        Path name = remote.getFileName();
        if (name == null) {
            throw new IOException("Cannot download filesystem root " + remote);
        }
        Path target = localRoot.resolve(String.valueOf(index)).resolve(name.toString());
        Files.createDirectories(target.getParent());
        long startedAt = System.nanoTime();
        if (!Files.isDirectory(remote)) {
            if (Verbose.isEnabled()) {
                Verbose.log("fetching file " + remote + " (" + Files.size(remote) + " bytes)");
            }
            Files.copy(remote, target, StandardCopyOption.REPLACE_EXISTING);
            Verbose.log("fetched " + remote.getFileName() + " in " + elapsedMs(startedAt) + " ms");
            return target;
        }
        if (conn != null) {
            try {
                return downloadDirAsArchive(conn, remote, target, startedAt);
            } catch (IOException | RuntimeException e) {
                Verbose.log("archive fast path failed ("
                        + (e.getMessage() != null ? e.getMessage().trim() : e.toString())
                        + "), falling back to per-file copy");
            }
        }
        return downloadDirFileByFile(remote, target, startedAt);
    }

    /**
     * One round trip instead of one per file: remote {@code tar -czf} into a
     * temp archive, single SFTP fetch, local {@code tar -xzf}. The remote temp
     * file is removed in all cases.
     */
    private static Path downloadDirAsArchive(SshConnection conn, Path remote, Path target,
                                             long startedAt) throws IOException {
        Path parent = remote.toAbsolutePath().normalize().getParent();
        String parentDir = parent != null ? parent.toString() : "/";
        String remoteTmp = "/tmp/nb5-visualizer-" + UUID.randomUUID() + ".tgz";
        Path remoteTmpPath = conn.fileSystem().getPath(remoteTmp);
        String command = "tar -czf " + shellQuote(remoteTmp) + " -C " + shellQuote(parentDir)
                + " " + shellQuote(remote.getFileName().toString());
        try {
            Verbose.log("compressing remotely: " + command);
            conn.exec(command);
            long archiveBytes = Files.size(remoteTmpPath);
            Verbose.log("fetching archive " + remoteTmp + " (" + archiveBytes + " bytes)");
            Path localTmp = Files.createTempFile("nb5-ssh-", ".tgz");
            try {
                Files.copy(remoteTmpPath, localTmp, StandardCopyOption.REPLACE_EXISTING);
                Verbose.log("extracting archive locally…");
                extractWithLocalTar(localTmp, target.getParent());
            } finally {
                Files.deleteIfExists(localTmp);
            }
            if (!Files.isDirectory(target)) {
                throw new IOException("archive did not contain " + target.getFileName());
            }
            Verbose.log("downloaded " + remote.getFileName() + " as one archive ("
                    + archiveBytes + " bytes compressed) in " + elapsedMs(startedAt) + " ms -> "
                    + target);
            return target;
        } finally {
            try {
                Files.deleteIfExists(remoteTmpPath);
                Verbose.log("removed remote temp file " + remoteTmp);
            } catch (IOException e) {
                Verbose.log("could not remove remote temp file " + remoteTmp + ": " + e);
            }
        }
    }

    private static void extractWithLocalTar(Path archive, Path intoDir) throws IOException {
        Process tar = new ProcessBuilder("tar", "-xzf", archive.toString(),
                "-C", intoDir.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit;
        try {
            exit = tar.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tar.destroyForcibly();
            throw new IOException("interrupted while extracting " + archive);
        }
        if (exit != 0) {
            throw new IOException("local tar exited with " + exit + ": " + output.trim());
        }
    }

    /** Portable but chatty fallback: one SFTP round trip per file. */
    private static Path downloadDirFileByFile(Path remote, Path target, long startedAt)
            throws IOException {
        Verbose.log("walking remote directory " + remote + "…");
        int files = 0;
        long bytes = 0;
        try (Stream<Path> walk = Files.walk(remote)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                // Resolve element by element through Strings: mixing paths of
                // different filesystem providers throws ProviderMismatchException.
                Path local = target;
                for (Path element : remote.relativize(p)) {
                    String s = element.toString();
                    if (!s.isEmpty()) {
                        local = local.resolve(s);
                    }
                }
                if (Files.isDirectory(p)) {
                    if (!local.equals(target)) {
                        Verbose.log("entering " + remote.relativize(p) + "/");
                    }
                    Files.createDirectories(local);
                } else {
                    if (Verbose.isEnabled()) {
                        Verbose.log("fetching " + remote.relativize(p)
                                + " (" + Files.size(p) + " bytes)");
                    }
                    Files.copy(p, local, StandardCopyOption.REPLACE_EXISTING);
                    files++;
                    bytes += Files.size(local);
                }
            }
        }
        Verbose.log("downloaded " + files + " file(s), " + bytes + " bytes in "
                + elapsedMs(startedAt) + " ms -> " + target);
        return target;
    }

    /** Single-quotes a value for the remote shell ({@code '} becomes {@code '\''}). */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /** Best-effort recursive delete of the temp root, for a shutdown hook. */
    public static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best effort — the OS cleans temp dirs eventually
                }
            });
        } catch (IOException ignored) {
            // already gone
        }
    }

    private RemoteDownloader() {
    }
}
