-- Removes transactions that never reached Register, and properties that were never
-- part of a registered transaction, together with all their child rows.
--
--   Preview (shows what would go, then rolls back):
--     psql -h <host> -U <owner> -d slate -f scripts/db/cleanup_unregistered.sql
--   Delete:
--     psql -h <host> -U <owner> -d slate -v apply=1 -f scripts/db/cleanup_unregistered.sql
--
-- Run as the schema owner: the append-only triggers on rule-check and chain history
-- tables are disabled inside this transaction only. Foreign keys stay enforced, so a
-- missed dependent row aborts the whole run. Registered data and sec.audit_log are kept.

\set ON_ERROR_STOP on
\if :{?apply}
\else
\set apply 0
\endif

BEGIN;

CREATE TEMP TABLE cleanup_txn ON COMMIT DROP AS
SELECT t.id, t.txn_ref, t.status
  FROM core.transaction t
 WHERE t.status IN ('DRAFT', 'CONSENT_PENDING', 'RULE_CHECK_PENDING', 'EXCEPTION',
                    'FEE_PAYMENT_PENDING', 'SUBMITTED', 'WITHDRAWN')
   AND NOT EXISTS (SELECT 1 FROM core.registration_result r WHERE r.transaction_id = t.id);

CREATE TEMP TABLE cleanup_prop ON COMMIT DROP AS
SELECT p.id, p.property_ref, p.status
  FROM core.property p
 WHERE p.parent_property_id IS NULL
   AND p.token_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM chain.token k WHERE k.property_id = p.id)
   AND NOT EXISTS (SELECT 1 FROM core.property c WHERE c.parent_property_id = p.id)
   AND NOT EXISTS (SELECT 1 FROM core.property_apartment_detail a WHERE a.parent_land_property_id = p.id)
   AND NOT EXISTS (SELECT 1 FROM core.transaction t
                    WHERE t.property_id = p.id AND t.id NOT IN (SELECT id FROM cleanup_txn))
   AND NOT EXISTS (SELECT 1 FROM core.transaction_property tp
                    WHERE tp.property_id = p.id AND tp.transaction_id NOT IN (SELECT id FROM cleanup_txn));

\echo '== Transactions to delete'
SELECT txn_ref, status FROM cleanup_txn ORDER BY txn_ref;
\echo '== Properties to delete'
SELECT property_ref, status FROM cleanup_prop ORDER BY property_ref;

ALTER TABLE rules.rule_check_result DISABLE TRIGGER trg_immutable_rules_rule_check_result;
ALTER TABLE rules.name_match_audit DISABLE TRIGGER trg_immutable_rules_name_match_audit;
ALTER TABLE chain.token_state_history DISABLE TRIGGER trg_immutable_chain_token_state_history;

-- Transaction children
CREATE TEMP TABLE cleanup_req ON COMMIT DROP AS
SELECT id FROM rules.rule_check_request
 WHERE transaction_id IN (SELECT id FROM cleanup_txn) OR property_id IN (SELECT id FROM cleanup_prop);
DELETE FROM rules.rule_check_result WHERE request_id IN (SELECT id FROM cleanup_req);
DELETE FROM rules.name_match_audit WHERE request_id IN (SELECT id FROM cleanup_req);
DELETE FROM rules.ec_certificate WHERE request_id IN (SELECT id FROM cleanup_req);
DELETE FROM rules.revenue_ownership_snapshot
 WHERE request_id IN (SELECT id FROM cleanup_req) OR property_id IN (SELECT id FROM cleanup_prop);
DELETE FROM rules.rule_check_request WHERE id IN (SELECT id FROM cleanup_req);

DELETE FROM core.consent_record
 WHERE transaction_id IN (SELECT id FROM cleanup_txn)
    OR party_id IN (SELECT id FROM core.transaction_party WHERE transaction_id IN (SELECT id FROM cleanup_txn));
DELETE FROM core.partition_member WHERE transaction_id IN (SELECT id FROM cleanup_txn);
DELETE FROM core.transaction_schedule_survey
 WHERE schedule_id IN (SELECT id FROM core.transaction_schedule WHERE transaction_id IN (SELECT id FROM cleanup_txn));
