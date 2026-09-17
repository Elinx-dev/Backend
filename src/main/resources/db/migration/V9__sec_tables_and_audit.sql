-- Users, roles, permissions, jurisdiction scoping, and the append-only audit log.

CREATE TABLE sec.user (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code            CHAR(2) NOT NULL,
  username              CITEXT NOT NULL UNIQUE,
  full_name             TEXT NOT NULL,
  email                 CITEXT,
  mobile                TEXT,
  employee_code         TEXT,
  designation           TEXT,
  department            TEXT NOT NULL CHECK (department IN
                          ('REGISTRATION','SURVEY','REVENUE','ADMIN','PUBLIC')),
  password_hash         TEXT NOT NULL,
  password_algo         TEXT NOT NULL DEFAULT 'bcrypt',
  mfa_required          BOOLEAN NOT NULL DEFAULT TRUE,
  status                TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED','DISABLED')),
  failed_login_count    INT NOT NULL DEFAULT 0,
  last_login_at         TIMESTAMPTZ,
  password_changed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sec.role (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        TEXT NOT NULL UNIQUE,
  name        TEXT NOT NULL,
  department  TEXT NOT NULL,
  description TEXT
);

CREATE TABLE sec.permission (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        TEXT NOT NULL UNIQUE,
  description TEXT NOT NULL
);

CREATE TABLE sec.role_permission (
  role_id       BIGINT NOT NULL REFERENCES sec.role(id),
  permission_id BIGINT NOT NULL REFERENCES sec.permission(id),
  PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE sec.user_role (
  user_id BIGINT NOT NULL REFERENCES sec.user(id),
  role_id BIGINT NOT NULL REFERENCES sec.role(id),
  PRIMARY KEY (user_id, role_id)
);

CREATE TABLE sec.user_jurisdiction (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id       BIGINT NOT NULL REFERENCES sec.user(id),
  state_code    CHAR(2) NOT NULL,
  district_code TEXT,
  taluk_code    TEXT,
  village_code  TEXT,
  sro_code      TEXT,
  UNIQUE (user_id, state_code, district_code, taluk_code, village_code, sro_code)
);

CREATE TABLE sec.login_otp (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id      BIGINT NOT NULL REFERENCES sec.user(id),
  otp_hash     TEXT NOT NULL,
  challenge_id UUID NOT NULL UNIQUE,
  issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at   TIMESTAMPTZ NOT NULL,
  consumed_at  TIMESTAMPTZ,
  attempt_count INT NOT NULL DEFAULT 0
);

CREATE TABLE sec.user_session (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id      BIGINT NOT NULL REFERENCES sec.user(id),
  jti          UUID NOT NULL UNIQUE,
  issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at   TIMESTAMPTZ NOT NULL,
  revoked_at   TIMESTAMPTZ,
  ip_address   TEXT,
  user_agent   TEXT
);

-- Append-only. Every state-changing endpoint writes a row before it returns.
CREATE TABLE sec.audit_log (
  id              BIGINT GENERATED ALWAYS AS IDENTITY,
  state_code      CHAR(2) NOT NULL,
  occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_user_id   BIGINT,
  actor_username  TEXT,
  actor_role      TEXT,
  action          TEXT NOT NULL,
  entity_type     TEXT NOT NULL,
  entity_id       TEXT,
  transaction_ref TEXT,
  property_ref    TEXT,
  before_json     JSONB,
  after_json      JSONB,
  request_id      TEXT,
  idempotency_key TEXT,
  ip_address      TEXT,
  outcome         TEXT NOT NULL CHECK (outcome IN ('SUCCESS','FAILURE')),
  detail          TEXT,
  PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE sec.audit_log_default PARTITION OF sec.audit_log DEFAULT;
CREATE TABLE sec.audit_log_2026 PARTITION OF sec.audit_log
  FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE sec.audit_log_2027 PARTITION OF sec.audit_log
  FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');

CREATE INDEX ix_audit_entity ON sec.audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_property ON sec.audit_log (property_ref, occurred_at DESC);
CREATE INDEX ix_audit_actor ON sec.audit_log (actor_user_id, occurred_at DESC);

CREATE TABLE sec.idempotency_record (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  scope           TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  request_hash    BYTEA NOT NULL,
  response_status INT NOT NULL,
  response_body   JSONB,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (scope, idempotency_key)
);
