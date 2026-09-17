-- Survey sub-workflow. Reference: Workflow & Field Specification §"Survey & Revenue Sub-Workflow"

CREATE TABLE survey.site_visit (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code            CHAR(2) NOT NULL,
  transaction_id        BIGINT NOT NULL REFERENCES core.transaction(id),
  proposed_by_role      TEXT NOT NULL CHECK (proposed_by_role IN ('SURVEYOR','VAO')),
  proposed_by_user_id   BIGINT NOT NULL,
  visit_date            DATE NOT NULL,
  visit_time            TIME,
  counter_visit_date    DATE,
  counter_visit_time    TIME,
  counter_by_role       TEXT CHECK (counter_by_role IN ('SURVEYOR','VAO')),
  status                TEXT NOT NULL CHECK (status IN ('PROPOSED','COUNTER_PROPOSED','ACCEPTED','COMPLETED')),
  surveyor_checkin_at   TIMESTAMPTZ,     -- captured by the system, never typed in
  vao_checkin_at        TIMESTAMPTZ,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_site_visit_txn ON survey.site_visit (transaction_id);

CREATE TABLE survey.submission (
  id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code                  CHAR(2) NOT NULL,
  transaction_id              BIGINT NOT NULL REFERENCES core.transaction(id),
  site_visit_id               BIGINT REFERENCES survey.site_visit(id),
  survey_purpose              TEXT NOT NULL,   -- derived from the driving transaction
  survey_no                   TEXT NOT NULL,
  subdivision_no              TEXT,
  old_survey_reference        TEXT,
  fmb_sketch_reference        TEXT,
  authoritative_recorded_extent NUMERIC(18,4) NOT NULL,
  measured_extent             NUMERIC(18,4) NOT NULL,
  extent_unit                 TEXT NOT NULL,
  variance_pct                NUMERIC(7,4),
  tolerance_pct               NUMERIC(7,4) NOT NULL,
  within_tolerance            BOOLEAN NOT NULL,
  survey_date                 DATE NOT NULL,
  centroid_lat                NUMERIC(10,7),
  centroid_lon                NUMERIC(10,7),
  boundary_north              TEXT,
  boundary_south              TEXT,
  boundary_east               TEXT,
  boundary_west               TEXT,
  site_notes                  TEXT,
  resulting_parcel_count      INT NOT NULL DEFAULT 0,
  submitted_by                BIGINT NOT NULL,
  submitted_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  attestation_user_ref        TEXT NOT NULL,   -- departmental login, not Aadhaar OTP
  routed_to                   TEXT NOT NULL CHECK (routed_to IN ('VAO_VERIFICATION','SURVEY_CORRECTION_REVIEW'))
);
CREATE INDEX ix_submission_txn ON survey.submission (transaction_id);

CREATE TABLE survey.measurement_segment (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  submission_id   BIGINT NOT NULL REFERENCES survey.submission(id) ON DELETE CASCADE,
  parcel_id       BIGINT,
  seq             INT NOT NULL,
  from_point      TEXT,
  to_point        TEXT,
  length_value    NUMERIC(18,4),
  length_unit     TEXT,
  bearing         TEXT,
  adjoining_feature TEXT
);

CREATE TABLE survey.boundary_point (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  submission_id BIGINT NOT NULL REFERENCES survey.submission(id) ON DELETE CASCADE,
  parcel_id     BIGINT,
  seq           INT NOT NULL,
  point_label   TEXT,
  marker_status TEXT CHECK (marker_status IN ('PRESENT','MISSING','DISPUTED')),
  latitude      NUMERIC(10,7),
  longitude     NUMERIC(10,7)
);

CREATE TABLE survey.resulting_parcel (
  id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  submission_id             BIGINT NOT NULL REFERENCES survey.submission(id) ON DELETE CASCADE,
  seq                       INT NOT NULL,
  extent_value              NUMERIC(18,4) NOT NULL,
  extent_unit               TEXT NOT NULL,
  intended_owner_mapping    JSONB NOT NULL,
  official_subdivision_no   TEXT,      -- assigned later by the authoritative system
  child_property_id         BIGINT REFERENCES core.property(id),
  child_token_id            BIGINT,    -- FK added in V8
  geometry_geojson          JSONB,
  UNIQUE (submission_id, seq)
);

CREATE TABLE survey.attachment (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  submission_id BIGINT NOT NULL REFERENCES survey.submission(id) ON DELETE CASCADE,
  kind          TEXT NOT NULL CHECK (kind IN ('FMB_SKETCH','SITE_PHOTO','OTHER')),
  document_id   BIGINT NOT NULL REFERENCES core.document(id)
);
