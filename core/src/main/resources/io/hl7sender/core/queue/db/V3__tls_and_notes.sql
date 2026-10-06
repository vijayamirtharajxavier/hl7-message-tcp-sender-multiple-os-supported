-- TLS per destination, a reference to its secrets in the OS keychain, and free-text notes.

ALTER TABLE destination ADD COLUMN tls_enabled INTEGER NOT NULL DEFAULT 0;
ALTER TABLE destination ADD COLUMN tls_trust_path TEXT NOT NULL DEFAULT '';
ALTER TABLE destination ADD COLUMN tls_key_path TEXT NOT NULL DEFAULT '';
ALTER TABLE destination ADD COLUMN tls_verify_hostname INTEGER NOT NULL DEFAULT 1;
ALTER TABLE destination ADD COLUMN tls_protocols TEXT NOT NULL DEFAULT 'TLSv1.3,TLSv1.2';
-- Key-store passwords are never stored here; this is the handle used to find them in the secret store.
ALTER TABLE destination ADD COLUMN secret_ref TEXT NOT NULL DEFAULT '';
ALTER TABLE destination ADD COLUMN notes TEXT NOT NULL DEFAULT '';
