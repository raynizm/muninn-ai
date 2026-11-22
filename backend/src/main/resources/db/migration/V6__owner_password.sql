-- Password (BCrypt) for the single owner account. NULL for accounts created through SSO.
alter table app_user
    add column password_hash text;
