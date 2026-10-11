-- Partition: current-owner status, deceased owners' legal-heir branches (any depth),
-- certificate uploads, and schedule allocation detail (allottees, extents, boundaries).

ALTER TABLE core.transaction_party ADD COLUMN IF NOT EXISTS deceased BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS core.document_content (
  document_id  BIGINT PRIMARY KEY REFERENCES core.document(id),
  content      BYTEA NOT NULL
);

CREATE TABLE IF NOT EXISTS core.partition_member (
  id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id              BIGINT NOT NULL REFERENCES core.transaction(id),
  seq                         INT NOT NULL,
  member_ref                  TEXT NOT NULL,
  parent_member_id            BIGINT REFERENCES core.partition_member(id) ON DELETE CASCADE,
  member_type                 TEXT NOT NULL CHECK (member_type IN ('OWNER','HEIR')),
  property_owner_id           BIGINT REFERENCES core.property_owner(id),
  party_id                    BIGINT REFERENCES core.transaction_party(id) ON DELETE SET NULL,
  name                        TEXT NOT NULL,
  relationship                TEXT,
  living_status               TEXT NOT NULL CHECK (living_status IN ('LIVING','DECEASED')),
  marital_status              TEXT,
  date_of_death               DATE,
  death_cert_no               TEXT,
  death_cert_date             DATE,
  death_cert_document_id      BIGINT REFERENCES core.document(id),
  legal_heir_cert_available   BOOLEAN,
  legal_heir_cert_no          TEXT,
  legal_heir_cert_date        DATE,
  legal_heir_document_id      BIGINT REFERENCES core.document(id),
  successors_available        BOOLEAN,
  created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (transaction_id, member_ref)
);
CREATE INDEX IF NOT EXISTS ix_partition_member_txn ON core.partition_member (transaction_id);

ALTER TABLE core.transaction_schedule
  ADD COLUMN IF NOT EXISTS allotted_member_refs TEXT[],
  ADD COLUMN IF NOT EXISTS source_branch        TEXT,
  ADD COLUMN IF NOT EXISTS total_extent         NUMERIC(18,4),
  ADD COLUMN IF NOT EXISTS extent_unit          TEXT,
  ADD COLUMN IF NOT EXISTS north_boundary       TEXT,
  ADD COLUMN IF NOT EXISTS south_boundary       TEXT,
  ADD COLUMN IF NOT EXISTS east_boundary        TEXT,
  ADD COLUMN IF NOT EXISTS west_boundary        TEXT,
  ADD COLUMN IF NOT EXISTS remarks              TEXT;

ALTER TABLE core.transaction_schedule_survey
  ADD COLUMN IF NOT EXISTS ulpin           TEXT,
  ADD COLUMN IF NOT EXISTS extent_allotted NUMERIC(18,4) CHECK (extent_allotted IS NULL OR extent_allotted > 0),
  ADD COLUMN IF NOT EXISTS extent_unit     TEXT;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'slate_app') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON core.document_content, core.partition_member TO slate_app;
  END IF;
END $$;
