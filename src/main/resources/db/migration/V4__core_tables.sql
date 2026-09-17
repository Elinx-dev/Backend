-- Property, transactions, parties, consent, documents, fees, registration result.
-- Reference: SLATE Database Design §5, Application Design §8.3–8.4

CREATE TABLE core.property (
  id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code                  CHAR(2) NOT NULL,
  property_ref                TEXT NOT NULL UNIQUE,
  ulpin                       TEXT NULL,         -- external only; SLATE never generates it
  property_type_code          TEXT NOT NULL,
  nature_of_title_code        TEXT,
  land_type_code              TEXT,
  classification_code         TEXT,
  extent_value                NUMERIC(18,4) NOT NULL,
  extent_unit                 TEXT NOT NULL,
  survey_no                   TEXT NOT NULL,
  subdivision_no              TEXT,
  old_survey_reference        TEXT,
  fmb_reference_no            TEXT,
  district_code               TEXT NOT NULL,
  taluk_code                  TEXT,
  village_code                TEXT,
  sro_code                    TEXT NOT NULL,
  panchayat                   TEXT,
  ward_no                     TEXT,
  street                      TEXT,
  door_no                     TEXT,
  boundary_north              TEXT,
  boundary_south              TEXT,
  boundary_east               TEXT,
  boundary_west               TEXT,
  guideline_value             NUMERIC(18,2),
  guideline_value_reference   TEXT,
  guideline_value_entry_date  DATE,
  is_apartment_unit           BOOLEAN NOT NULL DEFAULT FALSE,
  parent_property_id          BIGINT REFERENCES core.property(id),
  token_id                    BIGINT,            -- FK added in V8; NULL until first registration
  status                      TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUPERSEDED')),
  ext                         JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by                  BIGINT,
  updated_at                  TIMESTAMPTZ,
  updated_by                  BIGINT
);
CREATE UNIQUE INDEX ux_property_ulpin ON core.property (state_code, ulpin) WHERE ulpin IS NOT NULL;
CREATE INDEX ix_property_survey ON core.property (state_code, village_code, survey_no, subdivision_no);
CREATE INDEX ix_property_ext ON core.property USING gin (ext jsonb_path_ops);

CREATE TABLE core.property_apartment_detail (
  property_id            BIGINT PRIMARY KEY REFERENCES core.property(id),
  parent_land_property_id BIGINT REFERENCES core.property(id),
  flat_no                TEXT,
  block_tower            TEXT,
  floor                  TEXT,
  builtup_area           NUMERIC(18,4),
  area_unit              TEXT,
  uds_fraction           NUMERIC(10,6),
  parent_survey_no       TEXT,
  parent_subdivision_no  TEXT
);

CREATE TABLE core.transaction (
  id                            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code                    CHAR(2) NOT NULL,
  txn_ref                       TEXT NOT NULL UNIQUE,
  property_id                   BIGINT NOT NULL REFERENCES core.property(id),
  deed_type_code                TEXT NOT NULL,
  subtype                       TEXT,
  workflow_id                   BIGINT NOT NULL REFERENCES cfg.workflow_definition(id),
  config_version                INT NOT NULL,
  transfer_scope                TEXT CHECK (transfer_scope IN
                                  ('FULL_PROPERTY','UNDIVIDED_SHARE','PHYSICAL_PARTIAL_EXTENT_SUBDIVISION')),
  survey_required               BOOLEAN NOT NULL,     -- derived, never entered
  status                        TEXT NOT NULL,
  current_stage_code            TEXT NOT NULL,
  sro_code                      TEXT NOT NULL,
  relationship_category         TEXT,
  declared_consideration        NUMERIC(18,2),
  mode_of_consideration         TEXT,
  extent_or_share_transferred   NUMERIC(18,4),
  extent_unit                   TEXT,
  guideline_value               NUMERIC(18,2),
  guideline_value_reference     TEXT,
  basis_of_settlement           TEXT,
  share_being_released          NUMERIC(7,4),
  resulting_subparcel_count     INT,
  remarks                       TEXT,
  idempotency_key               UUID NOT NULL UNIQUE,
  initiated_by                  BIGINT NOT NULL,
  initiated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  registered_at                 TIMESTAMPTZ,
  withdrawn_at                  TIMESTAMPTZ,
  withdrawn_reason              TEXT,
  ext                           JSONB NOT NULL DEFAULT '{}'::jsonb,
  CONSTRAINT ck_no_forbidden_status CHECK (status NOT IN ('REJECTED','RETURNED','CANCELLED'))
);
CREATE INDEX ix_txn_queue ON core.transaction (state_code, sro_code, status, initiated_at DESC);
CREATE INDEX ix_txn_property ON core.transaction (property_id);

CREATE TABLE core.property_owner (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  property_id    BIGINT NOT NULL REFERENCES core.property(id),
  owner_name     TEXT NOT NULL,
  party_ref      TEXT,
  share_pct      NUMERIC(7,4) NULL,   -- nullable by design; never defaulted to an equal split
  share_note     TEXT,
  source         TEXT NOT NULL CHECK (source IN ('PROPERTY_ENTRY','REGISTRATION','REVENUE_APPROVED')),
  transaction_id BIGINT REFERENCES core.transaction(id),
  effective_from DATE NOT NULL,
  effective_to   DATE
);
CREATE INDEX ix_owner_current ON core.property_owner (property_id) WHERE effective_to IS NULL;

CREATE TABLE core.chain_of_title (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  property_id           BIGINT NOT NULL REFERENCES core.property(id),
  seq                   INT NOT NULL,
  executor_name         TEXT,
  claimant_name         TEXT,
  transaction_date      DATE,
  nature_of_transaction TEXT,
  reference_no          TEXT,
  survey_no             TEXT,
  date_of_death         DATE,
  legal_heirs           JSONB,
  prior_owner_name      TEXT,
  period_from           DATE,
  period_to             DATE,
  UNIQUE (property_id, seq)
);

