-- S2/S3: state onboarding (Tamil Nadu pilot, Karnataka as the second-state proof)
-- plus the configuration-driven workflow definition for all seven transaction types.

INSERT INTO cfg.state (state_code, state_name, extent_units, default_extent_unit, numeric_state_id, onboarded_on) VALUES
  ('TN','Tamil Nadu','{SQ_FT,SQ_M,CENT,ACRE,HECTARE}','SQ_FT',33,'2026-01-01'),
  ('KA','Karnataka','{SQ_FT,SQ_M,GUNTA,ACRE,HECTARE}','SQ_FT',29,'2026-04-01');

INSERT INTO cfg.state_module_config (state_code, module, mode, owner_department, effective_from) VALUES
  ('TN','REGISTRATION','FACILITATE','REGISTRATION','2026-01-01'),
  ('TN','SURVEY','FACILITATE','SURVEY','2026-01-01'),
  ('TN','REVENUE','FACILITATE','REVENUE','2026-01-01'),
  ('TN','RULE_CHECK','FACILITATE','REGISTRATION','2026-01-01'),
  ('TN','BLOCKCHAIN','FACILITATE','ADMIN','2026-01-01'),
  ('TN','PUBLIC_VIEW','FACILITATE','ADMIN','2026-01-01'),
  -- Karnataka onboards Registration in RECORD mode: SLATE records the outcome
  -- produced by the department's own system instead of driving the counter.
  ('KA','REGISTRATION','RECORD','REGISTRATION','2026-04-01'),
  ('KA','SURVEY','DISABLED','SURVEY','2026-04-01'),
  ('KA','REVENUE','RECORD','REVENUE','2026-04-01'),
  ('KA','RULE_CHECK','FACILITATE','REGISTRATION','2026-04-01'),
  ('KA','BLOCKCHAIN','FACILITATE','ADMIN','2026-04-01'),
  ('KA','PUBLIC_VIEW','FACILITATE','ADMIN','2026-04-01');

INSERT INTO cfg.fee_rule_config (state_code, default_valuation_basis, cash_mode_limit) VALUES
  ('TN','HIGHER_OF_BOTH', 20000),
  ('KA','HIGHER_OF_BOTH', 20000);

INSERT INTO cfg.numbering_series (state_code, series_code, pattern, scope, reset_policy) VALUES
  ('TN','PROPERTY_REF','TN-{DISTRICT}-{SEQ:8}','STATE','NEVER'),
  ('TN','TXN_REF','TXN-TN-{YEAR}-{SEQ:6}','YEAR','YEARLY'),
  ('TN','TOKEN_REF','SLATE-TN-{SEQ:8}','STATE','NEVER'),
  ('TN','REG_DOC_NO','{SEQ:6}/{YEAR}','YEAR','YEARLY'),
  ('KA','REG_DOC_NO','{SEQ:6}/{YEAR}','YEAR','YEARLY'),
  ('KA','PROPERTY_REF','KA-{DISTRICT}-{SEQ:8}','STATE','NEVER'),
  ('KA','TXN_REF','TXN-KA-{YEAR}-{SEQ:6}','YEAR','YEARLY'),
  ('KA','TOKEN_REF','SLATE-KA-{SEQ:8}','STATE','NEVER');

INSERT INTO cfg.rule_engine_config
  (state_code, engine, ec_lookback_years, extent_tolerance_pct, supported_land_types) VALUES
  ('TN','EC',30,2.00,'{RURAL,NATHAM}'),
  ('TN','REVENUE_OWNERSHIP',NULL,2.00,'{RURAL,NATHAM}'),
  ('KA','EC',30,2.00,'{RURAL,NATHAM}'),
  ('KA','REVENUE_OWNERSHIP',NULL,2.00,'{RURAL,NATHAM}');

