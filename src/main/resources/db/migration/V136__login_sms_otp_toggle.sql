-- State admin toggle: login verification code by SMS (2Factor.in) to the user's registered mobile.
INSERT INTO cfg.feature_flag (state_code, flag_code, enabled, description)
SELECT s.code, 'LOGIN_SMS_OTP', FALSE,
       'Login verification code: on = SMS to the user''s registered mobile (takes precedence over email), off = email or mock OTP 123456'
  FROM (SELECT '*' AS code UNION ALL SELECT state_code FROM cfg.state) s
ON CONFLICT (state_code, flag_code) DO NOTHING;
