package io.github.eolivelli.nb5visualizer.ssh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Copies a remote input (a metrics directory tree, or a single .zip file) to a
 * local temp directory so the unchanged local pipeline can render it. Remote
 * zips are always downloaded rather than opened in place: the JDK 11 zip
 * filesystem only opens archives on the default filesystem.
 */
public final class RemoteDownloader {

    /**
     * Downloads {@code remote} under {@code localRoot/<index>/<basename>} and
     * returns that local path. The per-input {@code index} subdirectory avoids
     * collisions between two runs with the same basename, while keeping the
     * basename itself — the report's default labels derive from it.
     */
    public static Path download(Path remote, Path localRoot, int index) throws IOException {
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
