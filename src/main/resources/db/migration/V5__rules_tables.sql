-- Rule check engines. Advisory during the pilot: a result never gates a transition.
-- Reference: SLATE Rule Check Engines Specification; Database Design §6

CREATE TABLE rules.survey_lineage (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code        CHAR(2) NOT NULL,
  district_code     TEXT NOT NULL,
  taluk_code        TEXT NOT NULL,
  revenue_village   TEXT NOT NULL,
  from_survey_no    TEXT NOT NULL,
  from_subdivision  TEXT,
  to_survey_no      TEXT NOT NULL,
  to_subdivision    TEXT,
  effective_date    DATE,
  reason            TEXT CHECK (reason IN ('SPLIT','MERGE','RENUMBER','CORRECTION')),
  source_reference  TEXT NOT NULL,   -- authoritative source only; no inferred lineage
  verified_at       TIMESTAMPTZ,
  verified_by       TEXT
);
CREATE INDEX ix_lineage_from ON rules.survey_lineage
  (state_code, district_code, taluk_code, revenue_village, from_survey_no);

CREATE TABLE rules.rule_check_request (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code      CHAR(2) NOT NULL,
  transaction_id  BIGINT NOT NULL REFERENCES core.transaction(id),
  property_id     BIGINT NOT NULL REFERENCES core.property(id),
  engine          TEXT NOT NULL CHECK (engine IN ('EC','REVENUE_OWNERSHIP')),
  mode            TEXT NOT NULL CHECK (mode IN
                    ('PILOT_CURRENT_RECONCILIATION','PRE_REGISTRATION_CLEARANCE')),
  assessment_date DATE,
  request_payload JSONB NOT NULL,
  idempotency_key UUID NOT NULL UNIQUE,
  requested_by    BIGINT,
  requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  connector_mode  TEXT NOT NULL CHECK (connector_mode IN ('MOCK','LIVE'))
);
CREATE INDEX ix_rcr_txn ON rules.rule_check_request (transaction_id, engine, requested_at DESC);

CREATE TABLE rules.rule_check_result (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id      BIGINT NOT NULL REFERENCES rules.rule_check_request(id),
  engine          TEXT NOT NULL,
  overall_outcome TEXT NOT NULL CHECK (overall_outcome IN
                    ('NO_DISCREPANCY_DETECTED','DISCREPANCY_DETECTED','REVIEW_REQUIRED','NOT_CHECKED')),
  reason_code     TEXT,
  result_payload  JSONB NOT NULL,
  result_hash     BYTEA NOT NULL,
  advisory        BOOLEAN NOT NULL DEFAULT TRUE,
  checked_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_rcres_request ON rules.rule_check_result (request_id);

CREATE TABLE rules.ec_certificate (
  id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id             BIGINT NOT NULL REFERENCES rules.rule_check_request(id),
  certificate_no         TEXT NOT NULL,
  issued_date            DATE,
  village_name           TEXT,
  survey_numbers_searched TEXT[],
  requested_from         DATE NOT NULL,
  requested_to           DATE NOT NULL,
  data_available_from    DATE,
  data_available_to      DATE,
  coverage_status        TEXT CHECK (coverage_status IN ('COMPLETE_AVAILABLE_COVERAGE','PARTIAL_COVERAGE')),
  raw_payload            JSONB NOT NULL,
  fetched_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE rules.ec_entry (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  certificate_id     BIGINT NOT NULL REFERENCES rules.ec_certificate(id) ON DELETE CASCADE,
  document_id        TEXT NOT NULL,          -- docNo/docYear, exactly as printed
  doc_no             TEXT NOT NULL,
  doc_year           INT NOT NULL,
  execution_date     DATE,
  presentation_date  DATE,
  registration_date  DATE,
  nature             TEXT,
  classified_type    TEXT CHECK (classified_type IN
                       ('MORTGAGE_CREATE','RECEIPT','COURT_ORDER',
                        'NON_ENCUMBRANCE_EVENT','UNCLASSIFIED_ENTRY')),
  match_status       TEXT CHECK (match_status IN ('MATCH','HISTORICAL_MATCH','AMBIGUOUS','NOT_MATCHED')),
  executants         TEXT[],
  claimants          TEXT[],
  consideration_value NUMERIC(18,2),
  market_value       NUMERIC(18,2),
  previous_doc_refs  JSONB,
  remarks            TEXT,
  schedules          JSONB
);
CREATE INDEX ix_ec_entry_cert ON rules.ec_entry (certificate_id);

CREATE TABLE rules.ec_mortgage_record (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  certificate_id      BIGINT NOT NULL REFERENCES rules.ec_certificate(id) ON DELETE CASCADE,
  document_id         TEXT NOT NULL,
  registration_date   DATE,
  mortgagor           TEXT,
  mortgagee           TEXT,
  property_schedule   JSONB,
  status              TEXT NOT NULL CHECK (status IN ('OPEN','DISCHARGED')),
  release_document_id TEXT,
  release_date        DATE,
  active_on_assessment BOOLEAN,
  UNIQUE (certificate_id, document_id)
);

CREATE TABLE rules.revenue_ownership_snapshot (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id      BIGINT NOT NULL REFERENCES rules.rule_check_request(id),
  property_id     BIGINT NOT NULL REFERENCES core.property(id),
  record_no       TEXT,                 -- display only; never a lookup key
  district_code   TEXT,
  taluk_code      TEXT,
  revenue_village TEXT,
  survey_no       TEXT,
  subdivision_no  TEXT,
  old_survey_no   TEXT,
  land_type       TEXT CHECK (land_type IN ('RURAL','NATHAM')),
  classification  TEXT,
  extent_value    NUMERIC(18,4),
  extent_unit     TEXT,
  record_status   TEXT,
  mutation_status TEXT,
  last_updated_at TIMESTAMPTZ,
  portal_reference TEXT,
  parcel_match_status TEXT CHECK (parcel_match_status IN
                       ('PARCEL_MATCH','PARCEL_NOT_FOUND','SURVEY_IDENTITY_UNRESOLVED','MULTIPLE_REVENUE_RECORDS')),
  lineage_applied BOOLEAN NOT NULL DEFAULT FALSE,
  raw_payload     JSONB NOT NULL,
  queried_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE rules.revenue_owner (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  snapshot_id         BIGINT NOT NULL REFERENCES rules.revenue_ownership_snapshot(id) ON DELETE CASCADE,
  seq                 INT NOT NULL,
  name                TEXT NOT NULL,
  relation_type       TEXT,
  related_person_name TEXT,
  share_pct           NUMERIC(7,4),   -- only when the source itself provides it
  UNIQUE (snapshot_id, seq)
);

CREATE TABLE rules.name_match_audit (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id     BIGINT NOT NULL REFERENCES rules.rule_check_request(id),
  left_name      TEXT NOT NULL,
  left_source    TEXT NOT NULL,
  right_name     TEXT NOT NULL,
  right_source   TEXT NOT NULL,
  normalized_left  TEXT NOT NULL,
  normalized_right TEXT NOT NULL,
  decision       TEXT NOT NULL CHECK (decision IN ('MATCH','POSSIBLE_NAME_MATCH','NO_MATCH')),
  rationale      TEXT NOT NULL
);