INSERT INTO cfg.connector_config (state_code, connector_code, impl, base_url, auth_type) VALUES
  ('TN','AADHAAR_OTP','MOCK','http://localhost:9090/mock/aadhaar','NONE'),
  ('TN','EC','MOCK','http://localhost:9090/mock/ec','NONE'),
  ('TN','REVENUE_OWNERSHIP','MOCK','http://localhost:9090/mock/revenue','NONE'),
  ('TN','REVENUE_MUTATION','MOCK','http://localhost:9090/mock/revenue','NONE'),
  ('TN','PAYMENT_GATEWAY','MOCK','http://localhost:9090/mock/payment','NONE'),
  ('KA','AADHAAR_OTP','MOCK','http://localhost:9090/mock/aadhaar','NONE'),
  ('KA','EC','MOCK','http://localhost:9090/mock/ec','NONE'),
  ('KA','REVENUE_OWNERSHIP','MOCK','http://localhost:9090/mock/revenue','NONE'),
  ('KA','REVENUE_MUTATION','MOCK','http://localhost:9090/mock/revenue','NONE'),
  ('KA','PAYMENT_GATEWAY','MOCK','http://localhost:9090/mock/payment','NONE');

INSERT INTO cfg.chain_network_config
  (state_code, network_code, chain_id, consensus, rpc_url, ws_url, fallback_rpc_urls, explorer_url) VALUES
  ('TN','slate-local',2026,'QBFT','http://localhost:8545','ws://localhost:8546',
   '{http://localhost:8547,http://localhost:8548}','http://localhost:4000'),
  ('KA','slate-local',2026,'QBFT','http://localhost:8545','ws://localhost:8546',
   '{http://localhost:8547,http://localhost:8548}','http://localhost:4000');

INSERT INTO cfg.chain_validator (network_code, node_name, department, validator_address, is_bootnode) VALUES
  ('slate-local','besu-registration','REGISTRATION','0x0000000000000000000000000000000000000001',TRUE),
  ('slate-local','besu-revenue','REVENUE','0x0000000000000000000000000000000000000002',FALSE),
  ('slate-local','besu-survey','SURVEY','0x0000000000000000000000000000000000000003',FALSE),
  ('slate-local','besu-control','CONTROL','0x0000000000000000000000000000000000000004',FALSE);

INSERT INTO cfg.chain_endorser (state_code, operation, required_departments, min_signatures) VALUES
  ('TN','MINT','{REGISTRATION}',1),
  ('TN','UPDATE','{REGISTRATION}',1),
  ('TN','SPLIT','{SURVEY}',1),
  ('TN','ANNOTATE','{REVENUE}',1),
  ('TN','ANCHOR','{REGISTRATION}',1),
  ('KA','MINT','{REGISTRATION}',1),
  ('KA','UPDATE','{REGISTRATION}',1),
  ('KA','SPLIT','{SURVEY}',1),
  ('KA','ANNOTATE','{REVENUE}',1),
  ('KA','ANCHOR','{REGISTRATION}',1);

-- Jurisdiction (Tamil Nadu pilot area + one Karnataka SRO)
INSERT INTO master.jurisdiction
  (state_code, district_code, district_name, taluk_code, taluk_name, village_code, village_name, sro_code, sro_name, revenue_division) VALUES
  ('TN','CHN','Chennai','SHOL','Sholinganallur','PERUNGUDI','Perungudi','SRO-ADYAR','Adyar SRO','Chennai South'),
  ('TN','CHN','Chennai','SHOL','Sholinganallur','THORAIPAKKAM','Thoraipakkam','SRO-ADYAR','Adyar SRO','Chennai South'),
  ('TN','CHN','Chennai','SHOL','Sholinganallur','SHOLINGANALLUR','Sholinganallur','SRO-SHOL','Sholinganallur SRO','Chennai South'),
  ('TN','KAN','Kancheepuram','CHENGALPATTU','Chengalpattu','KELAMBAKKAM','Kelambakkam','SRO-KELAM','Kelambakkam SRO','Chengalpattu'),
  ('KA','BLR','Bengaluru Urban','SOUTH','Bengaluru South','BEGUR','Begur','SRO-BEGUR','Begur SRO','Bengaluru South');

-- Seven in-scope transaction types. survey_rule is what makes surveyRequired derivable.
INSERT INTO master.deed_type
  (state_code, code, name, workflow_family, survey_rule, side1_role, side2_role,
   witness_required, min_witness_count, requires_relationship_category, effective_from)
SELECT s.state_code, d.code, d.name, d.family, d.survey_rule, d.side1, d.side2,
       d.witness_required, d.min_witness, d.needs_relationship, '2026-01-01'
