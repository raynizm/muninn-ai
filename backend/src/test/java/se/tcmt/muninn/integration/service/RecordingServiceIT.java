package se.tcmt.muninn.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static se.tcmt.muninn.support.TestData.bytes;
import static se.tcmt.muninn.support.TestData.sha;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.model.ProcessingStatus;
import se.tcmt.muninn.model.Recording;
import se.tcmt.muninn.repository.UserRepository;
import se.tcmt.muninn.service.AudioStore;
import se.tcmt.muninn.service.RecordingService;
import se.tcmt.muninn.support.AbstractPostgresIT;

/** Service-level upload/list/delete against real files and the real Postgres container. */
@SpringBootTest
class RecordingServiceIT extends AbstractPostgresIT {

    private static final String M4A = "audio/mp4";

    @Autowired
    RecordingService recordings;

    @Autowired
    AudioStore audio;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    AppUser ann;
    AppUser bob;

    @BeforeEach
    void reset() {
        jdbc.update("delete from app_user");
        ann = user("ann@acme.com");
        bob = user("bob@acme.com");
    }

    private AppUser user(String email) {
        users.insertIfAbsent(email, email, AppUser.Role.USER, "https://idp.test", "sub-" + email);
        return users.findByEmail(email).orElseThrow();
    }

    private RecordingService.UploadResult upload(AppUser u, UUID id, byte[] data) throws Exception {
        return recordings.upload(u.id(), id, new ByteArrayInputStream(data), sha(data), M4A, null, 1234L, null);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** Every regular file under the user's audio dir, temp files included. */
    private List<Path> filesOf(AppUser u) throws IOException {
        Path dir = DATA_DIR.resolve("audio").resolve(u.id().toString());
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }

    private static HttpStatus statusOf(Throwable t) {
        return HttpStatus.valueOf(((ResponseStatusException) t).getStatusCode().value());
    }

    @Test
    void uploadTwiceSameBytesReturnsSameRecordingAndOneJob() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] data = bytes(4096, 1);

        RecordingService.UploadResult first = upload(ann, id, data);
        assertThat(first.created()).isTrue();
        Path file = audio.path(ann.id(), id);
        assertThat(file).hasBinaryContent(data);
        var modified = Files.getLastModifiedTime(file);

        RecordingService.UploadResult second = upload(ann, id, data);
        assertThat(second.created()).isFalse();
        assertThat(second.recording().id()).isEqualTo(id);
        assertThat(second.recording().createdAt()).isEqualTo(first.recording().createdAt());

        Recording r = second.recording();
        assertThat(r.uploadStatus()).isEqualTo("UPLOADED");
        assertThat(r.processingStatus()).isEqualTo(ProcessingStatus.PENDING);
        assertThat(r.sizeBytes()).isEqualTo(4096L);
        assertThat(r.durationMs()).isEqualTo(1234L);
        assertThat(r.mime()).isEqualTo(M4A);
        assertThat(r.sha256()).isEqualTo(sha(data));

