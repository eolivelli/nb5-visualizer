package io.github.eolivelli.nb5visualizer.cli;

import io.github.eolivelli.nb5visualizer.Main;
import io.github.eolivelli.nb5visualizer.ssh.SshArgs;
import io.github.eolivelli.nb5visualizer.ssh.SshConnection;
import io.github.eolivelli.nb5visualizer.ssh.SshLauncher;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Entry point of the headless CLI jar: the core command line plus the SSH
 * flags, without the terminal UI. Adding {@code --ssh user@host[:port]} (plus
 * optional {@code -i <keyfile>} and {@code --remote-dir <path>}) reads the
 * inputs from a remote machine: the connection is opened once at launch,
 * inputs are fetched to a local temp directory, and the report is written
 * locally.
 *
 * <pre>
 * java -jar nb5-visualizer-cli.jar metrics-dir ...          # plain CLI
 * java -jar nb5-visualizer-cli.jar --ssh me@bench out ...   # remote inputs
 * </pre>
 */
public final class CliMain {

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
        // Main.run (not main) so the SSH usage block still prints before a
        // non-zero exit.
        if (ssh.spec == null || SshLauncher.wantsHelp(ssh.rest) || ssh.rest.length == 0) {
            int exit = Main.run(ssh.rest);
            if (SshLauncher.wantsHelp(ssh.rest) || ssh.rest.length == 0) {
                SshLauncher.printUsage();
            }
            if (exit != 0) {
                System.exit(exit);
            }
            return;
        }

        try (SshConnection conn = SshLauncher.connect(ssh)) {
            Path baseDir = SshLauncher.resolveBaseDir(conn, ssh.remoteDir);
            Path tempRoot = SshLauncher.createTempRoot();
            Main.main(SshLauncher.downloadInputs(ssh.rest, conn, baseDir, tempRoot));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(e.getMessage() != null ? e.getMessage() : e.toString());
            System.exit(1);
        }
    }

    private CliMain() {
    }
}
