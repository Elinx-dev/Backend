-- Computer / scanning "other charges" are no longer part of the fee.
-- Clearing the config also stops older builds asking for a page count.
UPDATE master.fee_master
   SET other_charges = '[]'::jsonb
 WHERE other_charges IS DISTINCT FROM '[]'::jsonb;
