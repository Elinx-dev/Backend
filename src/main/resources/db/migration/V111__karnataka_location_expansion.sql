-- Karnataka demo expansion for the Mint Property location and survey flows.
-- Adds a richer Karnataka state config, more registration districts, the
-- supporting SRO/taluk/village hierarchy, and guideline rows for the new areas.

INSERT INTO cfg.state
  (state_code, state_name, extent_units, default_extent_unit, numeric_state_id, onboarded_on, languages)
VALUES
  ('KA', 'Karnataka', '{SQ_FT,SQ_M,GUNTA,ACRE,HECTARE}', 'SQ_FT', 29, '2026-04-01', '{en,kn}')
ON CONFLICT (state_code) DO UPDATE SET
  state_name = EXCLUDED.state_name,
  extent_units = EXCLUDED.extent_units,
  default_extent_unit = EXCLUDED.default_extent_unit,
  numeric_state_id = EXCLUDED.numeric_state_id,
  onboarded_on = EXCLUDED.onboarded_on,
  languages = EXCLUDED.languages;

INSERT INTO master.registration_district (state_code, district_code, district_name, sort_order) VALUES
  ('KA', 'BLR', 'Bengaluru Urban', 1),
  ('KA', 'BRR', 'Bengaluru Rural', 2),
  ('KA', 'MYS', 'Mysuru', 3),
  ('KA', 'DKN', 'Dakshina Kannada', 4),
  ('KA', 'HDD', 'Hubballi-Dharwad', 5)
ON CONFLICT (state_code, district_code) DO NOTHING;

INSERT INTO master.sub_registrar_office (district_id, sro_code, sro_name, sort_order)
SELECT d.id, v.sro_code, v.sro_name, v.sort_order
  FROM (VALUES
    ('KA', 'BLR', 'SRO-BEGUR', 'Begur SRO', 1),
    ('KA', 'BRR', 'SRO-DEVANAHALLI', 'Devanahalli SRO', 1),
    ('KA', 'MYS', 'SRO-MYSURU-NORTH', 'Mysuru North SRO', 1),
    ('KA', 'DKN', 'SRO-MANGALURU-NORTH', 'Mangaluru North SRO', 1),
    ('KA', 'HDD', 'SRO-HUBBALLI-NORTH', 'Hubballi North SRO', 1)
  ) AS v(state_code, district_code, sro_code, sro_name, sort_order)
  JOIN master.registration_district d
    ON d.state_code = v.state_code
   AND d.district_code = v.district_code
ON CONFLICT (district_id, sro_code) DO NOTHING;

INSERT INTO master.taluk (sro_id, taluk_code, taluk_name, sort_order)
SELECT s.id, v.taluk_code, v.taluk_name, v.sort_order
  FROM (VALUES
    ('KA', 'BLR', 'SRO-BEGUR', 'ELECTRONIC_CITY', 'Electronic City', 1),
    ('KA', 'BRR', 'SRO-DEVANAHALLI', 'DEVANAHALLI', 'Devanahalli', 1),
    ('KA', 'MYS', 'SRO-MYSURU-NORTH', 'MYSURU', 'Mysuru', 1),
    ('KA', 'DKN', 'SRO-MANGALURU-NORTH', 'MANGALURU', 'Mangaluru', 1),
    ('KA', 'HDD', 'SRO-HUBBALLI-NORTH', 'HUBBALLI', 'Hubballi', 1)
  ) AS v(state_code, district_code, sro_code, taluk_code, taluk_name, sort_order)
  JOIN master.registration_district d
    ON d.state_code = v.state_code
   AND d.district_code = v.district_code
  JOIN master.sub_registrar_office s
    ON s.district_id = d.id
   AND s.sro_code = v.sro_code
ON CONFLICT (sro_id, taluk_code) DO NOTHING;

INSERT INTO master.revenue_village (taluk_id, village_code, village_name, sort_order)
SELECT t.id, v.village_code, v.village_name, v.sort_order
  FROM (VALUES
    ('KA', 'BLR', 'SRO-BEGUR', 'ELECTRONIC_CITY', 'HEBBAGODI', 'Hebbagodi', 1),
    ('KA', 'BRR', 'SRO-DEVANAHALLI', 'DEVANAHALLI', 'BOOVANAHALLI', 'Boovanahalli', 1),
    ('KA', 'MYS', 'SRO-MYSURU-NORTH', 'MYSURU', 'HINKAL', 'Hinkal', 1),
    ('KA', 'DKN', 'SRO-MANGALURU-NORTH', 'MANGALURU', 'KUDROLI', 'Kudroli', 1),
    ('KA', 'HDD', 'SRO-HUBBALLI-NORTH', 'HUBBALLI', 'GOKUL', 'Gokul', 1)
  ) AS v(state_code, district_code, sro_code, taluk_code, village_code, village_name, sort_order)
  JOIN master.registration_district d
    ON d.state_code = v.state_code
   AND d.district_code = v.district_code
  JOIN master.sub_registrar_office s
    ON s.district_id = d.id
   AND s.sro_code = v.sro_code
  JOIN master.taluk t
    ON t.sro_id = s.id
   AND t.taluk_code = v.taluk_code
