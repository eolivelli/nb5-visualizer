package io.github.eolivelli.nb5visualizer.ssh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.Set;

/**
 * The launch-time SSH flow shared by the CLI and TUI entry points: open the
 * one connection with console prompts, resolve the base directory, and fetch
 * remote CLI inputs to a local temp directory.
 */
public final class SshLauncher {

    /** Connects using console prompts and the user's {@code ~/.ssh/known_hosts}. */
    public static SshConnection connect(SshArgs ssh)
            throws IOException, GeneralSecurityException {
        if (ssh.verbose) {
            Verbose.enable();
        }
        Prompts.ConsolePrompts prompts = new Prompts.ConsolePrompts();
        Path knownHosts = Paths.get(System.getProperty("user.home"), ".ssh", "known_hosts");
        return SshConnection.open(SshTarget.parse(ssh.spec), ssh.identity, knownHosts,
                prompts, prompts);
    }

    /** The directory remote paths start at: {@code --remote-dir} (against home) or home. */
    public static Path resolveBaseDir(SshConnection conn, String remoteDir) throws IOException {
        if (remoteDir == null) {
            return conn.home();
        }
        Path base = conn.home().resolve(remoteDir).normalize();
        Verbose.log("checking --remote-dir " + base + "…");
        if (!Files.isDirectory(base)) {
            throw new IOException("--remote-dir is not a directory on " + conn.description()
                    + ": " + base);
        }
        return base;
    }

    /** A temp download root, removed again by a shutdown hook. */
    public static Path createTempRoot() throws IOException {
        Path tempRoot = Files.createTempDirectory("nb5-ssh-");
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> RemoteDownloader.deleteRecursively(tempRoot)));
        return tempRoot;
    }

    /**
     * Replaces every positional (remote) input in the CLI args with the local
     * path of its downloaded copy. Which options take a value mirrors the
     * option table of the core Main.run; keep the two in sync.
     */
    public static String[] downloadInputs(String[] args, SshConnection conn, Path baseDir,
                                          Path tempRoot) throws IOException {
        Set<String> valueOptions = Set.of("-o", "--output", "--title", "--labels");
        String[] rewritten = args.clone();
        int inputIndex = 0;
        for (int i = 0; i < rewritten.length; i++) {
            if (valueOptions.contains(rewritten[i])) {
                i++;
            } else if (!rewritten[i].startsWith("-")) {
                Path remote = baseDir.resolve(rewritten[i]);
                Verbose.log("checking remote input " + remote + "…");
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

    public static boolean wantsHelp(String[] args) {
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    public static void printUsage() {
        System.out.println();
        System.out.println("Remote input over SSH:");
        System.out.println("  --ssh user@host[:port]   connect once at launch; input paths (and the");
        System.out.println("                           TUI file browser) then refer to the remote");
        System.out.println("                           machine. Relative paths start at the remote home.");
        System.out.println("                           Inputs are fetched to a local temp dir; the");
        System.out.println("                           report is always written locally.");
        System.out.println("  -i, --identity <file>    private key (default: ~/.ssh/id_ed25519,");
        System.out.println("                           id_rsa or id_ecdsa). Auth is publickey-only.");
        System.out.println("  --remote-dir <path>      initial directory on the remote machine: the");
        System.out.println("                           file browser starts there and relative input");
        System.out.println("                           paths resolve against it (default: remote home;");
        System.out.println("                           relative values resolve against the home).");
        System.out.println("  -v, --verbose            log SSH progress to stderr (connecting, auth,");
        System.out.println("                           per-file downloads with sizes and timings). In");
        System.out.println("                           the TUI it covers the connect phase only.");
        System.out.println("  Host keys are checked against ~/.ssh/known_hosts; unknown hosts are");
        System.out.println("  confirmed on the console and remembered, changed keys are rejected.");
    }

    private SshLauncher() {
    }
}
