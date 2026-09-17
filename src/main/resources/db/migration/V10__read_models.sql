-- Read models. The public view is structurally incapable of exposing Aadhaar,
-- consideration, contact details, or rule-check findings: those columns are not selected.

CREATE VIEW rpt.public_property_view AS
SELECT
  p.property_ref,
  p.ulpin,
  p.state_code,
  p.property_type_code,
  p.land_type_code,
  p.classification_code,
  p.extent_value,
  p.extent_unit,
  p.survey_no,
  p.subdivision_no,
  p.district_code,
  p.taluk_code,
  p.village_code,
  p.status                                   AS property_status,
  t.token_ref,
  t.state_version                            AS token_state_version,
  t.status                                   AS token_status,
  t.minted_at                                AS token_minted_at,
  (SELECT jsonb_agg(jsonb_build_object('name', po.owner_name, 'sharePct', po.share_pct)
                    ORDER BY po.id)
     FROM core.property_owner po
    WHERE po.property_id = p.id
      AND po.effective_to IS NULL
      AND po.source IN ('REGISTRATION','PROPERTY_ENTRY'))          AS registered_owners,
  (SELECT rs.owners
     FROM revenue.current_state rs
    WHERE rs.property_id = p.id
    ORDER BY rs.fetched_at DESC
    LIMIT 1)                                                        AS revenue_owners,
  (SELECT rr.registration_date
     FROM core.registration_result rr
     JOIN core.transaction tx ON tx.id = rr.transaction_id
    WHERE tx.property_id = p.id
    ORDER BY rr.registration_date DESC
    LIMIT 1)                                                        AS last_registration_date
FROM core.property p
LEFT JOIN chain.token t ON t.id = p.token_id AND t.status = 'ACTIVE';

CREATE VIEW rpt.transaction_queue_view AS
SELECT
  t.id,
  t.state_code,
  t.txn_ref,
  t.deed_type_code,
  t.subtype,
  t.status,
  t.current_stage_code,
  t.survey_required,
  t.sro_code,
  t.initiated_at,
  t.registered_at,
  p.property_ref,
  p.ulpin,
  p.survey_no,
  p.subdivision_no,
  p.village_code,
  p.district_code,
  (SELECT count(*) FROM core.transaction_party tp WHERE tp.transaction_id = t.id)      AS party_count,
  (SELECT count(*) FROM core.consent_record c
    WHERE c.transaction_id = t.id AND c.status = 'VERIFIED')                           AS consent_verified_count,
  (SELECT count(*) FROM core.transaction_party tp WHERE tp.transaction_id = t.id)      AS consent_required_count,
  (SELECT rcres.overall_outcome
     FROM rules.rule_check_result rcres
     JOIN rules.rule_check_request rcreq ON rcreq.id = rcres.request_id
    WHERE rcreq.transaction_id = t.id AND rcres.engine = 'EC'
    ORDER BY rcres.checked_at DESC LIMIT 1)                                            AS ec_outcome,
  (SELECT rcres.overall_outcome
     FROM rules.rule_check_result rcres
     JOIN rules.rule_check_request rcreq ON rcreq.id = rcres.request_id
    WHERE rcreq.transaction_id = t.id AND rcres.engine = 'REVENUE_OWNERSHIP'
    ORDER BY rcres.checked_at DESC LIMIT 1)                                            AS revenue_outcome,
  (SELECT fc.total_payable FROM core.fee_calculation fc
    WHERE fc.transaction_id = t.id ORDER BY fc.calculated_at DESC LIMIT 1)             AS total_payable,
  (SELECT pm.status FROM revenue.proposed_mutation pm WHERE pm.transaction_id = t.id)  AS mutation_status
FROM core.transaction t
JOIN core.property p ON p.id = t.property_id;

CREATE VIEW rpt.token_history_view AS
SELECT
  tk.token_ref,
  tk.state_code,
  p.property_ref,
  h.state_version,
  h.operation,
  h.owner_set_json,
  encode(h.owner_set_hash, 'hex') AS owner_set_hash_hex,
  encode(h.state_hash, 'hex')     AS state_hash_hex,
  encode(h.prev_state_hash, 'hex') AS prev_state_hash_hex,
  encode(h.evidence_root, 'hex')  AS evidence_root_hex,
  h.onchain_tx_hash,
  h.recorded_at,
  tx.txn_ref
FROM chain.token_state_history h
JOIN chain.token tk ON tk.id = h.token_id
JOIN core.property p ON p.id = tk.property_id
LEFT JOIN core.transaction tx ON tx.id = h.transaction_id;
