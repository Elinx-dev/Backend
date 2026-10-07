-- V113, V116 and V121 created tables without granting them to the application role,
-- so the API (connected as slate_app) got "permission denied" on rule checks and survey rows.
-- Default privileges make tables and sequences created by later migrations accessible too.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'slate_app') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON
      cfg.ec_classification_keyword, core.property_survey, rules.revenue_parcel TO slate_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA
      cfg, master, core, rules, survey, revenue, chain, sec TO slate_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA cfg, master, core, rules, survey, revenue, chain, sec
      GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO slate_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA cfg, master, core, rules, survey, revenue, chain, sec
      GRANT USAGE, SELECT ON SEQUENCES TO slate_app;
  END IF;
END
$$;
