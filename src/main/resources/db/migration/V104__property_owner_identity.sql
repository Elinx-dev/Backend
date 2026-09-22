ALTER TABLE core.property_owner
    ADD COLUMN aadhaar_number TEXT,
    ADD COLUMN pan TEXT,
    ADD COLUMN address TEXT;

UPDATE core.property_owner
   SET aadhaar_number = '101010101010'
 WHERE aadhaar_number IS NULL;
