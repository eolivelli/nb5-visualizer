package io.github.eolivelli.nb5visualizer.tui;

import io.github.eolivelli.nb5visualizer.Main;
import io.github.eolivelli.nb5visualizer.ssh.SshArgs;
import io.github.eolivelli.nb5visualizer.ssh.SshConnection;
import io.github.eolivelli.nb5visualizer.ssh.SshLauncher;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Entry point of the self-contained TUI jar.
 *
 * <p>With no arguments it opens the interactive terminal UI; with arguments it
 * behaves exactly like the core CLI. Adding {@code --ssh user@host[:port]}
 * (plus optional {@code -i <keyfile>} and {@code --remote-dir <path>}) makes
 * either mode read its inputs from a remote machine: the connection is opened
 * once at launch, inputs are fetched to a local temp directory, and the report
 * is written locally.
 *
 * <pre>
 * java -jar nb5-visualizer-tui.jar                          # interactive, local
 * java -jar nb5-visualizer-tui.jar metrics-dir ...          # plain CLI
 * java -jar nb5-visualizer-tui.jar --ssh me@bench           # interactive, remote files
 * java -jar nb5-visualizer-tui.jar --ssh me@bench out ...   # CLI, remote inputs
 * </pre>
 */
public final class TuiMain {

    public static void main(String[] args) throws Exception {
        SshArgs ssh;
        try {
            ssh = SshArgs.extract(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            SshLauncher.printUsage();
            System.exit(2);
            return;
        }
        if (ssh.spec == null) {
            if (ssh.rest.length > 0) {
                Main.main(ssh.rest);
                if (SshLauncher.wantsHelp(ssh.rest)) {
                    SshLauncher.printUsage();
                }
                return;
            }
            new VisualizerTui().run();
            return;
        }
        if (SshLauncher.wantsHelp(ssh.rest)) {
            Main.main(ssh.rest);
            SshLauncher.printUsage();
            return;
        }

        try (SshConnection conn = SshLauncher.connect(ssh)) {
            Path baseDir = SshLauncher.resolveBaseDir(conn, ssh.remoteDir);
            Path tempRoot = SshLauncher.createTempRoot();
            if (ssh.rest.length > 0) {
                Main.main(SshLauncher.downloadInputs(ssh.rest, conn, baseDir, tempRoot));
            } else {
                new VisualizerTui(conn, baseDir, tempRoot).run();
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(e.getMessage() != null ? e.getMessage() : e.toString());
            System.exit(1);
        }
    }

    private TuiMain() {
    }
}
