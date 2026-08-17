package io.github.eolivelli.nb5visualizer.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static io.github.eolivelli.nb5visualizer.it.Docker.docker;
import static io.github.eolivelli.nb5visualizer.it.Docker.requireSuccess;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the shipped jar actually runs on a plain JDK 11, by executing it
 * inside an eclipse-temurin:11-jre container against the recorded fixture.
 *
 * <p>Enable with: {@code mvn verify -Pdocker-it}.
 */
@EnabledIfSystemProperty(named = "nb5.docker.it", matches = "true")
class Jdk11RuntimeIT {

    @Test
    void jarRunsOnJava11() throws Exception {
        Path projectDir = Paths.get("").toAbsolutePath();
        Path jar = findJar(projectDir.resolve("target"));
        Path outName = Paths.get("target/it-jdk11-report.html");
        Files.deleteIfExists(projectDir.resolve(outName));

        Docker.Result r = docker(300, "run", "--rm",
                "-v", projectDir + ":/w", "-w", "/w",
                "eclipse-temurin:11-jre",
                "java", "-jar", "/w/target/" + jar.getFileName(),
                "src/test/resources/fixtures/run-witherrors",
                "-o", outName.toString(),
                "--title", "jdk11 smoke");
        requireSuccess(r, "jar on JDK 11");
        assertTrue(r.output.contains("Report written"), "unexpected output:\n" + r.output);
        assertTrue(Files.size(projectDir.resolve(outName)) > 10_000, "report was generated");
    }

    private static Path findJar(Path target) throws IOException {
        try (Stream<Path> list = Files.list(target)) {
            return list.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("nb5-visualizer-") && n.endsWith(".jar")
                        && !n.contains("sources") && !n.contains("javadoc");
            }).findFirst().orElseThrow(() ->
                    new AssertionError("No jar in target/ — run this via 'mvn verify'"));
        }
    }
}
