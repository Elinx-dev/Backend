-- VAO portal: records are assigned to a named VAO, and the VAO books a site-visit
-- slot before a proposed mutation can be verified and forwarded.

ALTER TABLE core.transaction
  ADD COLUMN IF NOT EXISTS assigned_vao_id BIGINT REFERENCES sec.user(id);
CREATE INDEX IF NOT EXISTS ix_txn_assigned_vao ON core.transaction (assigned_vao_id);

-- JOINT_SURVEY visits are negotiated with the Surveyor while the transaction is
-- SURVEY_PENDING; FIELD_VERIFICATION visits are booked by the VAO alone.
ALTER TABLE survey.site_visit
  ADD COLUMN IF NOT EXISTS visit_purpose TEXT NOT NULL DEFAULT 'JOINT_SURVEY'
    CHECK (visit_purpose IN ('JOINT_SURVEY','FIELD_VERIFICATION')),
  ADD COLUMN IF NOT EXISTS vao_user_id BIGINT REFERENCES sec.user(id),
  ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS ix_site_visit_vao ON survey.site_visit (vao_user_id, visit_date);