FROM cfg.state s
CROSS JOIN (VALUES
  ('SALE_FULL','Sale - Full Property','SALE','NEVER','SELLER','BUYER',true,2,false),
  ('SALE_UNDIVIDED_SHARE','Sale - Undivided Share','SALE','NEVER','SELLER','BUYER',true,2,false),
  ('SALE_PARTIAL_SUBDIVISION','Sale - Partial Property / Subdivision','SALE','ALWAYS','SELLER','BUYER',true,2,false),
  ('GIFT','Gift','GIFT','DERIVED_FROM_TRANSFER_SCOPE','DONOR','DONEE',true,2,true),
  ('SETTLEMENT','Settlement','SETTLEMENT','DERIVED_FROM_TRANSFER_SCOPE','SETTLOR','BENEFICIARY',true,2,true),
  ('RELEASE_RELINQUISHMENT','Release / Relinquishment of Co-owner Share','RELEASE','NEVER','RELEASOR','RELEASEE',true,2,false),
  ('PARTITION','Partition','PARTITION','ALWAYS','CO_OWNER','CO_OWNER',true,2,false)
) AS d(code, name, family, survey_rule, side1, side2, witness_required, min_witness, needs_relationship);

-- Fee master rows. Rates here are demo values carrying a fictional gazette
-- reference; the engine only knows how to look a row up, never what the rate is.
INSERT INTO master.fee_master
  (state_code, deed_type_code, subtype, relationship_category, valuation_basis,
   stamp_duty_rate, registration_fee_rate, registration_fee_max, tds_threshold, tds_rate,
   other_charges, gazette_reference, version, effective_from)
SELECT s.state_code, f.deed, f.subtype, f.relcat, f.basis,
       f.stamp, f.regfee, f.regmax, 5000000, 0.0100,
       '{"computerCharge": 100, "scanningChargePerPage": 10}'::jsonb,
       'DEMO-GAZETTE/2026/'||f.deed, 1, '2026-01-01'
FROM cfg.state s
CROSS JOIN (VALUES
  ('SALE_FULL','*','*','HIGHER_OF_BOTH',0.0700,0.0400,NULL::numeric),
  ('SALE_UNDIVIDED_SHARE','*','*','HIGHER_OF_BOTH',0.0700,0.0400,NULL),
  ('SALE_PARTIAL_SUBDIVISION','*','*','HIGHER_OF_BOTH',0.0700,0.0400,NULL),
  ('GIFT','*','FAMILY','GUIDELINE_VALUE',0.0100,0.0100,25000),
  ('GIFT','*','NON_FAMILY','GUIDELINE_VALUE',0.0700,0.0400,NULL),
  ('SETTLEMENT','*','FAMILY','GUIDELINE_VALUE',0.0100,0.0100,25000),
  ('SETTLEMENT','*','NON_FAMILY','GUIDELINE_VALUE',0.0700,0.0400,NULL),
  ('RELEASE_RELINQUISHMENT','*','*','GUIDELINE_VALUE',0.0100,0.0100,25000),
  ('PARTITION','*','*','GUIDELINE_VALUE',0.0100,0.0100,25000)
) AS f(deed, subtype, relcat, basis, stamp, regfee, regmax);

INSERT INTO master.guideline_value
  (state_code, district_code, village_code, street_or_zone, land_type_code, rate_per_unit, unit, notification_reference, effective_from) VALUES
  ('TN','CHN','PERUNGUDI','OMR Zone A','NATHAM',7500,'SQ_FT','TN-GV-2026-01','2026-01-01'),
  ('TN','CHN','THORAIPAKKAM','OMR Zone B','NATHAM',6800,'SQ_FT','TN-GV-2026-01','2026-01-01'),
  ('TN','CHN','SHOLINGANALLUR','OMR Zone A','NATHAM',8200,'SQ_FT','TN-GV-2026-01','2026-01-01'),
  ('TN','KAN','KELAMBAKKAM','ECR Zone','RURAL',2400,'SQ_FT','TN-GV-2026-01','2026-01-01'),
  ('KA','BLR','BEGUR','Hosur Road Zone','NATHAM',6100,'SQ_FT','KA-GV-2026-01','2026-04-01');

