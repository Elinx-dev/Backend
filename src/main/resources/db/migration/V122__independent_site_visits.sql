-- Site visits are no longer negotiated between the Surveyor and the VAO: each officer
-- books his own slot. The Surveyor's survey visit becomes FIELD_SURVEY; open
-- proposals are kept as booked slots on the date last proposed.

ALTER TABLE survey.site_visit DROP CONSTRAINT IF EXISTS site_visit_visit_purpose_check;

UPDATE survey.site_visit SET visit_purpose = 'FIELD_SURVEY' WHERE visit_purpose = 'JOINT_SURVEY';

UPDATE survey.site_visit
   SET visit_date = COALESCE(counter_visit_date, visit_date),
       visit_time = CASE WHEN counter_visit_date IS NOT NULL THEN counter_visit_time ELSE visit_time END,
       counter_visit_date = NULL, counter_visit_time = NULL, counter_by_role = NULL,
       status = 'ACCEPTED', accepted_at = COALESCE(accepted_at, now())
 WHERE status IN ('PROPOSED','COUNTER_PROPOSED');

ALTER TABLE survey.site_visit
  ALTER COLUMN visit_purpose SET DEFAULT 'FIELD_SURVEY',
  ADD CONSTRAINT site_visit_visit_purpose_check CHECK (visit_purpose IN ('FIELD_SURVEY','FIELD_VERIFICATION'));
