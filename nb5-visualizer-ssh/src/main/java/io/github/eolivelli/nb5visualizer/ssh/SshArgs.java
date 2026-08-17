package io.github.eolivelli.nb5visualizer.ssh;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code --ssh}/{@code -i}/{@code --remote-dir} pulled out of an argument
 * list; every other argument is kept, untouched and in order, in {@link #rest}.
 */
public final class SshArgs {

    public final String spec;       // user@host[:port], null when --ssh absent
    public final Path identity;     // -i/--identity, or null
    public final String remoteDir;  // --remote-dir, or null
    public final String[] rest;

    private SshArgs(String spec, Path identity, String remoteDir, String[] rest) {
        this.spec = spec;
        this.identity = identity;
        this.remoteDir = remoteDir;
        this.rest = rest;
    }

    public static SshArgs extract(String[] args) {
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
