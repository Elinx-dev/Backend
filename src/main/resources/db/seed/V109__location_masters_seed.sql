-- Location master seed: Tamil Nadu (plus the existing Karnataka demo office).
-- Codes used by the demo users and properties in V101/V102 (CHN, SRO-ADYAR, SRO-SHOL,
-- SHOL, PERUNGUDI, ... ; KAN, SRO-KELAM, CHENGALPATTU, KELAMBAKKAM; BLR, SRO-BEGUR)
-- are kept so existing records resolve against the dropdowns.

-- 1. Registration districts (mapped to state)
INSERT INTO master.registration_district (state_code, district_code, district_name, sort_order) VALUES
  ('TN','CHN','Chennai',1),
  ('TN','CGL','Chengalpattu',2),
  ('TN','KAN','Kancheepuram',3),
  ('TN','TVL','Tiruvallur',4),
  ('TN','CBE','Coimbatore',5),
  ('TN','MDU','Madurai',6),
  ('TN','TRY','Tiruchirappalli',7),
  ('TN','SLM','Salem',8),
  ('KA','BLR','Bengaluru Urban',1)
ON CONFLICT (state_code, district_code) DO NOTHING;

-- 2. Sub-Registrar Offices (mapped to district)
INSERT INTO master.sub_registrar_office (district_id, sro_code, sro_name, sort_order)
SELECT d.id, v.sro_code, v.sro_name, v.sort_order
FROM (VALUES
  ('TN','CHN','SRO-ADYAR','Adyar SRO',1),
  ('TN','CHN','SRO-SHOL','Sholinganallur SRO',2),
  ('TN','CHN','SRO-MYLAPORE','Mylapore SRO',3),
  ('TN','CHN','SRO-TNAGAR','T. Nagar SRO',4),
  ('TN','CHN','SRO-VELACHERY','Velachery SRO',5),
  ('TN','CGL','SRO-TAMBARAM','Tambaram SRO',1),
  ('TN','CGL','SRO-THIRUPORUR','Thiruporur SRO',2),
  ('TN','KAN','SRO-KELAM','Kelambakkam SRO',1),
  ('TN','KAN','SRO-KANCHI','Kancheepuram SRO',2),
  ('TN','KAN','SRO-SRIPERUMBUDUR','Sriperumbudur SRO',3),
  ('TN','TVL','SRO-AVADI','Avadi SRO',1),
  ('TN','TVL','SRO-PONNERI','Ponneri SRO',2),
  ('TN','CBE','SRO-GANDHIPURAM','Gandhipuram SRO',1),
  ('TN','CBE','SRO-SINGANALLUR','Singanallur SRO',2),
  ('TN','MDU','SRO-TALLAKULAM','Tallakulam SRO',1),
  ('TN','MDU','SRO-THIRUPARANKUNDRAM','Thiruparankundram SRO',2),
  ('TN','TRY','SRO-SRIRANGAM','Srirangam SRO',1),
  ('TN','SLM','SRO-SURAMANGALAM','Suramangalam SRO',1),
  ('KA','BLR','SRO-BEGUR','Begur SRO',1)
) AS v(state_code, district_code, sro_code, sro_name, sort_order)
JOIN master.registration_district d
  ON d.state_code = v.state_code AND d.district_code = v.district_code
ON CONFLICT (district_id, sro_code) DO NOTHING;

