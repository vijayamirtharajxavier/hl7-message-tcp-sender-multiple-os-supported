-- Dashboard metrics query attempts by time window.

CREATE INDEX attempt_finished ON attempt (finished_at);
