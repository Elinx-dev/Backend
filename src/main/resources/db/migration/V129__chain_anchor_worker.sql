-- Anchors are queued at Register and sent by a background worker with retries.
ALTER TABLE chain.blockchain_transaction
  ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_bctx_worker ON chain.blockchain_transaction (status, next_attempt_at, id);

CREATE TABLE IF NOT EXISTS chain.reconciliation_run (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  trigger         TEXT NOT NULL,
  started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at     TIMESTAMPTZ,
  outcome         TEXT NOT NULL,
  tokens_checked  INT NOT NULL DEFAULT 0,
  states_checked  INT NOT NULL DEFAULT 0,
  not_anchored    INT NOT NULL DEFAULT 0,
  mismatches      INT NOT NULL DEFAULT 0,
  details_json    JSONB
);