-- 3. Taluks (mapped to SRO)
INSERT INTO master.taluk (sro_id, taluk_code, taluk_name, sort_order)
SELECT s.id, v.taluk_code, v.taluk_name, v.sort_order
FROM (VALUES
  ('TN','CHN','SRO-ADYAR','SHOL','Sholinganallur',1),
  ('TN','CHN','SRO-ADYAR','VELACHERY','Velachery',2),
  ('TN','CHN','SRO-SHOL','SHOL','Sholinganallur',1),
  ('TN','CHN','SRO-MYLAPORE','MYLAPORE','Mylapore',1),
  ('TN','CHN','SRO-TNAGAR','MAMBALAM','Mambalam',1),
  ('TN','CHN','SRO-TNAGAR','GUINDY','Guindy',2),
  ('TN','CHN','SRO-VELACHERY','VELACHERY','Velachery',1),
  ('TN','CGL','SRO-TAMBARAM','TAMBARAM','Tambaram',1),
  ('TN','CGL','SRO-THIRUPORUR','THIRUPORUR','Thiruporur',1),
  ('TN','KAN','SRO-KELAM','CHENGALPATTU','Chengalpattu',1),
  ('TN','KAN','SRO-KANCHI','KANCHIPURAM','Kancheepuram',1),
  ('TN','KAN','SRO-SRIPERUMBUDUR','SRIPERUMBUDUR','Sriperumbudur',1),
  ('TN','TVL','SRO-AVADI','AVADI','Avadi',1),
  ('TN','TVL','SRO-PONNERI','PONNERI','Ponneri',1),
  ('TN','CBE','SRO-GANDHIPURAM','CBE_NORTH','Coimbatore North',1),
  ('TN','CBE','SRO-SINGANALLUR','CBE_SOUTH','Coimbatore South',1),
  ('TN','MDU','SRO-TALLAKULAM','MDU_NORTH','Madurai North',1),
  ('TN','MDU','SRO-THIRUPARANKUNDRAM','THIRUPARANKUNDRAM','Thiruparankundram',1),
  ('TN','TRY','SRO-SRIRANGAM','SRIRANGAM','Srirangam',1),
  ('TN','SLM','SRO-SURAMANGALAM','SALEM','Salem',1),
  ('KA','BLR','SRO-BEGUR','SOUTH','Bengaluru South',1)
) AS v(state_code, district_code, sro_code, taluk_code, taluk_name, sort_order)
JOIN master.registration_district d
  ON d.state_code = v.state_code AND d.district_code = v.district_code
JOIN master.sub_registrar_office s
  ON s.district_id = d.id AND s.sro_code = v.sro_code
ON CONFLICT (sro_id, taluk_code) DO NOTHING;

