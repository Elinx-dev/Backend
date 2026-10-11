-- Transaction Type, Survey Fee and Blood Relation masters. Survey is driven by
-- "Subdivision Required?" and "Survey Required by Party?" on the transaction,
-- not by the deed type's survey rule.

CREATE TABLE IF NOT EXISTS master.transaction_type (
  id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code               TEXT NOT NULL DEFAULT '*',
  code                     TEXT NOT NULL,
  name                     TEXT NOT NULL,
  first_party_label        TEXT NOT NULL,
  second_party_label       TEXT NOT NULL,
  subdivision_allowed      BOOLEAN NOT NULL DEFAULT FALSE,
  individuals_only         BOOLEAN NOT NULL DEFAULT FALSE,
  blood_relation_required  BOOLEAN NOT NULL DEFAULT FALSE,
  display_order            INT NOT NULL DEFAULT 0,
  status                   TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  UNIQUE (state_code, code)
);

INSERT INTO master.transaction_type
  (state_code, code, name, first_party_label, second_party_label, subdivision_allowed,
   individuals_only, blood_relation_required, display_order, status)
VALUES
  ('*','SALE','Sale','Vendor','Vendee',TRUE,FALSE,FALSE,1,'ACTIVE'),
  ('*','SETTLEMENT','Settlement','Settler','Settlee',TRUE,TRUE,TRUE,2,'ACTIVE'),
  ('*','GIFT','Gift','Donor','Donee',TRUE,FALSE,FALSE,3,'ACTIVE'),
  ('*','RELEASE','Release','Releasor','Releasee',FALSE,FALSE,FALSE,4,'ACTIVE'),
  ('*','PARTITION','Partition','Party','Party',TRUE,FALSE,FALSE,5,'ACTIVE')
ON CONFLICT (state_code, code) DO NOTHING;

CREATE TABLE IF NOT EXISTS master.survey_fee (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code      TEXT NOT NULL DEFAULT '*',
  location_type   TEXT NOT NULL,
  location_name   TEXT NOT NULL,
  fee             NUMERIC(18,2) NOT NULL CHECK (fee >= 0),
  land_type_code  TEXT,
  display_order   INT NOT NULL DEFAULT 0,
  status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  UNIQUE (state_code, location_type)
);

INSERT INTO master.survey_fee (state_code, location_type, location_name, fee, land_type_code, display_order, status)
VALUES
  ('*','VILLAGE','Village',400,'RURAL',1,'ACTIVE'),
  ('*','TOWN','Town',800,'URBAN',2,'ACTIVE')
ON CONFLICT (state_code, location_type) DO NOTHING;

CREATE TABLE IF NOT EXISTS master.blood_relation (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code         TEXT NOT NULL DEFAULT '*',
  code               TEXT NOT NULL,
  relationship_name  TEXT NOT NULL,
  display_order      INT NOT NULL DEFAULT 0,
  status             TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  UNIQUE (state_code, code)
);

INSERT INTO master.blood_relation (state_code, code, relationship_name, display_order, status)
VALUES
  ('*','FATHER','Father',1,'ACTIVE'),
  ('*','MOTHER','Mother',2,'ACTIVE'),
  ('*','HUSBAND','Husband',3,'ACTIVE'),
  ('*','WIFE','Wife',4,'ACTIVE'),
  ('*','SON','Son',5,'ACTIVE'),
  ('*','DAUGHTER','Daughter',6,'ACTIVE'),
  ('*','BROTHER','Brother',7,'ACTIVE'),
  ('*','SISTER','Sister',8,'ACTIVE'),
  ('*','GRANDCHILD','Grandchild',9,'ACTIVE'),
  ('*','GREAT_GRANDCHILD','Great-grandchild',10,'ACTIVE'),
  ('*','WIFE_OF_PREDECEASED_SON','Wife of predeceased son',11,'ACTIVE'),
  ('*','HUSBAND_OF_PREDECEASED_DAUGHTER','Husband of predeceased daughter',12,'ACTIVE')
ON CONFLICT (state_code, code) DO NOTHING;

-- SALE and RELEASE become plain transaction type codes. Their workflow, fee and
-- field configuration start as copies of SALE_FULL and RELEASE_RELINQUISHMENT.
INSERT INTO master.deed_type
  (state_code, code, name, workflow_family, survey_rule, side1_role, side2_role,
   witness_required, min_witness_count, requires_relationship_category, consent_required, active, effective_from)
SELECT d.state_code, m.new_code, m.new_name, d.workflow_family, 'NEVER', m.side1, m.side2,
       d.witness_required, d.min_witness_count, false, d.consent_required, true, d.effective_from
  FROM master.deed_type d
  JOIN (VALUES ('SALE_FULL','SALE','Sale','VENDOR','VENDEE'),
               ('RELEASE_RELINQUISHMENT','RELEASE','Release','RELEASOR','RELEASEE'))
       AS m(src, new_code, new_name, side1, side2) ON m.src = d.code
 WHERE NOT EXISTS (SELECT 1 FROM master.deed_type x WHERE x.state_code = d.state_code AND x.code = m.new_code);

INSERT INTO master.fee_master
  (state_code, deed_type_code, subtype, relationship_category, transfer_nature, valuation_basis,
   stamp_duty_rate, stamp_duty_flat, stamp_duty_min, stamp_duty_max,
   registration_fee_rate, registration_fee_flat, registration_fee_min, registration_fee_max,
   tds_threshold, tds_rate, other_charges, gazette_reference, approved_by, approval_date, version,
   effective_from, effective_to)
