-- Revenue verification and mutation. VAO verifies and forwards; only the Tahsildar
-- issues the authoritative output. There is no Reject and no Return anywhere here.

CREATE TABLE revenue.current_state (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  property_id         BIGINT NOT NULL REFERENCES core.property(id),
  revenue_record_ref  TEXT,
  revenue_survey_no   TEXT,
  revenue_subdivision_no TEXT,
  land_context        TEXT CHECK (land_context IN ('RURAL','NATHAM')),
  owners              JSONB NOT NULL,   -- list; shares only where the source provides them
  extent_value        NUMERIC(18,4),
  extent_unit         TEXT,
  classification      TEXT,
  assessment_tax      TEXT,
  record_status       TEXT,
  source_reference    TEXT,
  fetched_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_rev_current_prop ON revenue.current_state (property_id, fetched_at DESC);

CREATE TABLE revenue.proposed_mutation (
  id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code               CHAR(2) NOT NULL,
  transaction_id           BIGINT NOT NULL REFERENCES core.transaction(id),
  property_id              BIGINT NOT NULL REFERENCES core.property(id),
  registration_result_id   BIGINT NOT NULL REFERENCES core.registration_result(id),
  current_state_id         BIGINT REFERENCES revenue.current_state(id),
  proposed_owner_set       JSONB NOT NULL,   -- no Aadhaar, masked or otherwise
  proposed_share_or_extent JSONB,
  resulting_ownership      JSONB,
  mutation_type            TEXT NOT NULL CHECK (mutation_type IN
                             ('FULL_PROPERTY_TRANSFER','JOINT_OWNERSHIP','UNDIVIDED_SHARE','SUBDIVISION',
                              'GIFT_SETTLEMENT_TRANSFER','RELEASE_RELINQUISHMENT','OTHER')),
  survey_submission_id     BIGINT REFERENCES survey.submission(id),
  status                   TEXT NOT NULL CHECK (status IN
                             ('VAO_PENDING','OBJECTION_PENDING','TAHSILDAR_PENDING','REVENUE_APPROVED')),
  vao_verified_by          BIGINT,
  vao_verified_at          TIMESTAMPTZ,
  vao_remarks              TEXT,
  tahsildar_remarks        TEXT,
  created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (transaction_id)
);
CREATE INDEX ix_mutation_queue ON revenue.proposed_mutation (state_code, status, created_at);

CREATE TABLE revenue.objection (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  mutation_id         BIGINT NOT NULL REFERENCES revenue.proposed_mutation(id),
  notice_date         DATE,
  objection_due_date  DATE,
  objection_received  BOOLEAN NOT NULL DEFAULT FALSE,
  objector_name       TEXT,
  objection_date      DATE,
  objection_reason    TEXT,
  supporting_document_id BIGINT REFERENCES core.document(id),
  hearing_date        DATE,
  disposal_decision   TEXT,
  recorded_by         BIGINT,
  recorded_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE revenue.approved_record (
  id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  mutation_id             BIGINT NOT NULL UNIQUE REFERENCES revenue.proposed_mutation(id),
  revenue_record_number   TEXT NOT NULL,   -- issued by the authoritative Revenue system
  mutation_register_number TEXT,
  mutation_date           DATE NOT NULL,
  approved_by             BIGINT NOT NULL,
  approved_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  approved_owner_set      JSONB NOT NULL,
  source_reference        TEXT
);
