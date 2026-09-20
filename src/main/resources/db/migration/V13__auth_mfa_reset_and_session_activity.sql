-- Email MFA, single-use password reset links, and rolling idle-session enforcement.
ALTER TABLE sec.user_session
  ADD COLUMN last_activity_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE UNIQUE INDEX ux_user_email
  ON sec.user (email)
  WHERE email IS NOT NULL;

CREATE INDEX ix_user_session_active
  ON sec.user_session (jti, expires_at, last_activity_at)
  WHERE revoked_at IS NULL;

CREATE TABLE sec.password_reset_token (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id     BIGINT NOT NULL REFERENCES sec.user(id),
  token_hash  BYTEA NOT NULL UNIQUE,
  issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ
);

CREATE INDEX ix_password_reset_user_active
  ON sec.password_reset_token (user_id, expires_at)
  WHERE consumed_at IS NULL;
