-- EC rule check: court attachment / decree classification, survey-link review
-- status, DB-maintained keyword lists and configurable blocking outcomes.

ALTER TABLE rules.ec_entry DROP CONSTRAINT IF EXISTS ec_entry_classified_type_check;
ALTER TABLE rules.ec_entry ADD CONSTRAINT ec_entry_classified_type_check CHECK (classified_type IN (
  'MORTGAGE_CREATE','RECEIPT','COURT_ORDER','COURT_ATTACHMENT','COURT_DECREE','COURT_UNCLEAR',
  'NON_ENCUMBRANCE_EVENT','UNCLASSIFIED_ENTRY'));

ALTER TABLE rules.ec_entry DROP CONSTRAINT IF EXISTS ec_entry_match_status_check;
ALTER TABLE rules.ec_entry ADD CONSTRAINT ec_entry_match_status_check CHECK (match_status IN (
  'MATCH','HISTORICAL_MATCH','AMBIGUOUS','LINK_UNCONFIRMED','NOT_MATCHED'));

-- Keywords used to classify EC entries. COURT_ATTACHMENT and
-- COURT_ATTACHMENT_EXCLUDE are matched against Document Remarks; every other
-- category is matched against Nature / Type of Document.
CREATE TABLE IF NOT EXISTS cfg.ec_classification_keyword (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  category    TEXT NOT NULL CHECK (category IN ('RECEIPT','MORTGAGE','COURT','COURT_ATTACHMENT',
                                                'COURT_ATTACHMENT_EXCLUDE','COURT_DECREE','NON_ENCUMBRANCE')),
  match_field TEXT NOT NULL CHECK (match_field IN ('NATURE','REMARKS')),
  keyword     TEXT NOT NULL,
  active      BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (category, keyword)
);

INSERT INTO cfg.ec_classification_keyword (category, match_field, keyword) VALUES
  ('RECEIPT','NATURE','receipt'),
  ('RECEIPT','NATURE','discharge'),
  ('RECEIPT','NATURE','release of mortgage'),
  ('RECEIPT','NATURE','cancellation of mortgage'),
  ('RECEIPT','NATURE','redemption'),
  ('MORTGAGE','NATURE','mortgage'),
  ('MORTGAGE','NATURE','deposit of title deed'),
  ('MORTGAGE','NATURE','hypothecation'),
  ('MORTGAGE','NATURE','hypothec'),
  ('COURT','NATURE','court'),
  ('COURT','NATURE','attachment'),
  ('COURT','NATURE','decree'),
  ('COURT','NATURE','judgment'),
  ('COURT','NATURE','judgement'),
  ('COURT','NATURE','injunction'),
  ('COURT','NATURE','order'),
  ('COURT','NATURE','lis pendens'),
  ('COURT','NATURE','suit'),
  ('COURT','NATURE','tribunal'),
  ('COURT_ATTACHMENT','REMARKS','attachment'),
  ('COURT_ATTACHMENT','REMARKS','attached'),
  ('COURT_ATTACHMENT_EXCLUDE','REMARKS','raised'),
  ('COURT_ATTACHMENT_EXCLUDE','REMARKS','lifted'),
  ('COURT_ATTACHMENT_EXCLUDE','REMARKS','vacated'),
  ('COURT_ATTACHMENT_EXCLUDE','REMARKS','withdrawn'),
  ('COURT_ATTACHMENT_EXCLUDE','REMARKS','released from attachment'),
  ('COURT_DECREE','NATURE','decree'),
  ('COURT_DECREE','NATURE','judgment'),
  ('COURT_DECREE','NATURE','judgement'),
  ('NON_ENCUMBRANCE','NATURE','sale'),
  ('NON_ENCUMBRANCE','NATURE','gift'),
  ('NON_ENCUMBRANCE','NATURE','settlement'),
  ('NON_ENCUMBRANCE','NATURE','partition'),
  ('NON_ENCUMBRANCE','NATURE','release'),
  ('NON_ENCUMBRANCE','NATURE','lease'),
  ('NON_ENCUMBRANCE','NATURE','power of attorney'),
  ('NON_ENCUMBRANCE','NATURE','exchange'),
  ('NON_ENCUMBRANCE','NATURE','rectification')
ON CONFLICT (category, keyword) DO NOTHING;

-- Rule outcomes (reason codes) that stop pre-registration for an engine.
ALTER TABLE cfg.rule_engine_config
  ADD COLUMN IF NOT EXISTS blocking_reason_codes TEXT[] NOT NULL DEFAULT '{}';
UPDATE cfg.rule_engine_config SET blocking_reason_codes = '{COURT_ATTACHMENT}' WHERE engine = 'EC';

UPDATE cfg.workflow_transition
   SET guard_expr = 'ruleChecksPassed'
 WHERE action_code = 'RULE_CHECKS_CLEAR' AND guard_expr = 'ruleChecksRun';