-- 4. Revenue villages (mapped to taluk under an SRO)
INSERT INTO master.revenue_village (taluk_id, village_code, village_name, sort_order)
SELECT t.id, v.village_code, v.village_name, v.sort_order
FROM (VALUES
  ('TN','CHN','SRO-ADYAR','SHOL','PERUNGUDI','Perungudi',1),
  ('TN','CHN','SRO-ADYAR','SHOL','THORAIPAKKAM','Thoraipakkam',2),
  ('TN','CHN','SRO-ADYAR','SHOL','KOTTIVAKKAM','Kottivakkam',3),
  ('TN','CHN','SRO-ADYAR','SHOL','PALAVAKKAM','Palavakkam',4),
  ('TN','CHN','SRO-ADYAR','VELACHERY','TARAMANI','Taramani',1),
  ('TN','CHN','SRO-ADYAR','VELACHERY','KANAGAM','Kanagam',2),
  ('TN','CHN','SRO-SHOL','SHOL','SHOLINGANALLUR','Sholinganallur',1),
  ('TN','CHN','SRO-SHOL','SHOL','KARAPAKKAM','Karapakkam',2),
  ('TN','CHN','SRO-SHOL','SHOL','SEMMENCHERRY','Semmencherry',3),
  ('TN','CHN','SRO-SHOL','SHOL','INJAMBAKKAM','Injambakkam',4),
  ('TN','CHN','SRO-SHOL','SHOL','UTHANDI','Uthandi',5),
  ('TN','CHN','SRO-MYLAPORE','MYLAPORE','MYLAPORE','Mylapore',1),
  ('TN','CHN','SRO-MYLAPORE','MYLAPORE','MANDAVELI','Mandaveli',2),
  ('TN','CHN','SRO-MYLAPORE','MYLAPORE','ALWARPET','Alwarpet',3),
  ('TN','CHN','SRO-TNAGAR','MAMBALAM','MAMBALAM','Mambalam',1),
  ('TN','CHN','SRO-TNAGAR','MAMBALAM','KODAMBAKKAM','Kodambakkam',2),
  ('TN','CHN','SRO-TNAGAR','GUINDY','GUINDY','Guindy',1),
  ('TN','CHN','SRO-TNAGAR','GUINDY','SAIDAPET','Saidapet',2),
  ('TN','CHN','SRO-VELACHERY','VELACHERY','VELACHERY','Velachery',1),
  ('TN','CHN','SRO-VELACHERY','VELACHERY','PALLIKARANAI','Pallikaranai',2),
  ('TN','CGL','SRO-TAMBARAM','TAMBARAM','TAMBARAM','Tambaram',1),
  ('TN','CGL','SRO-TAMBARAM','TAMBARAM','SELAIYUR','Selaiyur',2),
  ('TN','CGL','SRO-TAMBARAM','TAMBARAM','CHITLAPAKKAM','Chitlapakkam',3),
  ('TN','CGL','SRO-THIRUPORUR','THIRUPORUR','NAVALUR','Navalur',1),
  ('TN','CGL','SRO-THIRUPORUR','THIRUPORUR','SIRUSERI','Siruseri',2),
  ('TN','CGL','SRO-THIRUPORUR','THIRUPORUR','PADUR','Padur',3),
  ('TN','KAN','SRO-KELAM','CHENGALPATTU','KELAMBAKKAM','Kelambakkam',1),
  ('TN','KAN','SRO-KELAM','CHENGALPATTU','PUDUPAKKAM','Pudupakkam',2),
  ('TN','KAN','SRO-KANCHI','KANCHIPURAM','KANCHIPURAM','Kancheepuram',1),
  ('TN','KAN','SRO-KANCHI','KANCHIPURAM','ENATHUR','Enathur',2),
  ('TN','KAN','SRO-KANCHI','KANCHIPURAM','ORIKKAI','Orikkai',3),
  ('TN','KAN','SRO-SRIPERUMBUDUR','SRIPERUMBUDUR','SRIPERUMBUDUR','Sriperumbudur',1),
  ('TN','KAN','SRO-SRIPERUMBUDUR','SRIPERUMBUDUR','IRUNGATTUKOTTAI','Irungattukottai',2),
  ('TN','KAN','SRO-SRIPERUMBUDUR','SRIPERUMBUDUR','ORAGADAM','Oragadam',3),
  ('TN','TVL','SRO-AVADI','AVADI','AVADI','Avadi',1),
  ('TN','TVL','SRO-AVADI','AVADI','PATTABIRAM','Pattabiram',2),
  ('TN','TVL','SRO-PONNERI','PONNERI','PONNERI','Ponneri',1),
  ('TN','TVL','SRO-PONNERI','PONNERI','MINJUR','Minjur',2),
  ('TN','CBE','SRO-GANDHIPURAM','CBE_NORTH','GANAPATHY','Ganapathy',1),
  ('TN','CBE','SRO-GANDHIPURAM','CBE_NORTH','SARAVANAMPATTI','Saravanampatti',2),
  ('TN','CBE','SRO-GANDHIPURAM','CBE_NORTH','VILANKURICHI','Vilankurichi',3),
  ('TN','CBE','SRO-SINGANALLUR','CBE_SOUTH','SINGANALLUR','Singanallur',1),
  ('TN','CBE','SRO-SINGANALLUR','CBE_SOUTH','ONDIPUDUR','Ondipudur',2),
  ('TN','MDU','SRO-TALLAKULAM','MDU_NORTH','TALLAKULAM','Tallakulam',1),
  ('TN','MDU','SRO-TALLAKULAM','MDU_NORTH','KOODAL_NAGAR','Koodal Nagar',2),
  ('TN','MDU','SRO-THIRUPARANKUNDRAM','THIRUPARANKUNDRAM','THIRUPARANKUNDRAM','Thiruparankundram',1),
  ('TN','MDU','SRO-THIRUPARANKUNDRAM','THIRUPARANKUNDRAM','THIRUNAGAR','Thirunagar',2),
  ('TN','TRY','SRO-SRIRANGAM','SRIRANGAM','SRIRANGAM','Srirangam',1),
  ('TN','TRY','SRO-SRIRANGAM','SRIRANGAM','THIRUVANAIKOIL','Thiruvanaikoil',2),
  ('TN','TRY','SRO-SRIRANGAM','SRIRANGAM','MANACHANALLUR','Manachanallur',3),
  ('TN','SLM','SRO-SURAMANGALAM','SALEM','SURAMANGALAM','Suramangalam',1),
  ('TN','SLM','SRO-SURAMANGALAM','SALEM','HASTHAMPATTI','Hasthampatti',2),
  ('TN','SLM','SRO-SURAMANGALAM','SALEM','KONDALAMPATTI','Kondalampatti',3),
  ('KA','BLR','SRO-BEGUR','SOUTH','BEGUR','Begur',1)
) AS v(state_code, district_code, sro_code, taluk_code, village_code, village_name, sort_order)
JOIN master.registration_district d
  ON d.state_code = v.state_code AND d.district_code = v.district_code
JOIN master.sub_registrar_office s
  ON s.district_id = d.id AND s.sro_code = v.sro_code
JOIN master.taluk t
  ON t.sro_id = s.id AND t.taluk_code = v.taluk_code
ON CONFLICT (taluk_id, village_code) DO NOTHING;
