-- Hyperledger Fabric ledger: per-org submission, chain-rule rejections, encumbrance register,
-- off-chain token metadata (only its hash goes on the ledger).
ALTER TABLE chain.blockchain_transaction DROP CONSTRAINT IF EXISTS blockchain_transaction_status_check;
ALTER TABLE chain.blockchain_transaction ADD CONSTRAINT blockchain_transaction_status_check
  CHECK (status IN ('QUEUED','SUBMITTED','MINED','FAILED','SKIPPED','REJECTED'));
ALTER TABLE chain.blockchain_transaction
  ADD COLUMN IF NOT EXISTS org_msp TEXT,
  ADD COLUMN IF NOT EXISTS chaincode_function TEXT,
  ADD COLUMN IF NOT EXISTS validation_code TEXT;

CREATE TABLE IF NOT EXISTS chain.encumbrance (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  enc_ref TEXT NOT NULL UNIQUE,
  token_id BIGINT NOT NULL REFERENCES chain.token(id),
  enc_type TEXT NOT NULL CHECK (enc_type IN ('MORTGAGE','LEASE','COURT_ATTACHMENT')),
  holder_name TEXT NOT NULL,
  holder_hash BYTEA NOT NULL,
  document_ref TEXT NOT NULL,
  doc_hash BYTEA NOT NULL,
  amount NUMERIC(18,2),
  remarks TEXT,
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RELEASED')),
  created_by BIGINT,
  created_role TEXT,
  org_msp TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  released_by BIGINT,
  released_at TIMESTAMPTZ,
  release_document_ref TEXT
);
CREATE INDEX IF NOT EXISTS ix_encumbrance_token ON chain.encumbrance (token_id, status);

CREATE TABLE IF NOT EXISTS chain.token_metadata (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  token_id BIGINT NOT NULL REFERENCES chain.token(id),
  state_version INT NOT NULL,
  metadata_json JSONB NOT NULL,
  metadata_hash BYTEA NOT NULL,
  geo_hash BYTEA,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (token_id, state_version)
);

GRANT SELECT, INSERT, UPDATE ON chain.encumbrance, chain.token_metadata TO slate_app;
GRANT USAGE, SELECT ON SEQUENCE chain.encumbrance_id_seq, chain.token_metadata_id_seq TO slate_app;
