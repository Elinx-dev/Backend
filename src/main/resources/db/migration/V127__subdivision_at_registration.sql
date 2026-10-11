-- Subdivision Required = Yes (non-Partition): Register creates the new properties, linked to the one transaction.
CREATE TABLE IF NOT EXISTS core.transaction_property (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES core.transaction (id),
    property_id    BIGINT NOT NULL UNIQUE REFERENCES core.property (id),
    seq            INT    NOT NULL,
    allotted_side  TEXT   NOT NULL CHECK (allotted_side IN ('SIDE_1', 'SIDE_2')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (transaction_id, seq)
);

-- Register -> VAO -> Surveyor -> Tahsildar for those transactions.
INSERT INTO cfg.workflow_transition (workflow_id, from_status, to_status, action_code, allowed_roles, guard_expr)
SELECT d.id, v.from_status, v.to_status, v.action_code, v.roles, 'subdivisionRequired'
  FROM cfg.workflow_definition d
  CROSS JOIN (VALUES
      ('REGISTERED', 'VAO_PENDING', 'PROPOSE_SUBDIVISION', ARRAY['REGISTRATION_OFFICER', 'SYSTEM']),
      ('VAO_PENDING', 'SURVEY_PENDING', 'VAO_FORWARD_TO_SURVEY', ARRAY['VAO']),
      ('SURVEY_PENDING', 'TAHSILDAR_PENDING', 'SUBMIT_SUBDIVISION_SURVEY', ARRAY['SURVEYOR'])
  ) AS v (from_status, to_status, action_code, roles)
 WHERE d.deed_type_code <> 'PARTITION'
   AND EXISTS (SELECT 1 FROM cfg.workflow_transition t WHERE t.workflow_id = d.id AND t.action_code = 'VAO_FORWARD')
ON CONFLICT (workflow_id, from_status, action_code) DO NOTHING;
