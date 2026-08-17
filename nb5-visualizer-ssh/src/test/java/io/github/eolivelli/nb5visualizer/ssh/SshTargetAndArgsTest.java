package io.github.eolivelli.nb5visualizer.ssh;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SshTargetAndArgsTest {

    @Test
    void parsesUserHostAndOptionalPort() {
        SshTarget plain = SshTarget.parse("bench@10.0.0.5");
        assertEquals("bench", plain.user);
        assertEquals("10.0.0.5", plain.host);
        assertEquals(22, plain.port);

        SshTarget withPort = SshTarget.parse("me@example.com:2222");
        assertEquals(2222, withPort.port);

        SshTarget ipv6 = SshTarget.parse("me@[::1]:2200");
        assertEquals("::1", ipv6.host);
        assertEquals(2200, ipv6.port);
    }

    @Test
    void rejectsMalformedSpecs() {
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("hostonly"));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("@host"));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("user@"));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("user@host:notaport"));
        assertThrows(IllegalArgumentException.class, () -> SshTarget.parse("user@::1:22"));
    }

    @Test
    void sshFlagsAreExtractedAndTheRestKeptInOrder() {
        SshArgs ssh = SshArgs.extract(new String[]{
                "run-a", "--ssh", "me@bench:2222", "-o", "out.html", "-i", "/keys/k",
                "--remote-dir", "bench/results", "run-b"});
        assertEquals("me@bench:2222", ssh.spec);
        assertEquals(Paths.get("/keys/k"), ssh.identity);
        assertEquals("bench/results", ssh.remoteDir);
        assertArrayEquals(new String[]{"run-a", "-o", "out.html", "run-b"}, ssh.rest);
    }

    @Test
    void noSshFlagsMeansUntouchedArgs() {
        SshArgs ssh = SshArgs.extract(new String[]{"run-a", "-o", "x.html"});
        assertNull(ssh.spec);
        assertArrayEquals(new String[]{"run-a", "-o", "x.html"}, ssh.rest);
    }

    @Test
    void identityWithoutSshIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SshArgs.extract(new String[]{"-i", "/keys/k", "run-a"}));
        assertThrows(IllegalArgumentException.class,
                () -> SshArgs.extract(new String[]{"--remote-dir", "d", "run-a"}));
        assertThrows(IllegalArgumentException.class,
                () -> SshArgs.extract(new String[]{"--ssh"}));
    }
}
