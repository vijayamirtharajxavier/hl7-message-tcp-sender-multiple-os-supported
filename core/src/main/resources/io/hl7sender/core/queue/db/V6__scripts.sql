-- A JavaScript transform per destination, run on each message as it is queued.

ALTER TABLE destination ADD COLUMN script TEXT NOT NULL DEFAULT '';
