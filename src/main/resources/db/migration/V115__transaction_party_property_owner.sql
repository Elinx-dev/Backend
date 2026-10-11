-- First-party rows copied from an existing property owner keep a reference to it,
-- so party format rules can skip legacy owner data that predates those checks.
ALTER TABLE core.transaction_party
  ADD COLUMN IF NOT EXISTS property_owner_id BIGINT REFERENCES core.property_owner(id);
