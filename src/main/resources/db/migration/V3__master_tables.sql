-- Reference/master data, state-keyed. Reference: SLATE Database Design §4

CREATE TABLE master.jurisdiction (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code       CHAR(2) NOT NULL REFERENCES cfg.state(state_code),
  district_code    TEXT NOT NULL,
  district_name    TEXT NOT NULL,
  taluk_code       TEXT,
  taluk_name       TEXT,
  village_code     TEXT,
  village_name     TEXT,
  sro_code         TEXT,
  sro_name         TEXT,
  revenue_division TEXT,
  panchayat        TEXT,
  active           BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (state_code, district_code, taluk_code, village_code, sro_code)
);
CREATE INDEX ix_juris_sro ON master.jurisdiction (state_code, sro_code);

CREATE TABLE master.deed_type (
  id                             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code                     CHAR(2) NOT NULL,
  code                           TEXT NOT NULL,
  name                           TEXT NOT NULL,
  workflow_family                TEXT NOT NULL,
  survey_rule                    TEXT NOT NULL CHECK (survey_rule IN
                                   ('ALWAYS','NEVER','DERIVED_FROM_TRANSFER_SCOPE')),
  side1_role                     TEXT NOT NULL,
  side2_role                     TEXT NOT NULL,
  witness_required               BOOLEAN NOT NULL DEFAULT TRUE,
  min_witness_count              INT NOT NULL DEFAULT 2,
  requires_relationship_category BOOLEAN NOT NULL DEFAULT FALSE,
  consent_required               BOOLEAN NOT NULL DEFAULT TRUE,
  active                         BOOLEAN NOT NULL DEFAULT TRUE,
  effective_from                 DATE NOT NULL,
  effective_to                   DATE,
  UNIQUE (state_code, code, effective_from)
);

CREATE TABLE master.fee_master (
  id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code                CHAR(2) NOT NULL,
  deed_type_code            TEXT NOT NULL,
  subtype                   TEXT NOT NULL DEFAULT '*',
  relationship_category     TEXT NOT NULL DEFAULT '*',
  transfer_nature           TEXT NOT NULL DEFAULT '*',
  valuation_basis           TEXT NOT NULL,
  stamp_duty_rate           NUMERIC(7,4),
  stamp_duty_flat           NUMERIC(18,2),
  stamp_duty_min            NUMERIC(18,2),
  stamp_duty_max            NUMERIC(18,2),
  registration_fee_rate     NUMERIC(7,4),
  registration_fee_flat     NUMERIC(18,2),
  registration_fee_min      NUMERIC(18,2),
  registration_fee_max      NUMERIC(18,2),
  tds_threshold             NUMERIC(18,2),
  tds_rate                  NUMERIC(7,4),
  other_charges             JSONB,
  gazette_reference         TEXT,
  approved_by               TEXT,
  approval_date             DATE,
  version                   INT NOT NULL,
  effective_from            DATE NOT NULL,
  effective_to              DATE,
  UNIQUE (state_code, deed_type_code, subtype, relationship_category,
          transfer_nature, valuation_basis, effective_from)
);
CREATE INDEX ix_fee_lookup ON master.fee_master
  (state_code, deed_type_code, subtype, relationship_category, transfer_nature)
  WHERE effective_to IS NULL;

CREATE TABLE master.guideline_value (
  id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code             CHAR(2) NOT NULL,
  district_code          TEXT,
  village_code           TEXT,
  street_or_zone         TEXT,
  survey_no              TEXT,
  land_type_code         TEXT,
  rate_per_unit          NUMERIC(18,2) NOT NULL,
  unit                   TEXT NOT NULL,
  notification_reference TEXT NOT NULL,
  effective_from         DATE NOT NULL,
  effective_to           DATE
);

CREATE TABLE master.document_type (
  id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code                TEXT NOT NULL DEFAULT '*',
  code                      TEXT NOT NULL,
  name                      TEXT NOT NULL,
  applies_to                TEXT[] NOT NULL,
  mandatory_for_deed_types  TEXT[],
  allowed_mime              TEXT[] NOT NULL,
  max_size_mb               INT NOT NULL DEFAULT 10,
  retain_years              INT,
  active                    BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (state_code, code)
);

-- Relationship carries a fee category, so unlike land type/classification it is a
-- master table rather than a plain cfg.option_value row.
CREATE TABLE master.relationship (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code   TEXT NOT NULL DEFAULT '*',
  code         TEXT NOT NULL,
  name         TEXT NOT NULL,
  fee_category TEXT NOT NULL,
  sort_order   INT NOT NULL DEFAULT 0,
  active       BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (state_code, code)
);

CREATE TABLE master.system_config (
  key         TEXT PRIMARY KEY,
  value_json  JSONB NOT NULL,
  description TEXT
);
