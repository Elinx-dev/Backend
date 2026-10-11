-- Subdivision at Register: the parent token is closed as INACTIVE and every VAO / Surveyor / Tahsildar
-- action on the transaction is recorded as a new block (ANNOTATE version) on each child token.
ALTER TABLE chain.token DROP CONSTRAINT IF EXISTS token_status_check;
ALTER TABLE chain.token
  ADD CONSTRAINT token_status_check CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'INACTIVE'));
ALTER TABLE chain.token ADD COLUMN IF NOT EXISTS approval_status TEXT;

ALTER TABLE chain.token_state_history
  ADD COLUMN IF NOT EXISTS action_code     TEXT,
  ADD COLUMN IF NOT EXISTS actor_role      TEXT,
  ADD COLUMN IF NOT EXISTS actor_user_id   BIGINT,
  ADD COLUMN IF NOT EXISTS decision        TEXT,
  ADD COLUMN IF NOT EXISTS approval_status TEXT,
  ADD COLUMN IF NOT EXISTS remarks         TEXT;