-- One published workflow per state per deed type, with the common pipeline.
DO $$
DECLARE
  st RECORD;
  dt RECORD;
  wf_id BIGINT;
BEGIN
  FOR st IN SELECT state_code FROM cfg.state LOOP
    FOR dt IN SELECT code, survey_rule FROM master.deed_type WHERE state_code = st.state_code LOOP
      INSERT INTO cfg.workflow_definition
        (state_code, deed_type_code, workflow_code, version, status, published_at, effective_from)
      VALUES (st.state_code, dt.code, 'COMMON_PIPELINE', 1, 'PUBLISHED', now(), '2026-01-01')
      RETURNING id INTO wf_id;

      INSERT INTO cfg.workflow_stage
        (workflow_id, seq, stage_code, stage_label, status_on_enter, owner_role, optional, skip_condition, ui_route)
      VALUES
        (wf_id, 1,'PROPERTY_IDENTIFICATION','Property Identification & Transaction Type','DRAFT','REGISTRATION_OFFICER',false,NULL,'/ro/transactions/:ref/property'),
        (wf_id, 2,'PARTY_DETAILS','Party & Transaction Details','DRAFT','REGISTRATION_OFFICER',false,NULL,'/ro/transactions/:ref/parties'),
        (wf_id, 3,'WITNESSES','Witnesses','DRAFT','REGISTRATION_OFFICER',false,NULL,'/ro/transactions/:ref/witnesses'),
        (wf_id, 4,'CONSENT','Aadhaar OTP Consent','CONSENT_PENDING','REGISTRATION_OFFICER',false,NULL,'/ro/transactions/:ref/consent'),
        (wf_id, 5,'RULE_CHECK','Rule Check Engine','RULE_CHECK_PENDING','SYSTEM',false,NULL,'/ro/transactions/:ref/rule-checks'),
        (wf_id, 6,'FEES','Guideline Value & Fees','FEE_PAYMENT_PENDING','REGISTRATION_OFFICER',false,NULL,'/ro/transactions/:ref/fees'),
        (wf_id, 7,'PAYMENT','Payment','FEE_PAYMENT_PENDING','REGISTRATION_OFFICER',false,NULL,'/ro/transactions/:ref/payment'),
        (wf_id, 8,'REGISTRATION','Registration','REGISTERED','REGISTRATION_OFFICER',false,NULL,'/ro/transactions/:ref/register'),
        (wf_id, 9,'SURVEY','Survey','SURVEY_PENDING','SURVEYOR',
           CASE WHEN dt.survey_rule = 'ALWAYS' THEN false ELSE true END,
           CASE WHEN dt.survey_rule = 'NEVER' THEN 'always'
                WHEN dt.survey_rule = 'DERIVED_FROM_TRANSFER_SCOPE' THEN 'transferScope != PHYSICAL_PARTIAL_EXTENT_SUBDIVISION'
                ELSE NULL END,
           '/surveyor/transactions/:ref/survey'),
        (wf_id,10,'REVENUE_VERIFICATION','Revenue Verification','VAO_PENDING','VAO',false,NULL,'/vao/mutations/:id'),
        (wf_id,11,'MUTATION_DECISION','Final Mutation Decision','TAHSILDAR_PENDING','TAHSILDAR',false,NULL,'/tahsildar/mutations/:id'),
        (wf_id,12,'RECORD_UPDATE','Record Update','REVENUE_APPROVED','SYSTEM',false,NULL,NULL);

      INSERT INTO cfg.workflow_transition
        (workflow_id, from_status, to_status, action_code, allowed_roles, guard_expr, requires_reason, emits_event)
      VALUES
        (wf_id,'DRAFT','CONSENT_PENDING','REQUEST_CONSENT','{REGISTRATION_OFFICER}','partiesAndWitnessesComplete',false,'ConsentRequested'),
        (wf_id,'CONSENT_PENDING','RULE_CHECK_PENDING','CONSENT_COMPLETE','{REGISTRATION_OFFICER,SYSTEM}','allPartiesConsentVerified',false,'ConsentCompleted'),
        (wf_id,'CONSENT_PENDING','DRAFT','REOPEN_DRAFT','{REGISTRATION_OFFICER}',NULL,true,NULL),
        (wf_id,'RULE_CHECK_PENDING','FEE_PAYMENT_PENDING','RULE_CHECKS_CLEAR','{REGISTRATION_OFFICER,SYSTEM}','ruleChecksRun',false,'RuleChecksCompleted'),
        (wf_id,'RULE_CHECK_PENDING','EXCEPTION','RAISE_EXCEPTION','{REGISTRATION_OFFICER,SYSTEM}',NULL,true,'ExceptionRaised'),
        (wf_id,'EXCEPTION','RULE_CHECK_PENDING','RESUBMIT','{REGISTRATION_OFFICER}',NULL,true,'Resubmitted'),
        (wf_id,'EXCEPTION','FEE_PAYMENT_PENDING','ACKNOWLEDGE_ADVISORY','{REGISTRATION_OFFICER}','rulesAreAdvisory',true,'AdvisoryAcknowledged'),
        (wf_id,'FEE_PAYMENT_PENDING','SUBMITTED','RECORD_PAYMENT','{REGISTRATION_OFFICER}','feesFullyPaid',false,'PaymentRecorded'),
        (wf_id,'SUBMITTED','REGISTERED','REGISTER','{REGISTRATION_OFFICER}','readyToRegister',false,'Registered'),
        (wf_id,'REGISTERED','SURVEY_PENDING','START_SURVEY','{REGISTRATION_OFFICER,SURVEYOR,SYSTEM}','surveyRequired',false,'SurveyStarted'),
        (wf_id,'REGISTERED','VAO_PENDING','PROPOSE_MUTATION','{REGISTRATION_OFFICER,SYSTEM}','surveyNotRequired',false,'MutationProposed'),
        (wf_id,'SURVEY_PENDING','VAO_PENDING','SUBMIT_SURVEY','{SURVEYOR}','surveyWithinTolerance',false,'SurveySubmitted'),
        (wf_id,'VAO_PENDING','OBJECTION_PENDING','RAISE_OBJECTION','{VAO}',NULL,true,'ObjectionRaised'),
        (wf_id,'OBJECTION_PENDING','VAO_PENDING','DISPOSE_OBJECTION','{VAO}',NULL,true,'ObjectionDisposed'),
        (wf_id,'VAO_PENDING','TAHSILDAR_PENDING','VAO_FORWARD','{VAO}','mutationVerified',false,'MutationForwarded'),
        (wf_id,'TAHSILDAR_PENDING','REVENUE_APPROVED','TAHSILDAR_APPROVE','{TAHSILDAR}',NULL,false,'MutationApproved'),
        (wf_id,'DRAFT','WITHDRAWN','WITHDRAW','{REGISTRATION_OFFICER}',NULL,true,'Withdrawn'),
        (wf_id,'CONSENT_PENDING','WITHDRAWN','WITHDRAW','{REGISTRATION_OFFICER}',NULL,true,'Withdrawn'),
        (wf_id,'RULE_CHECK_PENDING','WITHDRAWN','WITHDRAW','{REGISTRATION_OFFICER}',NULL,true,'Withdrawn'),
        (wf_id,'EXCEPTION','WITHDRAWN','WITHDRAW','{REGISTRATION_OFFICER}',NULL,true,'Withdrawn'),
        (wf_id,'FEE_PAYMENT_PENDING','WITHDRAWN','WITHDRAW','{REGISTRATION_OFFICER}',NULL,true,'Withdrawn'),
        (wf_id,'SUBMITTED','WITHDRAWN','WITHDRAW','{REGISTRATION_OFFICER}',NULL,true,'Withdrawn');
    END LOOP;
  END LOOP;
