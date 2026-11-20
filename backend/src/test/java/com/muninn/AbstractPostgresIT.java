package com.muninn;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Postgres container, test-only master key and audio data dir for Spring integration tests. The data
 * dir is one temp dir per JVM (deleted on exit) rather than a per-class JUnit {@code @TempDir}, so all ITs
 * keep sharing one cached Spring context; tests isolate themselves by using fresh user ids.
 */
public abstract class AbstractPostgresIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final Path MASTER_KEY_FILE = createMasterKeyFile();
    protected static final Path DATA_DIR = createDataDir();
    /** Small upload cap so the over-limit path is cheap to exercise. */
    protected static final long MAX_UPLOAD_BYTES = 1024 * 1024;

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("MUNINN_MASTER_KEY_FILE", () -> MASTER_KEY_FILE.toString());
        registry.add("muninn.data-dir", () -> DATA_DIR.toString());
        registry.add("muninn.max-upload-bytes", () -> Long.toString(MAX_UPLOAD_BYTES));
        // Tests drive JobWorker.runOnce() themselves; a background poller would race them.
        registry.add("muninn.worker.enabled", () -> "false");
    }

    private static Path createDataDir() {
        try {
            Path dir = Files.createTempDirectory("muninn-test-data");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(dir)));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort at JVM exit
        }
    }

    private static Path createMasterKeyFile() {
        try {
            Path file = Files.createTempFile("muninn-test-master-key", ".bin");
            file.toFile().deleteOnExit();
            Files.write(file, new byte[32]);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
