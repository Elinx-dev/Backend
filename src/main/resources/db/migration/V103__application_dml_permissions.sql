-- The API replaces mutable child collections with DELETE followed by INSERT.
-- Grant complete DML access to the supported application roles while preserving
-- the append-only guarantees established in V11.
DO $$
DECLARE
  app_role TEXT;
BEGIN
  FOREACH app_role IN ARRAY ARRAY['slate', 'slate_app']
  LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role)
       AND EXISTS (
         SELECT 1
           FROM pg_class c
           JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname IN ('cfg', 'master', 'core', 'rules', 'survey', 'revenue', 'chain', 'sec')
            AND c.relkind IN ('r', 'p')
            AND n.nspname || '.' || c.relname NOT IN (
              'core.registration_result',
              'revenue.approved_record',
              'chain.token_state_history',
              'chain.blockchain_event',
              'rules.rule_check_result',
              'rules.name_match_audit',
              'sec.audit_log',
              'sec.audit_log_default',
              'sec.audit_log_2026',
              'sec.audit_log_2027'
            )
            AND NOT (
              has_table_privilege(app_role, c.oid, 'SELECT')
              AND has_table_privilege(app_role, c.oid, 'INSERT')
              AND has_table_privilege(app_role, c.oid, 'UPDATE')
              AND has_table_privilege(app_role, c.oid, 'DELETE')
            )
       ) THEN
      EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA
           cfg, master, core, rules, survey, revenue, chain, sec TO %I',
        app_role);
      EXECUTE format(
        'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA
           cfg, master, core, rules, survey, revenue, chain, sec TO %I',
        app_role);
      EXECUTE format(
        'REVOKE UPDATE, DELETE ON
           core.registration_result,
           revenue.approved_record,
           chain.token_state_history,
           chain.blockchain_event,
           rules.rule_check_result,
           rules.name_match_audit,
           sec.audit_log,
           sec.audit_log_default,
           sec.audit_log_2026,
           sec.audit_log_2027
         FROM %I',
        app_role);
    END IF;
  END LOOP;
END
$$;
