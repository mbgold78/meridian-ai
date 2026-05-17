-- 0015_transient_tokens.sql
-- M5-sqlite-transient-token-store slice 1a — transient credentials storage.
-- Purpose: SQLite-backed store for indexed=False (transient) tokens — moves
-- per-dispatch Implementer/Tester tokens + per-spawn supervisor tokens off
-- Windows Credential Manager to bound keyring entry growth (β-S11-5/6 next
-- failure mode). Keyring remains the storage for indexed=True (static seeds +
-- OAuth-issued PM tokens). Schema mirrors keyring TokenRecord fields needed
-- for the dispatch-self-access contract; transient records carry `source`
-- via the hardcoded-'static' policy at the TransientTokenStore.issue layer
-- (per ratified decision D1=(a)) — no `source` column persisted here.
-- Greenfield-safety: additive migration; no existing rows referenced.

CREATE TABLE transient_tokens (
    token_id      TEXT PRIMARY KEY,
    agent_id      TEXT NOT NULL,
    role          TEXT NOT NULL,
    label         TEXT NOT NULL,
    secret_hash   TEXT NOT NULL,
    issued_at     TEXT NOT NULL,
    revoked_at    TEXT,
    ttl_seconds   INTEGER NOT NULL DEFAULT 86400,
    expires_at    TEXT NOT NULL
);

CREATE INDEX transient_tokens_expires_at_idx ON transient_tokens(expires_at);
CREATE INDEX transient_tokens_agent_id_idx   ON transient_tokens(agent_id);
