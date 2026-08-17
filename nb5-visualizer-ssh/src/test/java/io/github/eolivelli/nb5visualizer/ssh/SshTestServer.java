package io.github.eolivelli.nb5visualizer.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

/**
 * In-JVM SFTP-enabled SSH server for tests: ephemeral port, generated host
 * key, publickey auth for {@code testuser} with the keypair written (as
 * unencrypted PKCS#8 PEM) to {@link #clientKeyFile}, and the session chrooted
 * to a fixture directory.
 */
final class SshTestServer implements AutoCloseable {

    static final String USER = "testuser";

    final Path clientKeyFile;
    private final SshServer sshd;

    SshTestServer(Path fixtureRoot, Path workDir, Path hostKeyFile, int fixedPort)
            throws IOException, NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair clientKeyPair = generator.generateKeyPair();
        clientKeyFile = workDir.resolve("test_key");
        Files.write(clientKeyFile, pkcs8Pem(clientKeyPair).getBytes(StandardCharsets.US_ASCII));

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(fixedPort);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyFile));
        sshd.setPublickeyAuthenticator((username, key, session) ->
                USER.equals(username) && KeyUtils.compareKeys(key, clientKeyPair.getPublic()));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(fixtureRoot));
        sshd.start();
    }

    int port() {
        return sshd.getPort();
    }

    SshTarget target() {
        return SshTarget.parse(USER + "@127.0.0.1:" + port());
    }

    private static String pkcs8Pem(KeyPair keyPair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    @Override
    public void close() throws IOException {
        sshd.stop(true);
    }
}
