package io.github.eolivelli.nb5visualizer.ssh;

import java.io.Console;
import java.io.IOException;

/**
 * The two questions a connection may need to ask the user. Interfaces so tests
 * can script the answers; the real implementation talks to the console, which
 * works because connecting happens before the Lanterna screen starts.
 */
public interface Prompts {

    interface HostKeyPrompt {
        /** True to trust (and remember) an unknown host key. */
        boolean acceptUnknownHost(String hostAndPort, String keyType, String fingerprint);
    }

    interface PassphrasePrompt {
        /** The passphrase for an encrypted private key, or null if aborted. */
        char[] passphraseFor(String resourceName) throws IOException;
    }

    final class ConsolePrompts implements HostKeyPrompt, PassphrasePrompt {

        @Override
        public boolean acceptUnknownHost(String hostAndPort, String keyType, String fingerprint) {
            Console console = System.console();
            if (console == null) {
                System.err.println("Unknown host " + hostAndPort + " (" + keyType + " "
                        + fingerprint + ") and no console to confirm on; refusing.");
                return false;
            }
            console.printf("The authenticity of host '%s' can't be established.%n", hostAndPort);
            console.printf("%s key fingerprint is %s.%n", keyType, fingerprint);
            String answer = console.readLine("Are you sure you want to continue connecting (yes/no)? ");
            return answer != null
                    && ("yes".equalsIgnoreCase(answer.trim()) || "y".equalsIgnoreCase(answer.trim()));
        }

        @Override
        public char[] passphraseFor(String resourceName) throws IOException {
            Console console = System.console();
            if (console == null) {
                throw new IOException("Key " + resourceName
                        + " is passphrase-protected but there is no console to prompt on;"
                        + " run from a terminal.");
            }
            return console.readPassword("Enter passphrase for key '%s': ", resourceName);
        }
    }
}
