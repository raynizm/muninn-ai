-- When a worker claimed the job. A RUNNING job whose locked_at is older than muninn.job-timeout belongs to a
-- worker that died mid-flight and may be claimed again. No row lock is held while a job is processed.
alter table processing_job
    add column locked_at timestamptz;
