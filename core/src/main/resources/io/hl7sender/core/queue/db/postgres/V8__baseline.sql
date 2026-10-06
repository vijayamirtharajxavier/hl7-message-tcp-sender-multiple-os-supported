-- PostgreSQL schema, equivalent to the SQLite scripts V1-V8. All timestamps are epoch milliseconds (UTC), so every
-- integer column is BIGINT. Later versions add their own postgres/V<n>__*.sql script.

CREATE TABLE destination (
    id                   BIGSERIAL PRIMARY KEY,
    name                 TEXT    NOT NULL UNIQUE,
    host                 TEXT    NOT NULL,
    port                 BIGINT  NOT NULL,
    connect_timeout_ms   BIGINT  NOT NULL,
    ack_timeout_ms       BIGINT  NOT NULL,
    charset              TEXT    NOT NULL,
    ack_mode             TEXT    NOT NULL,
    connection_mode      TEXT    NOT NULL,
    retry_max_attempts   BIGINT  NOT NULL,
    retry_base_ms        BIGINT  NOT NULL,
    retry_max_ms         BIGINT  NOT NULL,
    retry_jitter         DOUBLE PRECISION NOT NULL,
    cb_failure_threshold BIGINT  NOT NULL,
    cb_cool_down_ms      BIGINT  NOT NULL,
    ack_policy           TEXT    NOT NULL,
    paused               BIGINT  NOT NULL DEFAULT 0,
    created_at           BIGINT  NOT NULL,
    updated_at           BIGINT  NOT NULL,
    max_per_second       BIGINT  NOT NULL DEFAULT 0,
    validation_level     TEXT    NOT NULL DEFAULT 'STANDARD',
    profile_path         TEXT    NOT NULL DEFAULT '',
    watch_folder         TEXT    NOT NULL DEFAULT '',
    tls_enabled          BIGINT  NOT NULL DEFAULT 0,
    tls_trust_path       TEXT    NOT NULL DEFAULT '',
    tls_key_path         TEXT    NOT NULL DEFAULT '',
    tls_verify_hostname  BIGINT  NOT NULL DEFAULT 1,
    tls_protocols        TEXT    NOT NULL DEFAULT 'TLSv1.3,TLSv1.2',
    secret_ref           TEXT    NOT NULL DEFAULT '',
    notes                TEXT    NOT NULL DEFAULT '',
    script               TEXT    NOT NULL DEFAULT '',
    transport            TEXT    NOT NULL DEFAULT 'mllp',
    transport_options    TEXT    NOT NULL DEFAULT '{}'
);

CREATE TABLE message (
    id                 BIGSERIAL PRIMARY KEY,
    destination_id     BIGINT  NOT NULL REFERENCES destination (id) ON DELETE CASCADE,
    queue_seq          BIGINT  NOT NULL,
    control_id         TEXT    NOT NULL,
    message_type       TEXT    NOT NULL,
    payload            TEXT    NOT NULL,
    status             TEXT    NOT NULL,
    attempts           BIGINT  NOT NULL DEFAULT 0,
    next_attempt_at    BIGINT  NOT NULL,
    possible_duplicate BIGINT  NOT NULL DEFAULT 0,
    last_outcome       TEXT,
    last_error         TEXT,
    created_at         BIGINT  NOT NULL,
    updated_at         BIGINT  NOT NULL,
    completed_at       BIGINT,
    source             TEXT,
    batch_id           TEXT
);

CREATE INDEX message_dispatch ON message (destination_id, status, queue_seq);
CREATE INDEX message_control_id ON message (destination_id, control_id);
CREATE INDEX message_created ON message (created_at);
CREATE INDEX message_batch ON message (batch_id);
CREATE INDEX message_seq ON message (queue_seq);

CREATE TABLE attempt (
    id            BIGSERIAL PRIMARY KEY,
    message_id    BIGINT  NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    attempt_no    BIGINT  NOT NULL,
    started_at    BIGINT  NOT NULL,
    finished_at   BIGINT,
    outcome       TEXT,
    detail        TEXT,
    connect_ms    BIGINT,
    round_trip_ms BIGINT
);

CREATE INDEX attempt_message ON attempt (message_id);
CREATE INDEX attempt_finished ON attempt (finished_at);

CREATE TABLE ack (
    id             BIGSERIAL PRIMARY KEY,
    message_id     BIGINT  NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    attempt_id     BIGINT  NOT NULL REFERENCES attempt (id) ON DELETE CASCADE,
    ack_code       TEXT,
    msa_control_id TEXT,
    msa_text       TEXT,
    errors         TEXT,
    raw            TEXT    NOT NULL,
    received_at    BIGINT  NOT NULL
);

CREATE INDEX ack_message ON ack (message_id);

CREATE TABLE audit_event (
    id             BIGSERIAL PRIMARY KEY,
    message_id     BIGINT REFERENCES message (id) ON DELETE CASCADE,
    destination_id BIGINT REFERENCES destination (id) ON DELETE CASCADE,
    from_status    TEXT,
    to_status      TEXT,
    actor          TEXT    NOT NULL,
    detail         TEXT,
    at             BIGINT  NOT NULL
);

CREATE INDEX audit_message ON audit_event (message_id);

CREATE TABLE schedule (
    id             BIGSERIAL PRIMARY KEY,
    name           TEXT    NOT NULL UNIQUE,
    cron           TEXT    NOT NULL,
    zone           TEXT    NOT NULL,
    destination_id BIGINT  NOT NULL REFERENCES destination (id) ON DELETE CASCADE,
    message        TEXT    NOT NULL,
    count          BIGINT  NOT NULL DEFAULT 1,
    enabled        BIGINT  NOT NULL DEFAULT 1,
    last_run_at    BIGINT,
    last_result    TEXT,
    created_at     BIGINT  NOT NULL,
    updated_at     BIGINT  NOT NULL
);

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      TEXT    NOT NULL UNIQUE,
    display_name  TEXT    NOT NULL DEFAULT '',
    role          TEXT    NOT NULL,
    password_hash TEXT    NOT NULL,
    enabled       BIGINT  NOT NULL DEFAULT 1,
    created_at    BIGINT  NOT NULL,
    updated_at    BIGINT  NOT NULL,
    last_login_at BIGINT
);
