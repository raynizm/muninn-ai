create table app_user (
    id           uuid primary key,
    email        text not null unique,
    display_name text,
    role         text not null check (role in ('ADMIN', 'USER')),
    created_at   timestamptz not null default now()
);

create table app_setting (
    key   text primary key,
    value text not null
);

create table ai_provider (
    id            uuid primary key,
    scope         text not null check (scope in ('GLOBAL', 'USER')),
    owner_user_id uuid references app_user (id) on delete cascade,
    name          text not null,
    base_url      text not null,
    encrypted_key text,
    enabled       boolean not null default true,
    created_at    timestamptz not null default now()
);
create index idx_ai_provider_owner_user_id on ai_provider (owner_user_id);

create table ai_model (
    id               uuid primary key,
    provider_id      uuid not null references ai_provider (id) on delete cascade,
    model_identifier text not null,
    capabilities     jsonb not null default '[]'::jsonb,
    enabled          boolean not null default true
);
create index idx_ai_model_provider_id on ai_model (provider_id);

create table recording (
    id                uuid primary key,
    user_id           uuid not null references app_user (id) on delete cascade,
    sha256            text,
    mime              text,
    size_bytes        bigint,
    duration_ms       bigint,
    upload_status     text not null default 'PENDING',
    processing_status text not null default 'PENDING'
        check (processing_status in ('PENDING', 'TRANSCRIBING', 'SUMMARIZING', 'COMPLETED', 'FAILED')),
    title             text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);
create index idx_recording_user_id on recording (user_id);

create table transcript (
    recording_id uuid primary key references recording (id) on delete cascade,
    user_id      uuid not null references app_user (id) on delete cascade,
    text         text not null,
    segments     jsonb not null default '[]'::jsonb
);
create index idx_transcript_user_id on transcript (user_id);

create table summary (
    recording_id uuid primary key references recording (id) on delete cascade,
    user_id      uuid not null references app_user (id) on delete cascade,
    text         text not null,
    action_items jsonb not null default '[]'::jsonb
);
create index idx_summary_user_id on summary (user_id);

create table processing_job (
    id           uuid primary key,
    recording_id uuid not null references recording (id) on delete cascade,
    state        text not null default 'PENDING',
    attempts     integer not null default 0,
    run_after    timestamptz not null default now(),
    last_error   text
);
create index idx_processing_job_recording_id on processing_job (recording_id);
create index idx_processing_job_state_run_after on processing_job (state, run_after);

create table refresh_token (
    id         uuid primary key,
    user_id    uuid not null references app_user (id) on delete cascade,
    token_hash text not null unique,
    expires_at timestamptz not null
);
create index idx_refresh_token_user_id on refresh_token (user_id);
