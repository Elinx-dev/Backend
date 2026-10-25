-- State admin toggles: real Aadhaar OTP API / real login email OTP (on) or the mock OTP 123456 (off).
INSERT INTO cfg.feature_flag (state_code, flag_code, enabled, description)
SELECT s.code, f.flag_code, FALSE, f.description
  FROM (SELECT '*' AS code UNION ALL SELECT state_code FROM cfg.state) s
 CROSS JOIN (VALUES
     ('AADHAAR_OTP_LIVE', 'Aadhaar consent OTP: on = real Aadhaar OTP API, off = mock OTP 123456'),
     ('LOGIN_EMAIL_OTP', 'Login verification code: on = emailed to the user''s registered email, off = mock OTP 123456')
 ) AS f(flag_code, description)
ON CONFLICT (state_code, flag_code) DO NOTHING;

-- The live Aadhaar OTP API needs the full number; it is kept encrypted only until that party's consent is verified.
ALTER TABLE core.transaction_party ADD COLUMN IF NOT EXISTS aadhaar_enc TEXT;
ALTER TABLE core.consent_record ADD COLUMN IF NOT EXISTS otp_mode TEXT NOT NULL DEFAULT 'MOCK';
