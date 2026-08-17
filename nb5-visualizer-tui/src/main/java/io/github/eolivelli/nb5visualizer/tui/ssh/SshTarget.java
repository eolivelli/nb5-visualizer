package io.github.eolivelli.nb5visualizer.tui.ssh;

/** Parsed {@code --ssh} target: {@code user@host[:port]}. */
public final class SshTarget {

    public final String user;
    public final String host;
    public final int port;

    private SshTarget(String user, String host, int port) {
        this.user = user;
        this.host = host;
        this.port = port;
    }

    public static SshTarget parse(String spec) {
        int at = spec.lastIndexOf('@');
        if (at <= 0 || at == spec.length() - 1) {
            throw new IllegalArgumentException(
                    "--ssh expects user@host[:port], got: " + spec);
        }
        String user = spec.substring(0, at);
        String hostPort = spec.substring(at + 1);
        String host = hostPort;
        int port = 22;
        if (hostPort.startsWith("[")) {
            // bracketed IPv6: [::1] or [::1]:2222
            int close = hostPort.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("Unclosed '[' in --ssh host: " + spec);
            }
            host = hostPort.substring(1, close);
            String rest = hostPort.substring(close + 1);
            if (rest.startsWith(":")) {
                port = parsePort(rest.substring(1), spec);
            } else if (!rest.isEmpty()) {
                throw new IllegalArgumentException("Malformed --ssh host: " + spec);
            }
        } else {
            int colon = hostPort.indexOf(':');
            if (colon >= 0) {
                if (hostPort.indexOf(':', colon + 1) >= 0) {
                    throw new IllegalArgumentException("For IPv6 addresses use [addr]:port in --ssh, got: " + spec);
                }
                host = hostPort.substring(0, colon);
                port = parsePort(hostPort.substring(colon + 1), spec);
            }
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Empty host in --ssh " + spec);
        }
        return new SshTarget(user, host, port);
    }

    private static int parsePort(String s, String spec) {
        try {
            int port = Integer.parseInt(s);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port in --ssh " + spec);
        }
    }

    @Override
    public String toString() {
        return user + "@" + host + (port == 22 ? "" : ":" + port);
    }
}
