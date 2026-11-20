package com.muninn.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.muninn.provider.FakeOpenAi.Gate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class JobWorkerTest extends ProcessingFixture {

    @Autowired
    TransactionTemplate tx;

    private double dueIn(UUID recordingId) {
        return ((Number) job(recordingId).get("due_in_seconds")).doubleValue();
    }

    @Test
    void twoWorkersNeverClaimSameJob() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(upload(i % 2 == 0 ? ann : bob));
        }
        ConcurrentLinkedQueue<UUID> processed = new ConcurrentLinkedQueue<>();
        ProcessingPipeline slow = mock(ProcessingPipeline.class);
        doAnswer(inv -> {
                    processed.add(inv.getArgument(0));
                    Thread.sleep(20);
                    return null;
                })
                .when(slow)
                .process(any());
        JobWorker first = new JobWorker(jdbc, tx, slow, recordings, Duration.ofMinutes(30));
        JobWorker second = new JobWorker(jdbc, tx, slow, recordings, Duration.ofMinutes(30));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (JobWorker w : List.of(first, second, first, second)) {
                runs.add(pool.submit(() -> {
                    while (w.runOnce()) {
                        // drain
                    }
                }));
            }
            for (Future<?> f : runs) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(processed).hasSize(ids.size()).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
        assertThat(count("select count(*) from processing_job where state = 'COMPLETED' and attempts = 1"))
                .isEqualTo(ids.size());
    }

    @Test
    void retriesWithBackoffThenFails() throws Exception {
        UUID id = upload(ann); // no provider configured: every attempt fails
        assertThat(worker.runOnce()).isTrue();
        assertThat(job(id))
                .containsEntry("state", "PENDING")
                .containsEntry("attempts", 1)
                .containsEntry("last_error", "No AI provider configured");
        assertThat(dueIn(id)).isBetween(25.0, 31.0);
        assertThat(processingStatus(id)).isEqualTo("PENDING");
        assertThat(worker.runOnce()).as("not due yet").isFalse();

        makeDue(id);
        assertThat(worker.runOnce()).isTrue();
        assertThat(job(id)).containsEntry("state", "PENDING").containsEntry("attempts", 2);
        assertThat(dueIn(id)).isBetween(115.0, 121.0);

        makeDue(id);
        assertThat(worker.runOnce()).isTrue();
        assertThat(job(id)).containsEntry("state", "PENDING").containsEntry("attempts", 3);
        assertThat(dueIn(id)).isBetween(595.0, 601.0);

        makeDue(id);
        assertThat(worker.runOnce()).isTrue();
        assertThat(job(id))
                .containsEntry("state", "FAILED")
                .containsEntry("attempts", 4)
                .containsEntry("last_error", "No AI provider configured");
        assertThat(processingStatus(id)).isEqualTo("FAILED");
        assertThat(recordings.get(ann.id(), id).orElseThrow().lastError()).isEqualTo("No AI provider configured");

        makeDue(id);
        assertThat(worker.runOnce()).as("failed jobs are never claimed again").isFalse();
    }

    @Test
    void invalidSummaryDoesNotKillWorker() throws Exception {
        systemProvider();
        AI.chatContent("I'm sorry, I can't produce JSON today.");
        UUID bad = upload(ann);
        assertThatCode(() -> assertThat(worker.runOnce()).isTrue()).doesNotThrowAnyException();
        assertThat(job(bad))
                .containsEntry("state", "PENDING")
                .containsEntry("attempts", 1)
                .containsEntry("last_error", "Summary model returned invalid output");
        assertThat(count("select count(*) from transcript where recording_id = ?", bad))
                .isEqualTo(1);
        assertThat(count("select count(*) from summary where recording_id = ?", bad))
                .isZero();

        AI.chatContent(VALID_SUMMARY);
        UUID good = upload(bob);
        assertThat(worker.runOnce()).isTrue();
        assertThat(job(good)).containsEntry("state", "COMPLETED");
        assertThat(processingStatus(good)).isEqualTo("COMPLETED");
    }

    @Test
    void reclaimsStaleRunningJob() throws Exception {
        systemProvider();
        UUID stale = upload(ann);
        UUID fresh = upload(bob);
        jdbc.update(
                "update processing_job set state = 'RUNNING', attempts = 1, locked_at = now() - interval '31 minutes' "
                        + "where recording_id = ?",
                stale);
        jdbc.update(
                "update processing_job set state = 'RUNNING', attempts = 1, locked_at = now() - interval '5 minutes' "
                        + "where recording_id = ?",
                fresh);

        assertThat(worker.runOnce()).isTrue();
        assertThat(job(stale)).containsEntry("state", "COMPLETED").containsEntry("attempts", 2);
        assertThat(processingStatus(stale)).isEqualTo("COMPLETED");

        assertThat(worker.runOnce())
                .as("a RUNNING job inside the timeout belongs to its worker")
                .isFalse();
        assertThat(job(fresh)).containsEntry("state", "RUNNING").containsEntry("attempts", 1);
    }

    @Test
    void doesNotHoldLockDuringProcessing() throws Exception {
        systemProvider();
        UUID first = upload(ann);
        Gate gate = AI.hold(TRANSCRIBE);
        CompletableFuture<Boolean> running = CompletableFuture.supplyAsync(worker::runOnce);
        try {
            gate.awaitArrived();
            assertThat(job(first)).containsEntry("state", "RUNNING");
            assertThat(processingStatus(first)).isEqualTo("TRANSCRIBING");
            // No transaction holds the job or recording row: NOWAIT locks succeed immediately.
            tx.executeWithoutResult(s -> {
                jdbc.queryForList("select id from processing_job where recording_id = ? for update nowait", first);
                jdbc.queryForList("select id from recording where id = ? for update nowait", first);
            });
            UUID second = upload(bob);
            assertThat(worker.runOnce())
                    .as("a second job is claimed while the first is mid-flight")
                    .isTrue();
            assertThat(job(second)).containsEntry("state", "COMPLETED");
        } finally {
            gate.release();
        }
        assertThat(running.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(job(first)).containsEntry("state", "COMPLETED");
    }

    @Test
    void deletedRecordingMidProcessingIsHandled() throws Exception {
        systemProvider();
        UUID id = upload(ann);
        Gate gate = AI.hold(TRANSCRIBE);
        CompletableFuture<Boolean> running = CompletableFuture.supplyAsync(worker::runOnce);
        try {
            gate.awaitArrived();
            assertThat(CompletableFuture.supplyAsync(() -> recordings.delete(ann.id(), id))
                            .get(5, TimeUnit.SECONDS))
                    .as("delete is not blocked by the worker")
                    .isTrue();
        } finally {
            gate.release();
        }
        assertThat(running.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(count("select count(*) from transcript where recording_id = ?", id))
                .isZero();
        assertThat(count("select count(*) from summary where recording_id = ?", id))
                .isZero();
        assertThat(count("select count(*) from processing_job where recording_id = ?", id))
                .isZero();
        assertThat(AI.count(CHAT))
                .as("no summary is attempted for a deleted recording")
                .isZero();

        UUID next = upload(bob);
        assertThat(worker.runOnce()).isTrue();
        assertThat(processingStatus(next)).isEqualTo("COMPLETED");
    }

    @Test
    void workerSurvivesUnexpectedPipelineErrors() throws Exception {
        UUID id = upload(ann);
        ProcessingPipeline broken = mock(ProcessingPipeline.class);
        doAnswer(inv -> {
                    throw new IllegalStateException("boom at https://internal.example/secret?key=sk-abc");
                })
                .when(broken)
                .process(any());
        JobWorker w = new JobWorker(jdbc, tx, broken, recordings, Duration.ofMinutes(30));
        assertThatCode(() -> assertThat(w.runOnce()).isTrue()).doesNotThrowAnyException();
        Map<String, Object> row = job(id);
        assertThat(row).containsEntry("state", "PENDING").containsEntry("last_error", "Processing failed");
    }
}
