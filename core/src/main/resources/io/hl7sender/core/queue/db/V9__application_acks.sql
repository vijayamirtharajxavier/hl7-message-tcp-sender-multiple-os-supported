-- Enhanced acknowledgment mode: after a commit ACK (CA), a message whose MSH-16 asks for an application ACK
-- waits in AWAITING_APP_ACK until the receiver sends AA/AE/AR to the destination's application ACK port.

ALTER TABLE destination ADD COLUMN app_ack_port INTEGER NOT NULL DEFAULT 0;
ALTER TABLE destination ADD COLUMN app_ack_timeout_ms INTEGER NOT NULL DEFAULT 300000;

-- MSH-16 of a waiting message (AL, ER or SU) and when its wait ends.
ALTER TABLE message ADD COLUMN app_ack_mode TEXT;
ALTER TABLE message ADD COLUMN app_ack_due_at INTEGER;
CREATE INDEX message_app_ack ON message (destination_id, status, app_ack_due_at);

-- 'response' for the ACK returned on the sending connection, 'application' for a later application ACK.
ALTER TABLE ack ADD COLUMN kind TEXT NOT NULL DEFAULT 'response';