SELECT f.state_code, m.new_code, f.subtype, f.relationship_category, f.transfer_nature, f.valuation_basis,
       f.stamp_duty_rate, f.stamp_duty_flat, f.stamp_duty_min, f.stamp_duty_max,
       f.registration_fee_rate, f.registration_fee_flat, f.registration_fee_min, f.registration_fee_max,
       f.tds_threshold, f.tds_rate, f.other_charges, f.gazette_reference, f.approved_by, f.approval_date,
       f.version, f.effective_from, f.effective_to
  FROM master.fee_master f
  JOIN (VALUES ('SALE_FULL','SALE'), ('RELEASE_RELINQUISHMENT','RELEASE')) AS m(src, new_code)
    ON m.src = f.deed_type_code
 WHERE NOT EXISTS (SELECT 1 FROM master.fee_master x
                    WHERE x.state_code = f.state_code AND x.deed_type_code = m.new_code);

INSERT INTO cfg.field_config
  (state_code, deed_type_code, stage_code, role_code, field_id, visible, required, editable, masked,
   label_override, help_text, default_value, regex, min_value, max_value, min_length, max_length,
   option_set_code, display_order, ui_group, effective_from, effective_to)
SELECT c.state_code, m.new_code, c.stage_code, c.role_code, c.field_id, c.visible, c.required, c.editable,
       c.masked, c.label_override, c.help_text, c.default_value, c.regex, c.min_value, c.max_value,
       c.min_length, c.max_length, c.option_set_code, c.display_order, c.ui_group, c.effective_from,
       c.effective_to
  FROM cfg.field_config c
  JOIN (VALUES ('SALE_FULL','SALE'), ('RELEASE_RELINQUISHMENT','RELEASE')) AS m(src, new_code)
    ON m.src = c.deed_type_code
 WHERE NOT EXISTS (SELECT 1 FROM cfg.field_config x WHERE x.deed_type_code = m.new_code);

DO $$
DECLARE
  src RECORD;
  new_wf BIGINT;
BEGIN
  FOR src IN
    SELECT w.id, w.state_code, w.workflow_code, w.version, w.effective_from, m.new_code
      FROM cfg.workflow_definition w
      JOIN (VALUES ('SALE_FULL','SALE'), ('RELEASE_RELINQUISHMENT','RELEASE')) AS m(src, new_code)
        ON m.src = w.deed_type_code
     WHERE w.status = 'PUBLISHED'
       AND NOT EXISTS (SELECT 1 FROM cfg.workflow_definition x
                        WHERE x.state_code = w.state_code AND x.deed_type_code = m.new_code)
  LOOP
    INSERT INTO cfg.workflow_definition
      (state_code, deed_type_code, workflow_code, version, status, published_at, effective_from)
    VALUES (src.state_code, src.new_code, src.workflow_code, 1, 'PUBLISHED', now(), src.effective_from)
    RETURNING id INTO new_wf;

    INSERT INTO cfg.workflow_stage
      (workflow_id, seq, stage_code, stage_label, status_on_enter, owner_role, optional, skip_condition,
       entry_guard, exit_guard, sla_hours, ui_route)
    SELECT new_wf, seq, stage_code, stage_label, status_on_enter, owner_role, optional, skip_condition,
           entry_guard, exit_guard, sla_hours, ui_route
      FROM cfg.workflow_stage WHERE workflow_id = src.id;

    INSERT INTO cfg.workflow_transition
      (workflow_id, from_status, to_status, action_code, allowed_roles, guard_expr, effect_expr,
       requires_reason, emits_event)
    SELECT new_wf, from_status, to_status, action_code, allowed_roles, guard_expr, effect_expr,
           requires_reason, emits_event
      FROM cfg.workflow_transition WHERE workflow_id = src.id ORDER BY id;
  END LOOP;
END
$$;

-- The Survey stage is entered whenever the transaction needs a survey.
UPDATE cfg.workflow_stage s
   SET optional = TRUE, skip_condition = 'surveyNotRequired'
  FROM cfg.workflow_definition w
 WHERE w.id = s.workflow_id AND s.stage_code = 'SURVEY'
   AND w.deed_type_code IN ('SALE','SETTLEMENT','GIFT','RELEASE','PARTITION');

ALTER TABLE core.transaction
  ADD COLUMN IF NOT EXISTS subdivision_required    BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS survey_required_by_party BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS assigned_surveyor_id     BIGINT REFERENCES sec.user(id),
  ADD COLUMN IF NOT EXISTS survey_location_type     TEXT;

ALTER TABLE core.fee_calculation
  ADD COLUMN IF NOT EXISTS survey_fee NUMERIC(18,2) NOT NULL DEFAULT 0;

DO $$
DECLARE
  app_role TEXT;
BEGIN
  FOREACH app_role IN ARRAY ARRAY['slate', 'slate_app'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
      EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON master.transaction_type, master.survey_fee, '
                     || 'master.blood_relation TO %I', app_role);
    END IF;
  END LOOP;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'slate_readonly') THEN
    GRANT SELECT ON master.transaction_type, master.survey_fee, master.blood_relation TO slate_readonly;
  END IF;
END
$$;
