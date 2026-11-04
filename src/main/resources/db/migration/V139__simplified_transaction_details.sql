-- Transaction details simplification:
--   Gift and Release are always charged the non-family rate (7% stamp duty + 4% registration fee),
--   so the officer no longer picks a relationship category for them.

UPDATE master.transaction_type SET default_relationship_category = 'NON_FAMILY'
 WHERE code IN ('GIFT', 'RELEASE');

UPDATE master.fee_relationship_category
   SET transaction_types = array_append(transaction_types, 'RELEASE')
 WHERE code = 'NON_FAMILY' AND NOT ('RELEASE' = ANY (transaction_types));

INSERT INTO master.fee_master (state_code, deed_type_code, subtype, relationship_category, transfer_nature,
    valuation_basis, stamp_duty_rate, stamp_duty_flat, stamp_duty_min, stamp_duty_max,
    registration_fee_rate, registration_fee_flat, registration_fee_min, registration_fee_max,
    tds_threshold, tds_rate, other_charges, gazette_reference, approved_by, approval_date,
    version, effective_from, effective_to, status, stamp_duty_calc_type, registration_fee_calc_type)
SELECT r.state_code, 'RELEASE', r.subtype, 'NON_FAMILY', r.transfer_nature,
       'GUIDELINE_VALUE', g.stamp_duty_rate, g.stamp_duty_flat, g.stamp_duty_min, g.stamp_duty_max,
       g.registration_fee_rate, g.registration_fee_flat, g.registration_fee_min, g.registration_fee_max,
       r.tds_threshold, r.tds_rate, r.other_charges, g.gazette_reference, g.approved_by, g.approval_date,
       1, r.effective_from, NULL, 'ACTIVE', g.stamp_duty_calc_type, g.registration_fee_calc_type
  FROM master.fee_master r
  JOIN master.fee_master g
    ON g.state_code = r.state_code AND g.deed_type_code = 'GIFT' AND g.relationship_category = 'NON_FAMILY'
   AND g.status = 'ACTIVE' AND g.effective_to IS NULL
 WHERE r.deed_type_code = 'RELEASE' AND r.relationship_category = '*' AND r.subtype = '*'
   AND r.status = 'ACTIVE' AND r.effective_to IS NULL
   AND NOT EXISTS (SELECT 1 FROM master.fee_master x
                    WHERE x.state_code = r.state_code AND x.deed_type_code = 'RELEASE'
                      AND x.relationship_category = 'NON_FAMILY' AND x.status = 'ACTIVE');
