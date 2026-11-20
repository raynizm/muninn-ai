package com.muninn.processing;

import com.muninn.recording.ProcessingStatus;
import com.muninn.recording.RecordingService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs processing jobs from the {@code processing_job} table. A job is claimed in one short statement
 * ({@code FOR UPDATE SKIP LOCKED}, so concurrent workers never take the same row) that marks it RUNNING,
 * counts the attempt and stamps {@code locked_at}; no lock or transaction is held while the pipeline talks to
 * the provider. The outcome is then written in a new short transaction, fenced on (state RUNNING, attempts) so
 * a worker whose job was reclaimed after {@code muninn.job-timeout} cannot overwrite the newer attempt.
 *
 * <p>Failed attempts are retried after 30s, 2m and 10m; when the attempt after the last delay fails too, the
 * job and the recording become FAILED. {@code last_error} only ever holds authored, secret-free messages.
 */
@Component
public class JobWorker {

    static final List<Duration> BACKOFF =
            List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10));
    static final String GENERIC_FAILURE = "Processing failed";

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ProcessingPipeline pipeline;
    private final RecordingService recordings;
    private final Duration jobTimeout;

    private record Claimed(UUID id, UUID recordingId, int attempts) {}

    @Autowired
    public JobWorker(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            ProcessingPipeline pipeline,
            RecordingService recordings,
            @Value("${muninn.job-timeout:30m}") Duration jobTimeout) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.pipeline = pipeline;
        this.recordings = recordings;
        this.jobTimeout = jobTimeout;
    }

    /** Claims and runs at most one due job. Returns whether a job was run. Never throws. */
    public boolean runOnce() {
        Optional<Claimed> claimed;
        try {
            claimed = claim();
        } catch (RuntimeException e) {
            log.warn("Could not claim a processing job: {}", e.toString());
            return false;
        }
        if (claimed.isEmpty()) {
            return false;
        }
        Claimed job = claimed.get();
        String error = null;
        try {
            pipeline.process(job.recordingId());
        } catch (ProcessingException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            // Not a provider failure (those arrive as ProcessingException): log it for the operator, show a
            // generic message to the user.
            log.warn("Processing recording {} failed unexpectedly", job.recordingId(), e);
            error = GENERIC_FAILURE;
        }
        try {
            if (error == null) {
                complete(job);
            } else {
                fail(job, error);
            }
        } catch (RuntimeException e) {
            log.warn("Could not record the outcome of processing job {}: {}", job.id(), e.toString());
        }
        return true;
    }

    /** One autocommitted statement: picks a due PENDING job, or a RUNNING one abandoned for longer than the timeout. */
    private Optional<Claimed> claim() {
        return jdbc
                .query(
                        """
                        update processing_job j
                           set state = 'RUNNING', attempts = j.attempts + 1, locked_at = now()
                         where j.id = (select id from processing_job
                                        where (state = 'PENDING' and run_after <= now())
                                           or (state = 'RUNNING'
                                               and (locked_at is null or locked_at < now() - make_interval(secs => ?)))
                                        order by run_after, id
                                        limit 1
                                        for update skip locked)
                        returning j.id, j.recording_id, j.attempts""",
                        (rs, i) -> new Claimed(
                                rs.getObject("id", UUID.class),
                                rs.getObject("recording_id", UUID.class),
                                rs.getInt("attempts")),
                        (double) jobTimeout.toSeconds())
                .stream()
                .findFirst();
    }

    private void complete(Claimed job) {
        jdbc.update(
                "update processing_job set state = 'COMPLETED', last_error = null, locked_at = null "
                        + "where id = ? and state = 'RUNNING' and attempts = ?",
                job.id(),
                job.attempts());
    }

    private void fail(Claimed job, String error) {
        boolean retry = job.attempts() <= BACKOFF.size();
        log.info(
                "Processing job {} attempt {} failed: {}{}",
                job.id(),
                job.attempts(),
                error,
                retry ? "; will retry" : "; giving up");
        tx.executeWithoutResult(s -> {
            int updated = retry
                    ? jdbc.update(
                            "update processing_job set state = 'PENDING', last_error = ?, locked_at = null, "
                                    + "run_after = now() + make_interval(secs => ?) "
                                    + "where id = ? and state = 'RUNNING' and attempts = ?",
                            error,
                            (double) BACKOFF.get(job.attempts() - 1).toSeconds(),
                            job.id(),
                            job.attempts())
                    : jdbc.update(
                            "update processing_job set state = 'FAILED', last_error = ?, locked_at = null "
                                    + "where id = ? and state = 'RUNNING' and attempts = ?",
                            error,
                            job.id(),
                            job.attempts());
            if (updated == 1) {
                recordings.updateProcessingStatus(
                        job.recordingId(), retry ? ProcessingStatus.PENDING : ProcessingStatus.FAILED);
            }
        });
    }
}
