-- Every UI dropdown now loads its values from cfg.option_value (bootstrap optionSets).
-- Keeps the existing DB values/labels and adds the values that were only hard-coded in the UI.

INSERT INTO cfg.option_set (code, description) VALUES
  ('LAND_TYPE', 'Land type'),
  ('CLASSIFICATION', 'Land classification'),
  ('NATURE_OF_TITLE', 'How the current owner acquired title'),
  ('EXTENT_UNIT', 'Unit of land extent'),
  ('MODE_OF_CONSIDERATION', 'Mode of consideration'),
  ('TRANSFER_SCOPE', 'Transfer scope'),
  ('PAYMENT_MODE', 'Payment mode'),
  ('TRANSACTION_EXTENT_UNIT', 'Unit of extent or share transferred in a transaction'),
  ('NATURE_OF_TRANSACTION', 'Nature of a chain-of-title entry'),
  ('BOUNDARY_POINT', 'Boundary measurement end point'),
  ('DIRECTION', 'Survey sketch boundary direction'),
  ('LENGTH_UNIT', 'Survey sketch length unit'),
  ('YES_NO', 'Yes / No answer'),
  ('LIFE_STATUS', 'Living or deceased'),
  ('MARITAL_STATUS', 'Marital status')
ON CONFLICT (code) DO NOTHING;

