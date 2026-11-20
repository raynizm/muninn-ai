-- Job lifecycle states (recording.processing_status carries the five user-facing states separately).
alter table processing_job
    add constraint ck_processing_job_state check (state in ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'));

-- Incremental sync: GET /api/recordings?updatedSince= filters and orders by these columns.
create index idx_recording_user_id_updated_at on recording (user_id, updated_at);
