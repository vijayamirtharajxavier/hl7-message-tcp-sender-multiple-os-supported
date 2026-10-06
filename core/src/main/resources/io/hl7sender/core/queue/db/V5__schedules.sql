-- Scheduled sends. A schedule queues its message(s) to a destination on a cron expression.

CREATE TABLE schedule (
    id             INTEGER PRIMARY KEY,
    name           TEXT    NOT NULL UNIQUE,
    cron           TEXT    NOT NULL,
    zone           TEXT    NOT NULL,
    destination_id INTEGER NOT NULL REFERENCES destination (id) ON DELETE CASCADE,
    message        TEXT    NOT NULL,
    count          INTEGER NOT NULL DEFAULT 1,
    enabled        INTEGER NOT NULL DEFAULT 1,
    last_run_at    INTEGER,
    last_result    TEXT,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);
