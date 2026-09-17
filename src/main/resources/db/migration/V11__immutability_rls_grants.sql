-- Append-only enforcement, row-level state isolation, and least-privilege grants.
-- Reference: Database Design §11

-- 1. Append-only tables: UPDATE/DELETE blocked at the database, not only in code.
DO $$
DECLARE t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'core.registration_result',
    'revenue.approved_record',
    'chain.token_state_history',
    'chain.blockchain_event',
    'rules.rule_check_result',
    'rules.name_match_audit',
    'sec.audit_log'
  ] LOOP
    EXECUTE format(
      'CREATE TRIGGER trg_immutable_%s BEFORE UPDATE OR DELETE ON %s
         FOR EACH ROW EXECUTE FUNCTION sec.raise_immutable()',
      replace(t, '.', '_'), t);
    EXECUTE format('REVOKE UPDATE, DELETE ON %s FROM PUBLIC', t);
  END LOOP;
END
$$;

-- core.transaction: once REGISTERED, registration-side columns are frozen.
-- Only revenue-side progression and remarks may still move.
CREATE OR REPLACE FUNCTION core.guard_registered_transaction() RETURNS trigger AS $$
BEGIN
  IF OLD.status IN ('REGISTERED','SURVEY_PENDING','VAO_PENDING','OBJECTION_PENDING',
                    'TAHSILDAR_PENDING','REVENUE_APPROVED') THEN
    IF NEW.deed_type_code       IS DISTINCT FROM OLD.deed_type_code
    OR NEW.subtype              IS DISTINCT FROM OLD.subtype
    OR NEW.property_id          IS DISTINCT FROM OLD.property_id
    OR NEW.transfer_scope       IS DISTINCT FROM OLD.transfer_scope
    OR NEW.survey_required      IS DISTINCT FROM OLD.survey_required
    OR NEW.declared_consideration IS DISTINCT FROM OLD.declared_consideration
    OR NEW.guideline_value      IS DISTINCT FROM OLD.guideline_value
    OR NEW.registered_at        IS DISTINCT FROM OLD.registered_at THEN
      RAISE EXCEPTION 'Transaction % is registered; registration-side data cannot change', OLD.txn_ref;
    END IF;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_guard_registered BEFORE UPDATE ON core.transaction
  FOR EACH ROW EXECUTE FUNCTION core.guard_registered_transaction();

-- A party's Aadhaar hash may never be replaced once consent is verified.
CREATE OR REPLACE FUNCTION core.guard_verified_consent() RETURNS trigger AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM core.consent_record c
              WHERE c.party_id = OLD.id AND c.status = 'VERIFIED')
     AND NEW.aadhaar_hash IS DISTINCT FROM OLD.aadhaar_hash THEN
    RAISE EXCEPTION 'Party % has verified consent; its Aadhaar binding is frozen', OLD.id;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_guard_consent BEFORE UPDATE ON core.transaction_party
  FOR EACH ROW EXECUTE FUNCTION core.guard_verified_consent();

-- 2. Row-level security: a user of one state never sees another state's rows.
-- The API sets slate.state_code on every request; when it is unset (migrations,
-- indexer jobs) the policy allows the row through.
DO $$
DECLARE t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'core.property','core.transaction','core.document',
    'rules.rule_check_request','survey.site_visit','survey.submission',
    'revenue.proposed_mutation','chain.token','chain.blockchain_transaction',
    'sec.audit_log'
  ] LOOP
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY state_isolation ON %s USING (
         current_setting(''slate.state_code'', true) IS NULL
         OR current_setting(''slate.state_code'', true) = '''' 
         OR state_code = current_setting(''slate.state_code'', true))', t);
  END LOOP;
END
$$;

-- 3. Grants.
GRANT USAGE ON SCHEMA cfg, master, core, rules, survey, revenue, chain, sec, rpt TO slate_app, slate_readonly;

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA
  cfg, master, core, rules, survey, revenue, chain, sec TO slate_app;
GRANT SELECT ON ALL TABLES IN SCHEMA rpt TO slate_app, slate_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA
  cfg, master, core, rules, survey, revenue, chain TO slate_readonly;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA
  cfg, master, core, rules, survey, revenue, chain, sec TO slate_app;

-- Append-only tables: insert but never modify.
REVOKE UPDATE ON
  core.registration_result, revenue.approved_record, chain.token_state_history,
  chain.blockchain_event, rules.rule_check_result, rules.name_match_audit, sec.audit_log
  FROM slate_app;

-- The public/readonly role must not be able to read PII at all.
REVOKE SELECT ON core.transaction_party, core.consent_record, sec.user, sec.login_otp FROM slate_readonly;