END
$$;

-- Field configuration: which fields appear, for which deed type, at which stage.
INSERT INTO cfg.field_config
  (state_code, deed_type_code, stage_code, field_id, visible, required, masked, option_set_code, display_order, ui_group, effective_from)
SELECT '*', c.deed, c.stage, fd.id, true, c.required, c.masked, c.optset, c.ord, c.grp, '2026-01-01'
FROM (VALUES
  ('*','PROPERTY_IDENTIFICATION','PROPERTY','ulpin',false,false,NULL,1,'Identification'),
  ('*','PROPERTY_IDENTIFICATION','PROPERTY','propertyTypeCode',true,false,'PROPERTY_TYPE',2,'Identification'),
  ('*','PROPERTY_IDENTIFICATION','PROPERTY','surveyNo',true,false,NULL,3,'Identification'),
  ('*','PROPERTY_IDENTIFICATION','PROPERTY','subdivisionNo',false,false,NULL,4,'Identification'),
  ('*','PROPERTY_IDENTIFICATION','PROPERTY','extentValue',true,false,NULL,5,'Extent'),
  ('*','PROPERTY_IDENTIFICATION','PROPERTY','extentUnit',true,false,'EXTENT_UNIT',6,'Extent'),
  ('*','PROPERTY_IDENTIFICATION','TRANSACTION','sroCode',true,false,NULL,7,'Identification'),
  ('SALE_FULL','PARTY_DETAILS','TRANSACTION','declaredConsideration',true,false,NULL,1,'Transaction'),
  ('SALE_FULL','PARTY_DETAILS','TRANSACTION','modeOfConsideration',true,false,'MODE_OF_CONSIDERATION',2,'Transaction'),
  ('SALE_UNDIVIDED_SHARE','PARTY_DETAILS','TRANSACTION','declaredConsideration',true,false,NULL,1,'Transaction'),
  ('SALE_UNDIVIDED_SHARE','PARTY_DETAILS','TRANSACTION','modeOfConsideration',true,false,'MODE_OF_CONSIDERATION',2,'Transaction'),
  ('SALE_PARTIAL_SUBDIVISION','PARTY_DETAILS','TRANSACTION','declaredConsideration',true,false,NULL,1,'Transaction'),
  ('SALE_PARTIAL_SUBDIVISION','PARTY_DETAILS','TRANSACTION','modeOfConsideration',true,false,'MODE_OF_CONSIDERATION',2,'Transaction'),
  ('GIFT','PARTY_DETAILS','TRANSACTION','transferScope',true,false,'TRANSFER_SCOPE',1,'Transaction'),
  ('GIFT','PARTY_DETAILS','TRANSACTION','relationshipCategory',true,false,NULL,2,'Transaction'),
  ('SETTLEMENT','PARTY_DETAILS','TRANSACTION','transferScope',true,false,'TRANSFER_SCOPE',1,'Transaction'),
  ('SETTLEMENT','PARTY_DETAILS','TRANSACTION','relationshipCategory',true,false,NULL,2,'Transaction'),
  ('SETTLEMENT','PARTY_DETAILS','TRANSACTION','basisOfSettlement',true,false,'BASIS_OF_SETTLEMENT',3,'Transaction'),
  ('RELEASE_RELINQUISHMENT','PARTY_DETAILS','TRANSACTION','shareBeingReleased',true,false,NULL,1,'Transaction'),
  ('RELEASE_RELINQUISHMENT','PARTY_DETAILS','TRANSACTION','declaredConsideration',false,false,NULL,2,'Transaction'),
  ('PARTITION','PARTY_DETAILS','TRANSACTION','resultingSubparcelCount',true,false,NULL,1,'Transaction'),
  ('*','PARTY_DETAILS','PARTY','partyType',true,false,'PARTY_TYPE',1,'Party'),
  ('*','PARTY_DETAILS','PARTY','name',true,false,NULL,2,'Party'),
  ('*','PARTY_DETAILS','PARTY','aadhaarNumber',true,true,NULL,3,'Party'),
  ('*','PARTY_DETAILS','PARTY','pan',false,false,NULL,4,'Party'),
  ('*','PARTY_DETAILS','PARTY','address',true,false,NULL,5,'Party'),
  ('*','PARTY_DETAILS','PARTY','existingSharePct',false,false,NULL,6,'Share'),
  ('*','PARTY_DETAILS','PARTY','shareTransferredPct',true,false,NULL,7,'Share'),
  ('*','PARTY_DETAILS','PARTY','resultingSharePct',false,false,NULL,8,'Share'),
  ('*','FEES','TRANSACTION','guidelineValue',true,false,NULL,1,'Fees')
) AS c(deed, stage, entity, field, required, masked, optset, ord, grp)
JOIN cfg.field_definition fd ON fd.entity = c.entity AND fd.field_code = c.field;
