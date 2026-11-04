-- The demo seed used a long dash as an empty placeholder; screens show a plain hyphen instead.
UPDATE core.chain_of_title SET reference_no = '-' WHERE reference_no = U&'\2014';
