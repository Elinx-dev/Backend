DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = 'core' AND table_name = 'property_owner'
       AND column_name = 'aadhaar_number'
  ) THEN
    ALTER TABLE core.property_owner ADD COLUMN aadhaar_number TEXT;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = 'core' AND table_name = 'property_owner'
       AND column_name = 'pan'
  ) THEN
    ALTER TABLE core.property_owner ADD COLUMN pan TEXT;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = 'core' AND table_name = 'property_owner'
       AND column_name = 'address'
  ) THEN
    ALTER TABLE core.property_owner ADD COLUMN address TEXT;
  END IF;
END
$$;

UPDATE core.property_owner
   SET aadhaar_number = '101010101010'
 WHERE aadhaar_number IS NULL;
