package io.github.eolivelli.nb5visualizer.tui.ssh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
