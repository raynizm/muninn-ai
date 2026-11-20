-- Last connection-test result: a short secret-free message and when it ran.
alter table ai_provider
    add column last_test_ok      boolean,
    add column last_test_message text,
    add column last_tested_at    timestamptz;

-- Discovery upserts by (provider, model identifier).
create unique index uq_ai_model_provider_identifier on ai_model (provider_id, model_identifier);
