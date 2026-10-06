-- Destinations can send by other transports (HTTP, file, plugins) instead of MLLP.

ALTER TABLE destination ADD COLUMN transport TEXT NOT NULL DEFAULT 'mllp';
ALTER TABLE destination ADD COLUMN transport_options TEXT NOT NULL DEFAULT '{}';