INSERT INTO cfg.option_value (option_set_code, state_code, value_code, label, sort_order, active) VALUES
  ('LAND_TYPE', '*', 'RURAL', 'Rural', 1, TRUE),
  ('LAND_TYPE', '*', 'NATHAM', 'Natham (House Site)', 2, TRUE),
  ('LAND_TYPE', '*', 'URBAN', 'Urban', 3, TRUE),

  ('CLASSIFICATION', '*', 'WET', 'Wet (Nanjai)', 1, TRUE),
  ('CLASSIFICATION', '*', 'DRY', 'Dry (Punjai)', 2, TRUE),
  ('CLASSIFICATION', '*', 'MANAVARI', 'Manavari', 3, TRUE),
  ('CLASSIFICATION', '*', 'HOUSE_SITE', 'House Site', 4, TRUE),

  ('NATURE_OF_TITLE', '*', 'PURCHASE', 'Purchase', 1, TRUE),
  ('NATURE_OF_TITLE', '*', 'INHERITANCE', 'Inheritance', 2, TRUE),
  ('NATURE_OF_TITLE', '*', 'GIFT', 'Gift', 3, TRUE),
  ('NATURE_OF_TITLE', '*', 'SETTLEMENT', 'Settlement', 4, TRUE),
  ('NATURE_OF_TITLE', '*', 'PARTITION', 'Partition', 5, TRUE),
  ('NATURE_OF_TITLE', '*', 'GOVERNMENT_GRANT', 'Government Grant', 6, TRUE),
  ('NATURE_OF_TITLE', '*', 'FREEHOLD', 'Freehold', 7, TRUE),
  ('NATURE_OF_TITLE', '*', 'LEASEHOLD', 'Leasehold', 8, TRUE),

  ('EXTENT_UNIT', '*', 'SQ_FT', 'Square Feet', 1, TRUE),
  ('EXTENT_UNIT', '*', 'SQ_M', 'Square Metres', 2, TRUE),
  ('EXTENT_UNIT', '*', 'CENT', 'Cent', 3, TRUE),
  ('EXTENT_UNIT', '*', 'ACRE', 'Acre', 4, TRUE),
  ('EXTENT_UNIT', '*', 'HECTARE', 'Hectare', 5, TRUE),

  ('TRANSACTION_EXTENT_UNIT', '*', 'SQ_FT', 'Square Feet', 1, TRUE),
  ('TRANSACTION_EXTENT_UNIT', '*', 'SQ_M', 'Square Metres', 2, TRUE),
  ('TRANSACTION_EXTENT_UNIT', '*', 'CENT', 'Cent', 3, TRUE),
  ('TRANSACTION_EXTENT_UNIT', '*', 'ACRE', 'Acre', 4, TRUE),
  ('TRANSACTION_EXTENT_UNIT', '*', 'HECTARE', 'Hectare', 5, TRUE),
  ('TRANSACTION_EXTENT_UNIT', '*', 'PERCENT', 'Percent (share)', 6, TRUE),

  ('MODE_OF_CONSIDERATION', '*', 'BANK_TRANSFER', 'Bank Transfer', 1, TRUE),
  ('MODE_OF_CONSIDERATION', '*', 'DEMAND_DRAFT', 'Demand Draft', 2, TRUE),
  ('MODE_OF_CONSIDERATION', '*', 'CHEQUE', 'Cheque', 3, TRUE),
  ('MODE_OF_CONSIDERATION', '*', 'CASH', 'Cash', 4, TRUE),
  ('MODE_OF_CONSIDERATION', '*', 'MIXED', 'Mixed', 5, TRUE),

  ('TRANSFER_SCOPE', '*', 'FULL_PROPERTY', 'Full property', 1, TRUE),
  ('TRANSFER_SCOPE', '*', 'UNDIVIDED_SHARE', 'Undivided share', 2, TRUE),
  ('TRANSFER_SCOPE', '*', 'PHYSICAL_PARTIAL_EXTENT_SUBDIVISION', 'Partial extent / subdivision', 3, TRUE),

  ('PAYMENT_MODE', '*', 'E_CHALLAN', 'E-Challan', 1, TRUE),
  ('PAYMENT_MODE', '*', 'UPI', 'UPI', 2, TRUE),
  ('PAYMENT_MODE', '*', 'CARD', 'Card', 3, TRUE),
  ('PAYMENT_MODE', '*', 'DD', 'Demand draft', 4, TRUE),

  ('NATURE_OF_TRANSACTION', '*', 'Sale', 'Sale', 1, TRUE),
  ('NATURE_OF_TRANSACTION', '*', 'Purchase', 'Purchase', 2, TRUE),
  ('NATURE_OF_TRANSACTION', '*', 'Gift', 'Gift', 3, TRUE),
  ('NATURE_OF_TRANSACTION', '*', 'Settlement', 'Settlement', 4, TRUE),
  ('NATURE_OF_TRANSACTION', '*', 'Partition', 'Partition', 5, TRUE),
  ('NATURE_OF_TRANSACTION', '*', 'Inheritance', 'Inheritance', 6, TRUE),
  ('NATURE_OF_TRANSACTION', '*', 'Exchange', 'Exchange', 7, TRUE),
  ('NATURE_OF_TRANSACTION', '*', 'Government Grant', 'Government Grant', 8, TRUE),
  ('NATURE_OF_TRANSACTION', '*', 'Release', 'Release', 9, TRUE),
  ('NATURE_OF_TRANSACTION', '*', 'Other', 'Other', 10, TRUE),

  ('BOUNDARY_POINT', '*', 'NORTH', 'North', 1, TRUE),
  ('BOUNDARY_POINT', '*', 'SOUTH', 'South', 2, TRUE),
  ('BOUNDARY_POINT', '*', 'EAST', 'East', 3, TRUE),
  ('BOUNDARY_POINT', '*', 'WEST', 'West', 4, TRUE),

  ('DIRECTION', '*', 'North', 'North', 1, TRUE),
  ('DIRECTION', '*', 'East', 'East', 2, TRUE),
  ('DIRECTION', '*', 'South', 'South', 3, TRUE),
  ('DIRECTION', '*', 'West', 'West', 4, TRUE),
  ('DIRECTION', '*', 'North-East', 'North-East', 5, TRUE),
  ('DIRECTION', '*', 'South-East', 'South-East', 6, TRUE),
  ('DIRECTION', '*', 'South-West', 'South-West', 7, TRUE),
  ('DIRECTION', '*', 'North-West', 'North-West', 8, TRUE),

  ('LENGTH_UNIT', '*', 'FT', 'ft', 1, TRUE),
  ('LENGTH_UNIT', '*', 'M', 'm', 2, TRUE),

  ('YES_NO', '*', 'YES', 'Yes', 1, TRUE),
  ('YES_NO', '*', 'NO', 'No', 2, TRUE),

  ('LIFE_STATUS', '*', 'LIVING', 'Living', 1, TRUE),
  ('LIFE_STATUS', '*', 'DECEASED', 'Deceased', 2, TRUE),

  ('MARITAL_STATUS', '*', 'MARRIED', 'Married', 1, TRUE),
  ('MARITAL_STATUS', '*', 'UNMARRIED', 'Unmarried', 2, TRUE),
  ('MARITAL_STATUS', '*', 'WIDOWED', 'Widowed', 3, TRUE),
  ('MARITAL_STATUS', '*', 'DIVORCED', 'Divorced', 4, TRUE)
ON CONFLICT (option_set_code, state_code, value_code) DO UPDATE SET active = TRUE;

-- The old hard-coded Classification list saved 'Dry' / 'Wet'; align them with the configured codes.
UPDATE core.property SET classification_code = UPPER(classification_code)
 WHERE classification_code IN ('Dry', 'Wet');
