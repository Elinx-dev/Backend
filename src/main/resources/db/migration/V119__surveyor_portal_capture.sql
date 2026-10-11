-- Surveyor portal: visits record the Surveyor who negotiated them, and survey
-- submissions keep the measured extent in the record's unit plus the parcel polygon.

ALTER TABLE survey.site_visit
  ADD COLUMN IF NOT EXISTS surveyor_user_id BIGINT REFERENCES sec.user(id);
CREATE INDEX IF NOT EXISTS ix_site_visit_surveyor ON survey.site_visit (surveyor_user_id, visit_date);

UPDATE survey.site_visit v
   SET surveyor_user_id = t.assigned_surveyor_id
  FROM core.transaction t
 WHERE t.id = v.transaction_id
   AND v.surveyor_user_id IS NULL
   AND t.assigned_surveyor_id IS NOT NULL;

ALTER TABLE survey.submission
  ADD COLUMN IF NOT EXISTS measured_extent_in_record_unit NUMERIC(18,4),
  ADD COLUMN IF NOT EXISTS boundary_geojson JSONB;

-- A measured extent in a different unit (e.g. acres against a sq.ft record) can be far
-- off the record; NUMERIC(7,4) overflowed above 999.9999%.
ALTER TABLE survey.submission ALTER COLUMN variance_pct TYPE NUMERIC(12,4);
