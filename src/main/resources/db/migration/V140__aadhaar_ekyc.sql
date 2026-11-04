-- Aadhaar OTP e-KYC: the officer enters the Aadhaar number, the party gives the OTP, and the verified
-- Aadhaar profile fills the buyer / witness form. Only the encrypted number and its lookup hash are kept.
CREATE TABLE core.aadhaar_ekyc (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ekyc_ref              TEXT NOT NULL UNIQUE,
  state_code            TEXT NOT NULL,
  lookup_hash           TEXT NOT NULL,
  aadhaar_enc           TEXT NOT NULL,
  aadhaar_last4         TEXT NOT NULL,
  otp_request_reference TEXT NOT NULL,
  otp_mode              TEXT NOT NULL,
  status                TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'VERIFIED', 'FAILED')),
  profile               JSONB,
  attempt_count         INT NOT NULL DEFAULT 0,
  requested_by          BIGINT,
  requested_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  verified_at           TIMESTAMPTZ
);
CREATE INDEX ix_aadhaar_ekyc_lookup ON core.aadhaar_ekyc (state_code, lookup_hash, status, verified_at);