DELETE FROM core.transaction_schedule WHERE transaction_id IN (SELECT id FROM cleanup_txn);
DELETE FROM core.witness WHERE transaction_id IN (SELECT id FROM cleanup_txn);
DELETE FROM core.transaction_party WHERE transaction_id IN (SELECT id FROM cleanup_txn);
DELETE FROM core.fee_calculation_line
 WHERE fee_calculation_id IN (SELECT id FROM core.fee_calculation WHERE transaction_id IN (SELECT id FROM cleanup_txn));
DELETE FROM core.fee_calculation WHERE transaction_id IN (SELECT id FROM cleanup_txn);
DELETE FROM core.payment WHERE transaction_id IN (SELECT id FROM cleanup_txn);
DELETE FROM core.transaction_property
 WHERE transaction_id IN (SELECT id FROM cleanup_txn) OR property_id IN (SELECT id FROM cleanup_prop);

CREATE TEMP TABLE cleanup_sub ON COMMIT DROP AS
SELECT s.id FROM survey.submission s
 WHERE s.transaction_id IN (SELECT id FROM cleanup_txn)
    OR s.site_visit_id IN (SELECT id FROM survey.site_visit WHERE transaction_id IN (SELECT id FROM cleanup_txn));
DELETE FROM survey.attachment WHERE submission_id IN (SELECT id FROM cleanup_sub);
DELETE FROM survey.boundary_point WHERE submission_id IN (SELECT id FROM cleanup_sub);
DELETE FROM survey.measurement_segment WHERE submission_id IN (SELECT id FROM cleanup_sub);
DELETE FROM survey.resulting_parcel
 WHERE submission_id IN (SELECT id FROM cleanup_sub) OR child_property_id IN (SELECT id FROM cleanup_prop);
DELETE FROM survey.submission WHERE id IN (SELECT id FROM cleanup_sub);
DELETE FROM survey.site_visit WHERE transaction_id IN (SELECT id FROM cleanup_txn);

DELETE FROM chain.blockchain_transaction WHERE transaction_id IN (SELECT id FROM cleanup_txn);
DELETE FROM chain.endorsement WHERE transaction_id IN (SELECT id FROM cleanup_txn);
DELETE FROM chain.token_state_history WHERE transaction_id IN (SELECT id FROM cleanup_txn);

-- Documents of the deleted transactions and properties
CREATE TEMP TABLE cleanup_doc ON COMMIT DROP AS
SELECT id FROM core.document
 WHERE transaction_id IN (SELECT id FROM cleanup_txn) OR property_id IN (SELECT id FROM cleanup_prop);
DELETE FROM survey.attachment WHERE document_id IN (SELECT id FROM cleanup_doc);
DELETE FROM core.document_content WHERE document_id IN (SELECT id FROM cleanup_doc);
DELETE FROM core.document WHERE id IN (SELECT id FROM cleanup_doc);

DELETE FROM core.transaction WHERE id IN (SELECT id FROM cleanup_txn);

-- Property children
DELETE FROM chain.verification_run WHERE property_id IN (SELECT id FROM cleanup_prop);
DELETE FROM revenue.current_state WHERE property_id IN (SELECT id FROM cleanup_prop);
DELETE FROM core.property_apartment_detail WHERE property_id IN (SELECT id FROM cleanup_prop);
DELETE FROM core.property_measurement WHERE property_id IN (SELECT id FROM cleanup_prop);
DELETE FROM core.property_survey WHERE property_id IN (SELECT id FROM cleanup_prop);
DELETE FROM core.chain_of_title_owner
 WHERE chain_of_title_id IN (SELECT id FROM core.chain_of_title WHERE property_id IN (SELECT id FROM cleanup_prop));
DELETE FROM core.chain_of_title WHERE property_id IN (SELECT id FROM cleanup_prop);
DELETE FROM core.property_owner WHERE property_id IN (SELECT id FROM cleanup_prop);
DELETE FROM core.property WHERE id IN (SELECT id FROM cleanup_prop);

ALTER TABLE rules.rule_check_result ENABLE TRIGGER trg_immutable_rules_rule_check_result;
ALTER TABLE rules.name_match_audit ENABLE TRIGGER trg_immutable_rules_name_match_audit;
ALTER TABLE chain.token_state_history ENABLE TRIGGER trg_immutable_chain_token_state_history;

\echo '== Remaining'
SELECT 'transactions' AS item, count(*) FROM core.transaction
UNION ALL SELECT 'properties', count(*) FROM core.property;

\if :apply
COMMIT;
\echo 'Committed.'
\else
ROLLBACK;
\echo 'Preview only, rolled back. Re-run with -v apply=1 to delete.'
\endif
