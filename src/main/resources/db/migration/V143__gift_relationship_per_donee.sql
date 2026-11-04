-- Each donee on a Gift picks a Relationship Category (same blood-relation list as Settlement).
-- The Gift fee stays at the non-family rate; the relationship is recorded per party only.
UPDATE master.transaction_type SET blood_relation_required = true WHERE code = 'GIFT';
