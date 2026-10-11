INSERT INTO sec.role (code, name, department, description)
VALUES ('CENTRAL_ADMIN', 'Central Administrator', 'ADMIN', 'Manages administration across configured states')
ON CONFLICT (code) DO NOTHING;

INSERT INTO sec.role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM sec.role r
  CROSS JOIN sec.permission p
 WHERE r.code = 'CENTRAL_ADMIN'
   AND p.code IN ('PROPERTY_READ', 'TXN_READ', 'ADMIN_CONFIG', 'AUDIT_READ', 'TOKEN_READ',
                  'VERIFY_RUN', 'RULE_CHECK_READ', 'INGEST_RECORD_MODE')
ON CONFLICT DO NOTHING;