-- Revenue ownership check: the transaction type names the party side that holds the
-- current ownership, and each Revenue snapshot keeps every survey/subdivision row with
-- its own extent so the comparison is survey-wise.

ALTER TABLE master.transaction_type
  ADD COLUMN IF NOT EXISTS owner_side TEXT NOT NULL DEFAULT 'SIDE_1';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'transaction_type_owner_side_check') THEN
    ALTER TABLE master.transaction_type
      ADD CONSTRAINT transaction_type_owner_side_check CHECK (owner_side IN ('SIDE_1', 'SIDE_2'));
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS rules.revenue_parcel (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  snapshot_id    BIGINT NOT NULL REFERENCES rules.revenue_ownership_snapshot(id) ON DELETE CASCADE,
  seq            INT NOT NULL,
  survey_no      TEXT NOT NULL,
  subdivision_no TEXT,
  extent_value   NUMERIC(18,4),
  extent_unit    TEXT,
  UNIQUE (snapshot_id, seq)
);
