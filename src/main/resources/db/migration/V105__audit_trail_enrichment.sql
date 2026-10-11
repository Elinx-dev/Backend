-- Audit trail: classification, approval decisions and request context on every
-- audited action, plus the indexes the audit trail screen filters on.

ALTER TABLE sec.audit_log ADD COLUMN IF NOT EXISTS category      TEXT;
ALTER TABLE sec.audit_log ADD COLUMN IF NOT EXISTS decision      TEXT;
ALTER TABLE sec.audit_log ADD COLUMN IF NOT EXISTS from_status   TEXT;
ALTER TABLE sec.audit_log ADD COLUMN IF NOT EXISTS to_status     TEXT;
ALTER TABLE sec.audit_log ADD COLUMN IF NOT EXISTS stage_code    TEXT;
ALTER TABLE sec.audit_log ADD COLUMN IF NOT EXISTS http_method   TEXT;
ALTER TABLE sec.audit_log ADD COLUMN IF NOT EXISTS request_path  TEXT;
ALTER TABLE sec.audit_log ADD COLUMN IF NOT EXISTS user_agent    TEXT;

CREATE INDEX IF NOT EXISTS ix_audit_occurred ON sec.audit_log (occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_transaction ON sec.audit_log (transaction_ref, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_action ON sec.audit_log (action, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_actor_username ON sec.audit_log (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_category ON sec.audit_log (category, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_decision ON sec.audit_log (decision, occurred_at DESC);
