package io.github.eolivelli.nb5visualizer.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;
import org.apache.sshd.sftp.client.fs.SftpFileSystemProvider;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one SSH connection of a run: client, authenticated session, and an SFTP
 * NIO filesystem bound to that session, so remote files are plain
 * {@link java.nio.file.Path}s. Closing this closes everything.
 */
public final class SshConnection implements AutoCloseable {

    private static final List<String> DEFAULT_KEYS = List.of("id_ed25519", "id_rsa", "id_ecdsa");

    private final SshClient client;
    private final SftpFileSystem fileSystem;
    private final String description;
    private volatile boolean closed;

    private SshConnection(SshClient client, SftpFileSystem fileSystem, String description) {
        this.client = client;
        this.fileSystem = fileSystem;
        this.description = description;
    }

    /**
     * Connects and authenticates with the given identity file (or the default
     * {@code ~/.ssh} keys), verifying the host against {@code knownHostsFile}
     * with accept-new semantics: unknown hosts are confirmed via
     * {@code hostPrompt} and persisted; a changed key always fails.
     */
    public static SshConnection open(SshTarget target, Path identityFile, Path knownHostsFile,
                                     Prompts.HostKeyPrompt hostPrompt,
                                     Prompts.PassphrasePrompt passphrasePrompt)
            throws IOException, GeneralSecurityException {
        Path keyPath = resolveIdentity(identityFile);

        SshClient client = SshClient.setUpDefaultClient();
        AtomicBoolean keyChanged = new AtomicBoolean();
        client.setServerKeyVerifier(buildVerifier(target, knownHostsFile, hostPrompt, keyChanged));
        client.start();
        ClientSession session = null;
        try {
            Collection<KeyPair> keys = SecurityUtils.getKeyPairResourceParser().loadKeyPairs(
                    null, keyPath, (sessionCtx, resource, retryIndex) -> {
                        char[] passphrase = passphrasePrompt.passphraseFor(resource.getName());
                        if (passphrase == null) {
                            throw new IOException("Passphrase entry aborted");
                        }
                        return new String(passphrase);
                    });
            if (keys.isEmpty()) {
                throw new IOException("No usable key found in " + keyPath);
            }

            session = client.connect(target.user, target.host, target.port)
                    .verify(Duration.ofSeconds(15))
                    .getSession();
            keys.forEach(session::addPublicKeyIdentity);
            try {
                session.auth().verify(Duration.ofSeconds(30));
            } catch (IOException e) {
                if (keyChanged.get()) {
                    throw new IOException("REMOTE HOST IDENTIFICATION HAS CHANGED for " + target
                            + " — refusing to connect. If the host key change is expected, remove"
                            + " the old entry from " + knownHostsFile + " and retry.", e);
                }
                throw new IOException("Authentication as " + target + " failed with key "
                        + keyPath + ": " + e.getMessage(), e);
            }
            SftpFileSystem fs = new SftpFileSystemProvider(client).newFileSystem(session);
            return new SshConnection(client, fs, target.toString());
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            if (session != null) {
                session.close(false);
            }
            client.stop();
            throw e;
        }
    }

    private static ServerKeyVerifier buildVerifier(SshTarget target, Path knownHostsFile,
                                                   Prompts.HostKeyPrompt hostPrompt,
                                                   AtomicBoolean keyChanged) throws IOException {
        Path sshDir = knownHostsFile.toAbsolutePath().getParent();
        if (sshDir != null && !Files.isDirectory(sshDir)) {
            try {
                Files.createDirectories(sshDir,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                Files.createDirectories(sshDir);
            }
        }
        // Unknown host -> ask the prompt; on yes Mina appends the entry to the
        // file itself. No ModifiedServerKeyAcceptor is set, so a changed key is
        // always rejected — the acceptor below only records it for the error text.
        ServerKeyVerifier unknownHostDelegate = (session, remoteAddress, serverKey) ->
                hostPrompt.acceptUnknownHost(target.host + ":" + target.port,
                        KeyUtils.getKeyType(serverKey),
                        KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey));
        KnownHostsServerKeyVerifier verifier =
                new KnownHostsServerKeyVerifier(unknownHostDelegate, knownHostsFile);
        verifier.setModifiedServerKeyAcceptor((session, remoteAddress, entry, expected, actual) -> {
            keyChanged.set(true);
            return false;
        });
        return verifier;
    }

    private static Path resolveIdentity(Path identityFile) throws IOException {
        if (identityFile != null) {
            if (!Files.isRegularFile(identityFile)) {
                throw new IOException("Identity file not found: " + identityFile);
            }
            return identityFile;
        }
        Path sshDir = Paths.get(System.getProperty("user.home"), ".ssh");
        for (String name : DEFAULT_KEYS) {
            Path candidate = sshDir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException("No private key found (tried " + String.join(", ", DEFAULT_KEYS)
                + " in " + sshDir + "); pass one with -i <keyfile>");
    }

    /** The remote filesystem; paths from it work with the java.nio Files API. */
    public FileSystem fileSystem() {
        return fileSystem;
    }

    /** The remote home directory. */
    public Path home() throws IOException {
        return fileSystem.getDefaultDir();
    }

    /** "user@host[:port]", for window titles and messages. */
    public String description() {
        return description;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            fileSystem.close();   // owns and closes the session
        } catch (IOException ignored) {
            // best effort — we are exiting
        }
        client.stop();
    }
}