        assertThat(count("select count(*) from recording where id = ?", id)).isEqualTo(1);
        assertThat(count("select count(*) from processing_job where recording_id = ? and state = 'PENDING'", id))
                .isEqualTo(1);
        assertThat(filesOf(ann)).containsExactly(file);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
    }

    @Test
    void uploadSameIdDifferentBytesReturns409AndKeepsOriginal() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] original = bytes(2048, 2);
        upload(ann, id, original);

        assertThatThrownBy(() -> upload(ann, id, bytes(2048, 3)))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT));

        assertThat(audio.path(ann.id(), id)).hasBinaryContent(original);
        assertThat(recordings.get(ann.id(), id).orElseThrow().sha256()).isEqualTo(sha(original));
        assertThat(count("select count(*) from processing_job where recording_id = ?", id))
                .isEqualTo(1);
        assertThat(filesOf(ann)).hasSize(1);
    }

    @Test
    void hashMismatchRejectedAndNoFileLeft() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] data = bytes(2048, 4);

        assertThatThrownBy(() -> recordings.upload(
                        ann.id(), id, new ByteArrayInputStream(data), sha(bytes(2048, 5)), M4A, null, null, null))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));

        assertThat(filesOf(ann)).isEmpty();
        assertThat(count("select count(*) from recording where id = ?", id)).isZero();
        assertThat(count("select count(*) from processing_job where recording_id = ?", id))
                .isZero();
    }

    @Test
    void emptyStreamRejected400() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> upload(ann, id, new byte[0]))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(count("select count(*) from recording where id = ?", id)).isZero();
    }

    @Test
    void streamOverLimitWithoutLengthRejected413AndNoFileLeft() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] data = bytes((int) MAX_UPLOAD_BYTES + 1, 6);
        assertThatThrownBy(() -> upload(ann, id, data))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
        assertThat(filesOf(ann)).isEmpty();
        assertThat(count("select count(*) from recording where id = ?", id)).isZero();
    }

    @Test
    void concurrentIdenticalUploadsCreateOneRowOneJobOneFile() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 10; round++) {
                UUID id = UUID.randomUUID();
                byte[] data = bytes(256 * 1024, 100 + round);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<RecordingService.UploadResult>> results = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    Callable<RecordingService.UploadResult> task = () -> {
                        go.await();
                        return upload(ann, id, data);
                    };
                    results.add(pool.submit(task));
                }
                go.countDown();
                int created = 0;
                for (var f : results) {
                    RecordingService.UploadResult r = f.get();
                    assertThat(r.recording().id()).isEqualTo(id);
                    created += r.created() ? 1 : 0;
                }
                assertThat(created).isEqualTo(1);
                assertThat(count("select count(*) from recording where id = ?", id))
                        .isEqualTo(1);
                assertThat(count("select count(*) from processing_job where recording_id = ?", id))
                        .isEqualTo(1);
                assertThat(audio.path(ann.id(), id)).hasBinaryContent(data);
            }
            assertThat(filesOf(ann)).hasSize(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void getAndDeleteMatchOnOwner() throws Exception {
        UUID id = UUID.randomUUID();
        upload(ann, id, bytes(512, 7));

        assertThat(recordings.get(bob.id(), id)).isEmpty();
        assertThat(recordings.delete(bob.id(), id)).isFalse();
        assertThat(recordings.get(ann.id(), id)).isPresent();
        assertThat(audio.path(ann.id(), id)).exists();
    }

    @Test
    void uploadOfIdOwnedByOtherUserIsConflict() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] data = bytes(512, 8);
        upload(ann, id, data);

        assertThatThrownBy(() -> upload(bob, id, data))
                .satisfies(t -> assertThat(statusOf(t)).isEqualTo(HttpStatus.CONFLICT));
        assertThat(filesOf(bob)).isEmpty();
        assertThat(recordings.get(ann.id(), id).orElseThrow().userId()).isEqualTo(ann.id());
    }

    @Test
    void deleteRemovesFileAndRows() throws Exception {
        UUID id = UUID.randomUUID();
        upload(ann, id, bytes(1024, 9));
        jdbc.update("insert into transcript(recording_id, user_id, text) values (?, ?, 'hello')", id, ann.id());
        jdbc.update("insert into summary(recording_id, user_id, text) values (?, ?, 'hi')", id, ann.id());

        assertThat(recordings.delete(ann.id(), id)).isTrue();

        assertThat(audio.path(ann.id(), id)).doesNotExist();
        assertThat(filesOf(ann)).isEmpty();
        assertThat(count("select count(*) from recording where id = ?", id)).isZero();
        assertThat(count("select count(*) from processing_job where recording_id = ?", id))
                .isZero();
        assertThat(count("select count(*) from transcript where recording_id = ?", id))
                .isZero();
        assertThat(count("select count(*) from summary where recording_id = ?", id))
                .isZero();
        assertThat(recordings.delete(ann.id(), id)).isFalse();
    }

    @Test
    void deleteWhileFileMissingStillSucceeds() throws Exception {
        UUID id = UUID.randomUUID();
        upload(ann, id, bytes(1024, 10));
        Files.delete(audio.path(ann.id(), id));

        assertThat(recordings.delete(ann.id(), id)).isTrue();
        assertThat(count("select count(*) from recording where id = ?", id)).isZero();
    }

    @Test
    void listFiltersByUpdatedSince() throws Exception {
        UUID old = UUID.randomUUID();
        UUID mid = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        upload(ann, old, bytes(100, 11));
        upload(ann, mid, bytes(100, 12));
        upload(ann, recent, bytes(100, 13));
        upload(bob, UUID.randomUUID(), bytes(100, 14));
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        jdbc.update("update recording set updated_at = ? where id = ?", java.sql.Timestamp.from(base), old);
        jdbc.update(
                "update recording set updated_at = ? where id = ?",
                java.sql.Timestamp.from(base.plus(2, ChronoUnit.DAYS)),
                recent);
        jdbc.update(
                "update recording set updated_at = ? where id = ?",
                java.sql.Timestamp.from(base.plus(1, ChronoUnit.DAYS)),
                mid);

        assertThat(recordings.list(ann.id(), null)).extracting(Recording::id).containsExactly(old, mid, recent);
        assertThat(recordings.list(ann.id(), base.plus(1, ChronoUnit.HOURS)))
                .extracting(Recording::id)
                .containsExactly(mid, recent);
        assertThat(recordings.list(ann.id(), base.plus(3, ChronoUnit.DAYS))).isEmpty();
    }

    @Test
    void statusChangeTouchesUpdatedAtAndExposesLastError() throws Exception {
        UUID id = UUID.randomUUID();
        upload(ann, id, bytes(100, 15));
        jdbc.update(
                "update recording set updated_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                id);
        jdbc.update("update processing_job set state = 'FAILED', last_error = 'boom' where recording_id = ?", id);

        recordings.updateProcessingStatus(id, ProcessingStatus.FAILED);

        Recording r = recordings.get(ann.id(), id).orElseThrow();
        assertThat(r.processingStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(r.lastError()).isEqualTo("boom");
        assertThat(r.updatedAt()).isAfter(Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    void savedFileNeverLeavesUserDir() throws Exception {
        UUID id = UUID.randomUUID();
        InputStream in = new ByteArrayInputStream(new byte[] {1, 2, 3});
        Path p = audio.save(ann.id(), id, in);
        assertThat(p)
                .isEqualTo(DATA_DIR.resolve("audio")
                        .resolve(ann.id().toString())
                        .resolve(id + ".m4a")
                        .toAbsolutePath()
                        .normalize());
        assertThat(p).hasBinaryContent(new byte[] {1, 2, 3});
        audio.delete(ann.id(), id);
        assertThat(p).doesNotExist();
    }
}
