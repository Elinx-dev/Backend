-- Incoming parties (buyer / vendee / settlee ...) are captured per owner type, like Mint Property owners.
-- Share percentages are no longer recorded on transaction parties.
ALTER TABLE core.transaction_party
    ADD COLUMN owner_type_code TEXT,
    ADD COLUMN mobile TEXT,
    ADD COLUMN registration_no TEXT,
    ADD COLUMN representative_role TEXT,
    ADD COLUMN representative_name TEXT,
    ADD COLUMN representative_designation TEXT,
    ADD COLUMN representative_aadhaar_last4 CHAR(4),
    ADD COLUMN representative_pan TEXT,
    ADD COLUMN representative_mobile TEXT;

ALTER TABLE core.transaction_party
    ADD CONSTRAINT ck_transaction_party_owner_type CHECK (owner_type_code IS NULL OR owner_type_code IN (
        'INDIVIDUAL', 'SOLE_PROPRIETORSHIP', 'PARTNERSHIP_FIRM', 'HUF', 'LLP', 'PRIVATE_LIMITED_COMPANY',
        'PUBLIC_LIMITED_COMPANY', 'ONE_PERSON_COMPANY', 'TRUST', 'SOCIETY', 'AOP_BOI', 'GOVERNMENT',
        'OTHER_LEGAL_ENTITY')),
    ADD CONSTRAINT ck_transaction_party_representative_role CHECK (representative_role IS NULL OR representative_role IN (
        'AUTHORISED_SIGNATORY', 'AUTHORISED_PARTNER', 'KARTA', 'AUTHORISED_TRUSTEE'));

ALTER TABLE core.transaction_party
    DROP COLUMN existing_share_pct,
    DROP COLUMN share_transferred_pct,
    DROP COLUMN resulting_share_pct;

UPDATE cfg.validation_rule
   SET active = FALSE
 WHERE expression IN ('releasedShareMatchesExisting', 'sharesAgainstChildParcel');
