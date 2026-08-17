package io.github.eolivelli.nb5visualizer.tui;

import io.github.eolivelli.nb5visualizer.Main;
import io.github.eolivelli.nb5visualizer.tui.ssh.Prompts;
import io.github.eolivelli.nb5visualizer.tui.ssh.RemoteDownloader;
import io.github.eolivelli.nb5visualizer.tui.ssh.SshConnection;
import io.github.eolivelli.nb5visualizer.tui.ssh.SshTarget;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Entry point of the self-contained TUI jar.
 *
 * <p>With no arguments it opens the interactive terminal UI; with arguments it
 * behaves exactly like the core CLI. Adding {@code --ssh user@host[:port]}
 * (plus optional {@code -i <keyfile>}) makes either mode read its inputs from
 * a remote machine: the connection is opened once at launch, inputs are
 * fetched to a local temp directory, and the report is written locally.
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
            printSshUsage();
            System.exit(2);
            return;
        }
        if (ssh.spec == null) {
            if (ssh.rest.length > 0) {
                Main.main(ssh.rest);
                if (wantsHelp(ssh.rest)) {
                    printSshUsage();
                }
                return;
            }
            new VisualizerTui().run();
            return;
        }
        if (wantsHelp(ssh.rest)) {
            Main.main(ssh.rest);
            printSshUsage();
            return;
        }

        Prompts.ConsolePrompts prompts = new Prompts.ConsolePrompts();
        Path knownHosts = Paths.get(System.getProperty("user.home"), ".ssh", "known_hosts");
        try (SshConnection conn = SshConnection.open(
                SshTarget.parse(ssh.spec), ssh.identity, knownHosts, prompts, prompts)) {
            Path baseDir = resolveBaseDir(conn, ssh.remoteDir);
            Path tempRoot = Files.createTempDirectory("nb5-ssh-");
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> RemoteDownloader.deleteRecursively(tempRoot)));
            if (ssh.rest.length > 0) {
                Main.main(downloadInputs(ssh.rest, conn, baseDir, tempRoot));
            } else {
                new VisualizerTui(conn, baseDir, tempRoot).run();
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(e.getMessage() != null ? e.getMessage() : e.toString());
            System.exit(1);
        }
    }

    /**
     * Replaces every positional (remote) input in the CLI args with the local
     * path of its downloaded copy. Which options take a value mirrors the
     * option table of the core {@link Main#run}; keep the two in sync.
     */
    private static String[] downloadInputs(String[] args, SshConnection conn, Path baseDir,
                                           Path tempRoot) throws IOException {
        Set<String> valueOptions = Set.of("-o", "--output", "--title", "--labels");
        String[] rewritten = args.clone();
        int inputIndex = 0;
        for (int i = 0; i < rewritten.length; i++) {
            if (valueOptions.contains(rewritten[i])) {
                i++;
            } else if (!rewritten[i].startsWith("-")) {
                Path remote = baseDir.resolve(rewritten[i]);
                if (!Files.exists(remote)) {
                    throw new IOException("Remote input does not exist on " + conn.description()
                            + ": " + remote);
                }
                System.out.println("Downloading " + conn.description() + ":" + remote + " …");
                rewritten[i] = RemoteDownloader.download(remote, tempRoot, inputIndex++).toString();
            }
        }
        return rewritten;
    }

    /** The directory remote paths start at: {@code --remote-dir} (against home) or home. */
    private static Path resolveBaseDir(SshConnection conn, String remoteDir) throws IOException {
        if (remoteDir == null) {
            return conn.home();
        }
        Path base = conn.home().resolve(remoteDir).normalize();
        if (!Files.isDirectory(base)) {
            throw new IOException("--remote-dir is not a directory on " + conn.description()
                    + ": " + base);
        }
        return base;
    }

    private static boolean wantsHelp(String[] args) {
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void printSshUsage() {
        System.out.println();
        System.out.println("Remote input over SSH (this jar only):");
        System.out.println("  --ssh user@host[:port]   connect once at launch; input paths (CLI) and");
        System.out.println("                           the file browser (TUI) then refer to the remote");
        System.out.println("                           machine. Relative paths start at the remote home.");
        System.out.println("                           Inputs are fetched to a local temp dir; the");
        System.out.println("                           report is always written locally.");
        System.out.println("  -i, --identity <file>    private key (default: ~/.ssh/id_ed25519,");
        System.out.println("                           id_rsa or id_ecdsa). Auth is publickey-only.");
        System.out.println("  --remote-dir <path>      initial directory on the remote machine: the");
        System.out.println("                           file browser starts there and relative input");
        System.out.println("                           paths resolve against it (default: remote home;");
        System.out.println("                           relative values resolve against the home).");
        System.out.println("  Host keys are checked against ~/.ssh/known_hosts; unknown hosts are");
        System.out.println("  confirmed on the console and remembered, changed keys are rejected.");
    }

    /** {@code --ssh}/{@code -i}/{@code --remote-dir} pulled out of the arg list; the rest untouched. */
    static final class SshArgs {
        final String spec;
        final Path identity;
        final String remoteDir;
        final String[] rest;

        private SshArgs(String spec, Path identity, String remoteDir, String[] rest) {
            this.spec = spec;
            this.identity = identity;
            this.remoteDir = remoteDir;
            this.rest = rest;
        }

        static SshArgs extract(String[] args) {
            String spec = null;
            Path identity = null;
            String remoteDir = null;
            List<String> rest = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--ssh":
                        spec = requireValue(args, ++i, "--ssh");
                        break;
                    case "-i":
                    case "--identity":
                        identity = Paths.get(requireValue(args, ++i, args[i - 1]));
                        break;
                    case "--remote-dir":
                        remoteDir = requireValue(args, ++i, "--remote-dir");
                        break;
                    default:
                        rest.add(args[i]);
                }
            }
            if (spec == null && (identity != null || remoteDir != null)) {
                throw new IllegalArgumentException(
                        "-i/--identity and --remote-dir require --ssh user@host[:port]");
            }
            return new SshArgs(spec, identity, remoteDir, rest.toArray(new String[0]));
        }

        private static String requireValue(String[] args, int i, String option) {
            if (i >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[i];
        }
    }

    private TuiMain() {
    }
}
