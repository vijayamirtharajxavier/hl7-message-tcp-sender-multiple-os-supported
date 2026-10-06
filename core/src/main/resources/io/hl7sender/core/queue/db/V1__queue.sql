-- Durable outbound queue. All timestamps are epoch milliseconds (UTC).

CREATE TABLE destination (
    id                   INTEGER PRIMARY KEY,
    name                 TEXT    NOT NULL UNIQUE,
    host                 TEXT    NOT NULL,
    port                 INTEGER NOT NULL,
    connect_timeout_ms   INTEGER NOT NULL,
    ack_timeout_ms       INTEGER NOT NULL,
    charset              TEXT    NOT NULL,
    ack_mode             TEXT    NOT NULL,
    connection_mode      TEXT    NOT NULL,
    retry_max_attempts   INTEGER NOT NULL,
    retry_base_ms        INTEGER NOT NULL,
    retry_max_ms         INTEGER NOT NULL,
    retry_jitter         REAL    NOT NULL,
    cb_failure_threshold INTEGER NOT NULL,
    cb_cool_down_ms      INTEGER NOT NULL,
    ack_policy           TEXT    NOT NULL,
    paused               INTEGER NOT NULL DEFAULT 0,
    created_at           INTEGER NOT NULL,
    updated_at           INTEGER NOT NULL
);

CREATE TABLE message (
    id                 INTEGER PRIMARY KEY,
    destination_id     INTEGER NOT NULL REFERENCES destination (id) ON DELETE CASCADE,
    -- Dispatch order within a destination. Re-queued messages get a new, higher value.
    queue_seq          INTEGER NOT NULL,
    control_id         TEXT    NOT NULL,
    message_type       TEXT    NOT NULL,
    payload            TEXT    NOT NULL,
    status             TEXT    NOT NULL,
    attempts           INTEGER NOT NULL DEFAULT 0,
    next_attempt_at    INTEGER NOT NULL,
    possible_duplicate INTEGER NOT NULL DEFAULT 0,
    last_outcome       TEXT,
    last_error         TEXT,
    created_at         INTEGER NOT NULL,
    updated_at         INTEGER NOT NULL,
    completed_at       INTEGER
);

CREATE INDEX message_dispatch ON message (destination_id, status, queue_seq);
CREATE INDEX message_control_id ON message (destination_id, control_id);

CREATE TABLE attempt (
    id            INTEGER PRIMARY KEY,
    message_id    INTEGER NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    attempt_no    INTEGER NOT NULL,
    started_at    INTEGER NOT NULL,
    finished_at   INTEGER,
    outcome       TEXT,
    detail        TEXT,
    connect_ms    INTEGER,
    round_trip_ms INTEGER
);

CREATE INDEX attempt_message ON attempt (message_id);

CREATE TABLE ack (
    id             INTEGER PRIMARY KEY,
    message_id     INTEGER NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    attempt_id     INTEGER NOT NULL REFERENCES attempt (id) ON DELETE CASCADE,
    ack_code       TEXT,
    msa_control_id TEXT,
    msa_text       TEXT,
    errors         TEXT,
    raw            TEXT    NOT NULL,
    received_at    INTEGER NOT NULL
);

CREATE INDEX ack_message ON ack (message_id);

-- Append-only record of every state change.
CREATE TABLE audit_event (
    id             INTEGER PRIMARY KEY,
    message_id     INTEGER REFERENCES message (id) ON DELETE CASCADE,
    destination_id INTEGER REFERENCES destination (id) ON DELETE CASCADE,
    from_status    TEXT,
    to_status      TEXT,
    actor          TEXT    NOT NULL,
    detail         TEXT,
    at             INTEGER NOT NULL
);

CREATE INDEX audit_message ON audit_event (message_id);
