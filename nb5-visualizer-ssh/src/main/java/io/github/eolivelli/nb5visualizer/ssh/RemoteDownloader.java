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
        if (!Files.isDirectory(remote)) {
            Files.copy(remote, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
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
                    Files.createDirectories(local);
                } else {
                    Files.copy(p, local, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return target;
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
