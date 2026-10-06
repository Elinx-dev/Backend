-- A property has one owner type, which drives which identity fields are
-- collected for its owners. Company, firm, LLP, HUF and trust owners act through
-- a representative (authorised signatory, authorised partner, karta or trustee).
-- cfg.option_value.attributes.allowMultipleOwners decides whether a property of
-- that owner type may have more than one owner.
ALTER TABLE core.property
  ADD COLUMN IF NOT EXISTS owner_type_code TEXT;

ALTER TABLE core.property_owner
  ADD COLUMN IF NOT EXISTS owner_type_code            TEXT,
  ADD COLUMN IF NOT EXISTS mobile                     TEXT,
  ADD COLUMN IF NOT EXISTS registration_no            TEXT,
  ADD COLUMN IF NOT EXISTS representative_role        TEXT,
  ADD COLUMN IF NOT EXISTS representative_name        TEXT,
  ADD COLUMN IF NOT EXISTS representative_designation TEXT,
  ADD COLUMN IF NOT EXISTS representative_aadhaar     TEXT,
  ADD COLUMN IF NOT EXISTS representative_pan         TEXT,
  ADD COLUMN IF NOT EXISTS representative_mobile      TEXT;

ALTER TABLE core.property_owner
  DROP CONSTRAINT IF EXISTS ck_property_owner_type,
  ADD CONSTRAINT ck_property_owner_type CHECK (owner_type_code IS NULL OR owner_type_code IN (
    'INDIVIDUAL', 'SOLE_PROPRIETORSHIP', 'PARTNERSHIP_FIRM', 'HUF', 'LLP',
    'PRIVATE_LIMITED_COMPANY', 'PUBLIC_LIMITED_COMPANY', 'ONE_PERSON_COMPANY', 'TRUST',
    'SOCIETY', 'AOP_BOI', 'GOVERNMENT', 'OTHER_LEGAL_ENTITY'));

ALTER TABLE core.property
  DROP CONSTRAINT IF EXISTS ck_property_owner_type,
  ADD CONSTRAINT ck_property_owner_type CHECK (owner_type_code IS NULL OR owner_type_code IN (
    'INDIVIDUAL', 'SOLE_PROPRIETORSHIP', 'PARTNERSHIP_FIRM', 'HUF', 'LLP',
    'PRIVATE_LIMITED_COMPANY', 'PUBLIC_LIMITED_COMPANY', 'ONE_PERSON_COMPANY', 'TRUST',
    'SOCIETY', 'AOP_BOI', 'GOVERNMENT', 'OTHER_LEGAL_ENTITY'));

ALTER TABLE core.property_owner
  DROP CONSTRAINT IF EXISTS ck_property_owner_representative_role,
  ADD CONSTRAINT ck_property_owner_representative_role CHECK (representative_role IS NULL OR representative_role IN (
    'AUTHORISED_SIGNATORY', 'AUTHORISED_PARTNER', 'KARTA', 'AUTHORISED_TRUSTEE'));

INSERT INTO cfg.option_set (code, description)
VALUES ('OWNER_TYPE', 'Legal type of a property owner')
ON CONFLICT (code) DO NOTHING;

INSERT INTO cfg.option_value (option_set_code, state_code, value_code, label, sort_order, attributes, active)
VALUES
  ('OWNER_TYPE', '*', 'INDIVIDUAL', 'Individual', 1, '{"allowMultipleOwners": true}', TRUE),
  ('OWNER_TYPE', '*', 'SOLE_PROPRIETORSHIP', 'Sole Proprietorship', 2, '{"allowMultipleOwners": false}', TRUE),
  ('OWNER_TYPE', '*', 'PARTNERSHIP_FIRM', 'Partnership Firm', 3, '{"allowMultipleOwners": false}', TRUE),
  ('OWNER_TYPE', '*', 'HUF', 'HUF', 4, '{"allowMultipleOwners": false}', TRUE),
  ('OWNER_TYPE', '*', 'LLP', 'LLP', 5, '{"allowMultipleOwners": false}', TRUE),
  ('OWNER_TYPE', '*', 'PRIVATE_LIMITED_COMPANY', 'Private Limited Company', 6, '{"allowMultipleOwners": false}', TRUE),
  ('OWNER_TYPE', '*', 'PUBLIC_LIMITED_COMPANY', 'Public Limited Company', 7, '{"allowMultipleOwners": false}', TRUE),
  ('OWNER_TYPE', '*', 'ONE_PERSON_COMPANY', 'One Person Company', 8, '{"allowMultipleOwners": false}', TRUE),
  ('OWNER_TYPE', '*', 'TRUST', 'Trust', 9, '{"allowMultipleOwners": false}', TRUE),
  ('OWNER_TYPE', '*', 'SOCIETY', 'Society / Co-operative Society', 10, '{"allowMultipleOwners": false}', TRUE),
  ('OWNER_TYPE', '*', 'AOP_BOI', 'Association of Persons / Body of Individuals', 11, '{"allowMultipleOwners": false}', TRUE),
  ('OWNER_TYPE', '*', 'GOVERNMENT', 'Government / Government Department / Local Authority', 12, '{"allowMultipleOwners": false}', TRUE),
  ('OWNER_TYPE', '*', 'OTHER_LEGAL_ENTITY', 'Other Legal Entity', 13, '{"allowMultipleOwners": true}', TRUE)
ON CONFLICT (option_set_code, state_code, value_code)
DO UPDATE SET label = EXCLUDED.label, sort_order = EXCLUDED.sort_order, attributes = EXCLUDED.attributes,
              active = TRUE;
