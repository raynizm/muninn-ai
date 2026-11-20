-- Accounts are bound to the IdP identity (iss, sub), not to the mutable email claim.
-- Rows created before this migration have NULL here and are bound on their next login.
alter table app_user
    add column oidc_issuer  text,
    add column oidc_subject text,
    add constraint uq_app_user_oidc_identity unique (oidc_issuer, oidc_subject);

-- Original login time, carried across refresh-token rotation to cap total session lifetime.
alter table refresh_token
    add column session_started_at timestamptz not null default now();