CREATE TABLE core.property_measurement (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  property_id BIGINT NOT NULL REFERENCES core.property(id),
  seq         INT NOT NULL,
  from_point  TEXT,
  to_point    TEXT,
  value       NUMERIC(18,4),
  unit        TEXT,
  UNIQUE (property_id, seq)
);

CREATE TABLE core.transaction_party (
  id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id          BIGINT NOT NULL REFERENCES core.transaction(id),
  side                    TEXT NOT NULL CHECK (side IN ('SIDE_1','SIDE_2')),
  role                    TEXT NOT NULL,
  seq                     INT NOT NULL,
  party_type              TEXT NOT NULL CHECK (party_type IN
                            ('INDIVIDUAL','HUF','INSTITUTION','AUTHORISED_REPRESENTATIVE')),
  party_ref               TEXT,
  name                    TEXT NOT NULL,
  aadhaar_hash            BYTEA,        -- salted SHA-256; the number itself is never stored
  aadhaar_last4           CHAR(4),
  aadhaar_salt_ref        TEXT,
  karta_name              TEXT,
  karta_aadhaar_hash      BYTEA,
  pan                     TEXT,
  address                 TEXT,
  mobile_hash             BYTEA,
  relationship_code       TEXT,
  existing_share_pct      NUMERIC(7,4),
  share_transferred_pct   NUMERIC(7,4),
  extent_transferred      NUMERIC(18,4),
  resulting_share_pct     NUMERIC(7,4),
  authority_poa_reference TEXT,
  ext                     JSONB NOT NULL DEFAULT '{}'::jsonb,
  UNIQUE (transaction_id, side, seq)
);

CREATE TABLE core.consent_record (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id        BIGINT NOT NULL REFERENCES core.transaction(id),
  party_id              BIGINT NOT NULL REFERENCES core.transaction_party(id),
  aadhaar_hash          BYTEA NOT NULL,
  otp_request_reference TEXT NOT NULL,
  status                TEXT NOT NULL CHECK (status IN ('PENDING','VERIFIED','FAILED','EXPIRED')),
  requested_at          TIMESTAMPTZ NOT NULL,
  verified_at           TIMESTAMPTZ,
  attempt_count         INT NOT NULL DEFAULT 0,
  device_id             TEXT,
  officer_user_id       BIGINT,
  consent_text_version  TEXT NOT NULL,
  UNIQUE (transaction_id, party_id)
);

CREATE TABLE core.witness (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id BIGINT NOT NULL REFERENCES core.transaction(id),
  seq            INT NOT NULL,
  name           TEXT NOT NULL,
  address        TEXT,
  id_proof_type  TEXT,
  id_proof_ref   TEXT,
  UNIQUE (transaction_id, seq)
);

CREATE TABLE core.document (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code         CHAR(2) NOT NULL,
  transaction_id     BIGINT REFERENCES core.transaction(id),
  property_id        BIGINT REFERENCES core.property(id),
  document_type_code TEXT NOT NULL,
  file_name          TEXT NOT NULL,
  mime_type          TEXT NOT NULL,
  size_bytes         BIGINT NOT NULL,
  storage_bucket     TEXT NOT NULL,
  storage_key        TEXT NOT NULL,
  sha256             BYTEA NOT NULL,
  uploaded_by        BIGINT NOT NULL,
  uploaded_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  merkle_batch_id    BIGINT,   -- FK added in V8
  anchored_tx_hash   TEXT,
  CHECK (transaction_id IS NOT NULL OR property_id IS NOT NULL)
);
CREATE INDEX ix_doc_sha ON core.document (sha256);

CREATE TABLE core.fee_calculation (
  id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id         BIGINT NOT NULL REFERENCES core.transaction(id),
  fee_master_id          BIGINT NOT NULL REFERENCES master.fee_master(id),
  fee_master_version     INT NOT NULL,
  valuation_basis_used   TEXT NOT NULL,
  valuation_amount       NUMERIC(18,2) NOT NULL,
  stamp_duty             NUMERIC(18,2) NOT NULL,
  registration_fee       NUMERIC(18,2) NOT NULL,
  tds_amount             NUMERIC(18,2) DEFAULT 0,
  other_charges          NUMERIC(18,2) DEFAULT 0,
  total_payable          NUMERIC(18,2) NOT NULL,
  calculation_input_json JSONB NOT NULL,
  calculated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE core.payment (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id BIGINT NOT NULL REFERENCES core.transaction(id),
  mode           TEXT NOT NULL,
  reference_no   TEXT NOT NULL,
  amount         NUMERIC(18,2) NOT NULL,
  paid_at        TIMESTAMPTZ NOT NULL,
  received_by    BIGINT,
  status         TEXT NOT NULL CHECK (status IN ('INITIATED','SUCCESS','FAILED','REFUNDED')),
  UNIQUE (transaction_id, reference_no)
);

CREATE TABLE core.registration_result (
  id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id         BIGINT NOT NULL UNIQUE REFERENCES core.transaction(id),
  registered_document_no TEXT NOT NULL,
  registration_year      INT NOT NULL,
  registration_date      DATE NOT NULL,
  registering_sro        TEXT NOT NULL,
  registration_status    TEXT NOT NULL DEFAULT 'REGISTERED' CHECK (registration_status = 'REGISTERED'),
  deed_document_id       BIGINT REFERENCES core.document(id),
  deed_sha256            BYTEA NOT NULL,
  registration_reference TEXT,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