ON CONFLICT (taluk_id, village_code) DO NOTHING;

INSERT INTO master.jurisdiction
  (state_code, district_code, district_name, taluk_code, taluk_name, village_code, village_name, sro_code, sro_name, revenue_division)
VALUES
  ('KA', 'BLR', 'Bengaluru Urban', 'SOUTH', 'Bengaluru South', 'BEGUR', 'Begur', 'SRO-BEGUR', 'Begur SRO', 'Bengaluru South'),
  ('KA', 'BLR', 'Bengaluru Urban', 'ELECTRONIC_CITY', 'Electronic City', 'HEBBAGODI', 'Hebbagodi', 'SRO-BEGUR', 'Begur SRO', 'Bengaluru South'),
  ('KA', 'BRR', 'Bengaluru Rural', 'DEVANAHALLI', 'Devanahalli', 'BOOVANAHALLI', 'Boovanahalli', 'SRO-DEVANAHALLI', 'Devanahalli SRO', 'Bengaluru North'),
  ('KA', 'MYS', 'Mysuru', 'MYSURU', 'Mysuru', 'HINKAL', 'Hinkal', 'SRO-MYSURU-NORTH', 'Mysuru North SRO', 'Mysuru'),
  ('KA', 'DKN', 'Dakshina Kannada', 'MANGALURU', 'Mangaluru', 'KUDROLI', 'Kudroli', 'SRO-MANGALURU-NORTH', 'Mangaluru North SRO', 'Mangaluru'),
  ('KA', 'HDD', 'Hubballi-Dharwad', 'HUBBALLI', 'Hubballi', 'GOKUL', 'Gokul', 'SRO-HUBBALLI-NORTH', 'Hubballi North SRO', 'Hubballi-Dharwad')
ON CONFLICT (state_code, district_code, taluk_code, village_code, sro_code) DO NOTHING;

INSERT INTO master.guideline_value
  (state_code, district_code, village_code, street_or_zone, land_type_code, rate_per_unit, unit, notification_reference, effective_from)
SELECT v.state_code, v.district_code, v.village_code, v.street_or_zone, v.land_type_code,
       v.rate_per_unit, v.unit, v.notification_reference, v.effective_from
  FROM (VALUES
    ('KA', 'BRR', 'BOOVANAHALLI', 'Devanahalli Growth Zone', 'RURAL', 4200, 'SQ_FT', 'KA-GV-2026-02', DATE '2026-04-01'),
    ('KA', 'BLR', 'HEBBAGODI', 'Electronic City Growth Corridor', 'NATHAM', 6400, 'SQ_FT', 'KA-GV-2026-02', DATE '2026-04-01'),
    ('KA', 'MYS', 'HINKAL', 'Mysuru Outer Ring', 'NATHAM', 5600, 'SQ_FT', 'KA-GV-2026-02', DATE '2026-04-01'),
    ('KA', 'DKN', 'KUDROLI', 'Mangaluru City Core', 'NATHAM', 6200, 'SQ_FT', 'KA-GV-2026-02', DATE '2026-04-01'),
    ('KA', 'HDD', 'GOKUL', 'Hubballi Residential Zone', 'NATHAM', 4500, 'SQ_FT', 'KA-GV-2026-02', DATE '2026-04-01')
  ) AS v(state_code, district_code, village_code, street_or_zone, land_type_code, rate_per_unit, unit, notification_reference, effective_from)
 WHERE NOT EXISTS (
       SELECT 1
         FROM master.guideline_value g
        WHERE g.state_code = v.state_code
          AND g.district_code = v.district_code
          AND g.village_code = v.village_code
          AND g.street_or_zone = v.street_or_zone
          AND COALESCE(g.land_type_code, '') = COALESCE(v.land_type_code, '')
          AND g.effective_from = v.effective_from
 );
