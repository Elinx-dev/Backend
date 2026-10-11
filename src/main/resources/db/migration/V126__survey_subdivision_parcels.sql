-- A property split by a subdivision survey is shown as INACTIVE; its new parcels carry the parent reference.
ALTER TABLE core.property DROP CONSTRAINT IF EXISTS property_status_check;
UPDATE core.property SET status = 'INACTIVE' WHERE status = 'SUPERSEDED';
ALTER TABLE core.property
  ADD CONSTRAINT property_status_check CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUPERSEDED'));

ALTER TABLE survey.resulting_parcel
  ADD COLUMN IF NOT EXISTS survey_no       TEXT,
  ADD COLUMN IF NOT EXISTS boundary_north  TEXT,
  ADD COLUMN IF NOT EXISTS boundary_south  TEXT,
  ADD COLUMN IF NOT EXISTS boundary_east   TEXT,
  ADD COLUMN IF NOT EXISTS boundary_west   TEXT;
