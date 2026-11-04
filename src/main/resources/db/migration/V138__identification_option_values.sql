-- Mint Property -> Identification offers only:
--   Nature of title: Freehold, Leasehold
--   Land type:       Rural, Urban
--   Classification:  Wet, Dry
-- Other values are deactivated, not deleted, so existing properties keep their codes.

INSERT INTO cfg.option_value (option_set_code, state_code, value_code, label, sort_order, active) VALUES
  ('NATURE_OF_TITLE', '*', 'FREEHOLD',  'Freehold',  1, TRUE),
  ('NATURE_OF_TITLE', '*', 'LEASEHOLD', 'Leasehold', 2, TRUE),
  ('LAND_TYPE',       '*', 'RURAL',     'Rural',     1, TRUE),
  ('LAND_TYPE',       '*', 'URBAN',     'Urban',     2, TRUE),
  ('CLASSIFICATION',  '*', 'WET',       'Wet',       1, TRUE),
  ('CLASSIFICATION',  '*', 'DRY',       'Dry',       2, TRUE)
ON CONFLICT (option_set_code, state_code, value_code)
DO UPDATE SET label = EXCLUDED.label, sort_order = EXCLUDED.sort_order, active = TRUE;

UPDATE cfg.option_value SET active = FALSE
 WHERE (option_set_code = 'NATURE_OF_TITLE' AND value_code NOT IN ('FREEHOLD', 'LEASEHOLD'))
    OR (option_set_code = 'LAND_TYPE'       AND value_code NOT IN ('RURAL', 'URBAN'))
    OR (option_set_code = 'CLASSIFICATION'  AND value_code NOT IN ('WET', 'DRY'));

-- Urban land is now mintable, so the EC and Revenue / Patta checks must cover it.
UPDATE cfg.rule_engine_config
   SET supported_land_types = array_append(supported_land_types, 'URBAN')
 WHERE NOT ('URBAN' = ANY (supported_land_types));

ALTER TABLE cfg.rule_engine_config ALTER COLUMN supported_land_types SET DEFAULT '{RURAL,URBAN,NATHAM}';

ALTER TABLE rules.revenue_ownership_snapshot DROP CONSTRAINT revenue_ownership_snapshot_land_type_check;
ALTER TABLE rules.revenue_ownership_snapshot ADD CONSTRAINT revenue_ownership_snapshot_land_type_check
  CHECK (land_type IN ('RURAL', 'URBAN', 'NATHAM'));

ALTER TABLE revenue.current_state DROP CONSTRAINT current_state_land_context_check;
ALTER TABLE revenue.current_state ADD CONSTRAINT current_state_land_context_check
  CHECK (land_context IN ('RURAL', 'URBAN', 'NATHAM'));
