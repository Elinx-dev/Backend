-- Fee Master drives every fee: status, calculation type per component, configured
-- other charges (flat or per page), relationship categories and partition schedules.

ALTER TABLE master.fee_master
  ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN IF NOT EXISTS stamp_duty_calc_type TEXT,
  ADD COLUMN IF NOT EXISTS registration_fee_calc_type TEXT;

UPDATE master.fee_master SET
  stamp_duty_calc_type = CASE WHEN stamp_duty_flat IS NOT NULL THEN 'FIXED'
                              WHEN stamp_duty_max IS NOT NULL THEN 'PERCENTAGE_WITH_CAP'
                              ELSE 'PERCENTAGE' END,
  registration_fee_calc_type = CASE WHEN registration_fee_flat IS NOT NULL THEN 'FIXED'
                                    WHEN registration_fee_max IS NOT NULL THEN 'PERCENTAGE_WITH_CAP'
                                    ELSE 'PERCENTAGE' END
 WHERE stamp_duty_calc_type IS NULL OR registration_fee_calc_type IS NULL;

-- Other charges become a list: [{"code","label","basis":"FLAT"|"PER_PAGE","amount"}].
UPDATE master.fee_master f
   SET other_charges = (
     SELECT coalesce(jsonb_agg(jsonb_build_object(
              'code', upper(regexp_replace(regexp_replace(e.key, 'PerPage$', ''), '([a-z])([A-Z])', '\1_\2', 'g')),
              'label', initcap(replace(regexp_replace(regexp_replace(e.key, 'PerPage$', ''), '([a-z])([A-Z])', '\1 \2', 'g'), '_', ' ')),
              'basis', CASE WHEN e.key LIKE '%PerPage' THEN 'PER_PAGE' ELSE 'FLAT' END,
              'amount', (e.value #>> '{}')::numeric) ORDER BY e.key), '[]'::jsonb)
       FROM jsonb_each(f.other_charges) e)
 WHERE jsonb_typeof(f.other_charges) = 'object';

ALTER TABLE master.fee_master
  ALTER COLUMN stamp_duty_calc_type SET NOT NULL,
  ALTER COLUMN registration_fee_calc_type SET NOT NULL,
  ADD CONSTRAINT fee_master_status_check CHECK (status IN ('ACTIVE', 'INACTIVE')),
  ADD CONSTRAINT fee_master_stamp_calc_type_check
    CHECK (stamp_duty_calc_type IN ('PERCENTAGE', 'PERCENTAGE_WITH_CAP', 'FIXED')),
  ADD CONSTRAINT fee_master_registration_calc_type_check
    CHECK (registration_fee_calc_type IN ('PERCENTAGE', 'PERCENTAGE_WITH_CAP', 'FIXED')),
  ADD CONSTRAINT fee_master_valuation_basis_check
    CHECK (valuation_basis IN ('GUIDELINE_VALUE', 'PROPERTY_VALUE', 'CONSIDERATION', 'HIGHER_OF_BOTH',
                               'SCHEDULE_VALUE', 'FLAT')),
  ADD CONSTRAINT fee_master_stamp_config_check CHECK (
    (stamp_duty_calc_type = 'FIXED' AND stamp_duty_flat IS NOT NULL)
    OR (stamp_duty_calc_type = 'PERCENTAGE' AND stamp_duty_rate IS NOT NULL)
    OR (stamp_duty_calc_type = 'PERCENTAGE_WITH_CAP' AND stamp_duty_rate IS NOT NULL AND stamp_duty_max IS NOT NULL)),
  ADD CONSTRAINT fee_master_registration_config_check CHECK (
    (registration_fee_calc_type = 'FIXED' AND registration_fee_flat IS NOT NULL)
    OR (registration_fee_calc_type = 'PERCENTAGE' AND registration_fee_rate IS NOT NULL)
    OR (registration_fee_calc_type = 'PERCENTAGE_WITH_CAP' AND registration_fee_rate IS NOT NULL
        AND registration_fee_max IS NOT NULL));

-- New versions of the rows whose values change; the old version stays for past calculations.
CREATE TEMP TABLE fee_master_change (
  deed_type_code TEXT, relationship_category TEXT, valuation_basis TEXT,
  stamp_type TEXT, stamp_rate NUMERIC, stamp_flat NUMERIC, stamp_max NUMERIC,
  reg_type TEXT, reg_rate NUMERIC, reg_flat NUMERIC, reg_max NUMERIC, source_category TEXT
) ON COMMIT DROP;

INSERT INTO fee_master_change VALUES
  ('SETTLEMENT', 'FAMILY',   'GUIDELINE_VALUE', 'PERCENTAGE_WITH_CAP', 0.01, NULL, 40000, 'PERCENTAGE_WITH_CAP', 0.01, NULL, 10000, 'FAMILY'),
  ('GIFT',       'FAMILY',   'GUIDELINE_VALUE', 'FIXED',               NULL, 0,    NULL,  'FIXED',               NULL, 0,    NULL,  'FAMILY'),
  ('RELEASE',    'FAMILY',   'GUIDELINE_VALUE', 'PERCENTAGE_WITH_CAP', 0.01, NULL, 40000, 'PERCENTAGE_WITH_CAP', 0.01, NULL, 10000, '*'),
  ('RELEASE',    'CO_OWNER', 'GUIDELINE_VALUE', 'PERCENTAGE_WITH_CAP', 0.01, NULL, 40000, 'PERCENTAGE_WITH_CAP', 0.01, NULL, 10000, '*'),
  ('PARTITION',  '*',        'SCHEDULE_VALUE',  'PERCENTAGE',          0.01, NULL, NULL,  'PERCENTAGE_WITH_CAP', 0.01, NULL, 25000, '*');

CREATE TEMP TABLE fee_master_source ON COMMIT DROP AS
SELECT DISTINCT ON (f.state_code, c.deed_type_code, c.relationship_category) f.id AS source_id, c.*,
       f.state_code, f.relationship_category = c.relationship_category AS replaces_source
  FROM fee_master_change c
  JOIN master.fee_master f
    ON f.deed_type_code = c.deed_type_code AND f.relationship_category = c.source_category
   AND f.subtype = '*' AND f.status = 'ACTIVE' AND f.effective_to IS NULL
 ORDER BY f.state_code, c.deed_type_code, c.relationship_category, f.effective_from DESC;

UPDATE master.fee_master f SET effective_to = current_date - 1
  FROM fee_master_source s
 WHERE f.id = s.source_id AND s.replaces_source;

INSERT INTO master.fee_master
  (state_code, deed_type_code, subtype, relationship_category, transfer_nature, valuation_basis,
   stamp_duty_rate, stamp_duty_flat, stamp_duty_min, stamp_duty_max,
   registration_fee_rate, registration_fee_flat, registration_fee_min, registration_fee_max,
   tds_threshold, tds_rate, other_charges, gazette_reference, approved_by, approval_date, version,
   effective_from, effective_to, status, stamp_duty_calc_type, registration_fee_calc_type)
SELECT f.state_code, s.deed_type_code, '*', s.relationship_category, f.transfer_nature, s.valuation_basis,
       s.stamp_rate, s.stamp_flat, NULL, s.stamp_max,
       s.reg_rate, s.reg_flat, NULL, s.reg_max,
       f.tds_threshold, f.tds_rate, f.other_charges, f.gazette_reference, f.approved_by, f.approval_date,
       CASE WHEN s.replaces_source THEN f.version + 1 ELSE 1 END,
       current_date, NULL, 'ACTIVE', s.stamp_type, s.reg_type
  FROM fee_master_source s
  JOIN master.fee_master f ON f.id = s.source_id;

-- Relationship categories offered per transaction type, matched to fee_master.relationship_category.
CREATE TABLE IF NOT EXISTS master.fee_relationship_category (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code         TEXT NOT NULL DEFAULT '*',
  code               TEXT NOT NULL,
  label              TEXT NOT NULL,
  transaction_types  TEXT[] NOT NULL,
  display_order      INT NOT NULL DEFAULT 0,
  status             TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
  UNIQUE (state_code, code)
);

INSERT INTO master.fee_relationship_category (state_code, code, label, transaction_types, display_order)
VALUES ('*', 'FAMILY', 'Family', ARRAY['GIFT', 'SETTLEMENT', 'RELEASE'], 1),
       ('*', 'NON_FAMILY', 'Non-family', ARRAY['GIFT', 'SETTLEMENT'], 2),
       ('*', 'CO_OWNER', 'Co-owner', ARRAY['RELEASE'], 3)
ON CONFLICT (state_code, code) DO NOTHING;

ALTER TABLE master.transaction_type ADD COLUMN IF NOT EXISTS default_relationship_category TEXT;
UPDATE master.transaction_type SET default_relationship_category = 'FAMILY'
 WHERE blood_relation_required AND default_relationship_category IS NULL;

-- Partition schedules: a value per schedule, optionally made up of survey-level values.
CREATE TABLE IF NOT EXISTS core.transaction_schedule (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  transaction_id  BIGINT NOT NULL REFERENCES core.transaction(id),
  seq             INT NOT NULL,
  label           TEXT NOT NULL,
  manual_value    NUMERIC(18,2),
  schedule_value  NUMERIC(18,2) NOT NULL CHECK (schedule_value > 0),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (transaction_id, label)
);

CREATE TABLE IF NOT EXISTS core.transaction_schedule_survey (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  schedule_id     BIGINT NOT NULL REFERENCES core.transaction_schedule(id) ON DELETE CASCADE,
  seq             INT NOT NULL,
  survey_no       TEXT NOT NULL,
  subdivision_no  TEXT,
  value           NUMERIC(18,2) CHECK (value >= 0)
);

ALTER TABLE core.fee_calculation ADD COLUMN IF NOT EXISTS page_count INT CHECK (page_count > 0);

CREATE TABLE IF NOT EXISTS core.fee_calculation_line (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  fee_calculation_id  BIGINT NOT NULL REFERENCES core.fee_calculation(id),
  seq                 INT NOT NULL,
  schedule_label      TEXT NOT NULL,
  schedule_value      NUMERIC(18,2) NOT NULL,
  stamp_duty          NUMERIC(18,2) NOT NULL,
  registration_fee    NUMERIC(18,2) NOT NULL
);

DO $$
DECLARE
  app_role TEXT;
BEGIN
  FOREACH app_role IN ARRAY ARRAY['slate', 'slate_app'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
      EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON master.fee_relationship_category, '
                     || 'core.transaction_schedule, core.transaction_schedule_survey, '
                     || 'core.fee_calculation_line TO %I', app_role);
    END IF;
  END LOOP;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'slate_readonly') THEN
    GRANT SELECT ON master.fee_relationship_category, core.transaction_schedule,
      core.transaction_schedule_survey, core.fee_calculation_line TO slate_readonly;
  END IF;
END
$$;
