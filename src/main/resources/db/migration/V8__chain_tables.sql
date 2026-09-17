-- Off-chain mirror of the ledger. Nothing here holds private key material.
-- Reference: Application Design §10; Database Design §8

CREATE TABLE chain.token (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code            CHAR(2) NOT NULL,
  token_ref             TEXT NOT NULL UNIQUE,
  onchain_token_id      NUMERIC(78,0),
  property_id           BIGINT NOT NULL REFERENCES core.property(id),
  parent_token_id       BIGINT REFERENCES chain.token(id),
  state_version         INT NOT NULL DEFAULT 1,
  status                TEXT NOT NULL CHECK (status IN ('ACTIVE','SUPERSEDED')),
  property_ref_hash     BYTEA NOT NULL,
  owner_set_hash        BYTEA NOT NULL,
  evidence_root         BYTEA,
  prev_state_hash       BYTEA,
  config_hash           BYTEA,
  minted_txn_id         BIGINT NOT NULL REFERENCES core.transaction(id),
  minted_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  superseded_at         TIMESTAMPTZ
);
-- Exactly one active token per property.
CREATE UNIQUE INDEX ux_token_property_active ON chain.token (property_id) WHERE status = 'ACTIVE';

ALTER TABLE core.property
  ADD CONSTRAINT fk_property_token FOREIGN KEY (token_id) REFERENCES chain.token(id);
ALTER TABLE survey.resulting_parcel
  ADD CONSTRAINT fk_parcel_token FOREIGN KEY (child_token_id) REFERENCES chain.token(id);

CREATE TABLE chain.token_state_history (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  token_id          BIGINT NOT NULL REFERENCES chain.token(id),
  state_version     INT NOT NULL,
  operation         TEXT NOT NULL CHECK (operation IN ('MINT','UPDATE','SPLIT','SUPERSEDE','ANNOTATE')),
  transaction_id    BIGINT REFERENCES core.transaction(id),
  owner_set_hash    BYTEA NOT NULL,
  owner_set_json    JSONB NOT NULL,
  evidence_root     BYTEA,
  prev_state_hash   BYTEA,
  state_hash        BYTEA NOT NULL,
  onchain_tx_hash   TEXT,
  recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (token_id, state_version)
);

CREATE TABLE chain.blockchain_transaction (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code        CHAR(2) NOT NULL,
  network_code      TEXT NOT NULL,
  contract_code     TEXT NOT NULL,
  function_name     TEXT NOT NULL,
  args_json         JSONB NOT NULL,
  idempotency_key   UUID NOT NULL UNIQUE,
  status            TEXT NOT NULL CHECK (status IN ('QUEUED','SUBMITTED','MINED','FAILED','SKIPPED')),
  tx_hash           TEXT,
  block_number      BIGINT,
  gas_used          BIGINT,
  error_message     TEXT,
  transaction_id    BIGINT REFERENCES core.transaction(id),
  token_id          BIGINT REFERENCES chain.token(id),
  submitted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  mined_at          TIMESTAMPTZ
);
CREATE INDEX ix_bctx_status ON chain.blockchain_transaction (status, submitted_at);

CREATE TABLE chain.blockchain_event (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  network_code  TEXT NOT NULL,
  contract_code TEXT NOT NULL,
  event_name    TEXT NOT NULL,
  block_number  BIGINT NOT NULL,
  tx_hash       TEXT NOT NULL,
  log_index     INT NOT NULL,
  payload_json  JSONB NOT NULL,
  observed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (network_code, tx_hash, log_index)   -- replay-safe
);

CREATE TABLE chain.indexer_checkpoint (
  network_code       TEXT PRIMARY KEY,
  last_block_number  BIGINT NOT NULL DEFAULT 0,
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chain.merkle_batch (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code    CHAR(2) NOT NULL,
  scope         TEXT NOT NULL CHECK (scope IN ('TRANSACTION','DAILY')),
  transaction_id BIGINT REFERENCES core.transaction(id),
  merkle_root   BYTEA NOT NULL,
  leaf_count    INT NOT NULL,
  anchored_tx_hash TEXT,
  anchored_block BIGINT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chain.merkle_leaf (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  batch_id   BIGINT NOT NULL REFERENCES chain.merkle_batch(id) ON DELETE CASCADE,
  leaf_index INT NOT NULL,
  leaf_hash  BYTEA NOT NULL,
  subject_type TEXT NOT NULL,
  subject_id BIGINT NOT NULL,
  proof_json JSONB,
  UNIQUE (batch_id, leaf_index)
);

ALTER TABLE core.document
  ADD CONSTRAINT fk_doc_batch FOREIGN KEY (merkle_batch_id) REFERENCES chain.merkle_batch(id);

-- Public metadata only. Key material lives in Vault / the HSM, never here.
CREATE TABLE chain.signing_key (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  department     TEXT NOT NULL,
  state_code     CHAR(2) NOT NULL,
  key_ref        TEXT NOT NULL,     -- Vault Transit key name / PKCS#11 label
  address        TEXT NOT NULL,
  public_key     TEXT,
  algorithm      TEXT NOT NULL DEFAULT 'secp256k1',
  status         TEXT NOT NULL CHECK (status IN ('ACTIVE','ROTATED','REVOKED')),
  activated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  rotated_at     TIMESTAMPTZ,
  UNIQUE (state_code, department, address)
);

CREATE TABLE chain.endorsement (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id  BIGINT NOT NULL REFERENCES core.transaction(id),
  operation       TEXT NOT NULL CHECK (operation IN ('MINT','UPDATE','SPLIT','ANNOTATE','ANCHOR')),
  department      TEXT NOT NULL,
  signer_address  TEXT NOT NULL,
  signature       TEXT NOT NULL,
  digest          BYTEA NOT NULL,
  signed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (transaction_id, operation, department)
);

CREATE TABLE chain.verification_run (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  property_id    BIGINT REFERENCES core.property(id),
  token_id       BIGINT REFERENCES chain.token(id),
  requested_by   BIGINT,
  requested_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  outcome        TEXT NOT NULL CHECK (outcome IN ('VERIFIED','MISMATCH','UNAVAILABLE')),
  details_json   JSONB NOT NULL
);
