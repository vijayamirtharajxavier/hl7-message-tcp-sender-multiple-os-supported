-- Rate limiting, per-destination validation, folder watch, and batch tracking.

ALTER TABLE destination ADD COLUMN max_per_second INTEGER NOT NULL DEFAULT 0;
ALTER TABLE destination ADD COLUMN validation_level TEXT NOT NULL DEFAULT 'STANDARD';
ALTER TABLE destination ADD COLUMN profile_path TEXT NOT NULL DEFAULT '';
ALTER TABLE destination ADD COLUMN watch_folder TEXT NOT NULL DEFAULT '';

-- Where a message came from (file name, "editor", ...) and which import it belongs to.
ALTER TABLE message ADD COLUMN source TEXT;
ALTER TABLE message ADD COLUMN batch_id TEXT;

CREATE INDEX message_created ON message (created_at);
CREATE INDEX message_batch ON message (batch_id);
-- Enqueue computes MAX(queue_seq) for every insert; keep that O(log n) for bulk imports.
CREATE INDEX message_seq ON message (queue_seq);
