-- State-level control of which rule check outcomes may proceed past the Rule checks stage.
ALTER TABLE cfg.rule_engine_config
  ADD COLUMN IF NOT EXISTS allowed_outcomes TEXT[] NOT NULL
    DEFAULT '{NO_DISCREPANCY_DETECTED,NOT_CHECKED}';

ALTER TABLE cfg.rule_engine_config
  ADD CONSTRAINT rule_engine_config_allowed_outcomes_check CHECK (
    allowed_outcomes <@ ARRAY['NO_DISCREPANCY_DETECTED','DISCREPANCY_DETECTED','REVIEW_REQUIRED','NOT_CHECKED']::text[]
    AND 'NO_DISCREPANCY_DETECTED' = ANY (allowed_outcomes));
