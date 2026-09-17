-- S4: demo users and test properties for the local runbook.
-- Every demo account uses the same password, Slate@123, and the demo login OTP 123456.
-- These credentials exist only in this seed file, which is never loaded outside
-- a local/demo profile. See docs/SLATE_Local_Setup_Runbook.md.

INSERT INTO sec.user (state_code, username, full_name, email, mobile, employee_code, designation, department, password_hash) VALUES
  ('TN','ro.adyar','R. Anandhi','ro.adyar@tn.demo.slate','9000000001','TN-REG-0001','Sub-Registrar','REGISTRATION', crypt('Slate@123', gen_salt('bf'))),
  ('TN','ro.sholinganallur','S. Karthik','ro.shol@tn.demo.slate','9000000002','TN-REG-0002','Sub-Registrar','REGISTRATION', crypt('Slate@123', gen_salt('bf'))),
  ('TN','surveyor.sholinganallur','M. Prakash','surveyor.shol@tn.demo.slate','9000000003','TN-SUR-0001','Surveyor','SURVEY', crypt('Slate@123', gen_salt('bf'))),
  ('TN','vao.perungudi','K. Lakshmi','vao.perungudi@tn.demo.slate','9000000004','TN-REV-0001','Village Administrative Officer','REVENUE', crypt('Slate@123', gen_salt('bf'))),
  ('TN','tahsildar.sholinganallur','V. Ramesh','tahsildar.shol@tn.demo.slate','9000000005','TN-REV-0002','Tahsildar','REVENUE', crypt('Slate@123', gen_salt('bf'))),
  ('TN','admin.state','A. Divya','admin.tn@demo.slate','9000000006','TN-ADM-0001','State Administrator','ADMIN', crypt('Slate@123', gen_salt('bf'))),
  ('TN','viewer.public','Public Viewer','viewer@demo.slate','9000000007',NULL,'Public','PUBLIC', crypt('Slate@123', gen_salt('bf'))),
  ('KA','ro.begur','B. Suresh','ro.begur@ka.demo.slate','9000000008','KA-REG-0001','Sub-Registrar','REGISTRATION', crypt('Slate@123', gen_salt('bf'))),
  ('KA','admin.state.ka','N. Meera','admin.ka@demo.slate','9000000009','KA-ADM-0001','State Administrator','ADMIN', crypt('Slate@123', gen_salt('bf')));

-- The public viewer has no MFA: it reads only the common public view.
UPDATE sec.user SET mfa_required = FALSE WHERE username = 'viewer.public';

INSERT INTO sec.user_role (user_id, role_id)
SELECT u.id, r.id
FROM sec.user u
JOIN (VALUES
  ('ro.adyar','REGISTRATION_OFFICER'),
  ('ro.sholinganallur','REGISTRATION_OFFICER'),
  ('surveyor.sholinganallur','SURVEYOR'),
  ('vao.perungudi','VAO'),
  ('tahsildar.sholinganallur','TAHSILDAR'),
  ('admin.state','STATE_ADMIN'),
  ('viewer.public','PUBLIC_VIEWER'),
  ('ro.begur','REGISTRATION_OFFICER'),
  ('admin.state.ka','STATE_ADMIN')
) AS m(username, role) ON m.username = u.username
JOIN sec.role r ON r.code = m.role;

INSERT INTO sec.user_jurisdiction (user_id, state_code, district_code, taluk_code, village_code, sro_code)
SELECT u.id, j.state_code, j.district_code, j.taluk_code, j.village_code, j.sro_code
FROM sec.user u JOIN (VALUES
  ('ro.adyar','TN','CHN','SHOL',NULL,'SRO-ADYAR'),
  ('ro.sholinganallur','TN','CHN','SHOL',NULL,'SRO-SHOL'),
  ('surveyor.sholinganallur','TN','CHN','SHOL',NULL,NULL),
  ('vao.perungudi','TN','CHN','SHOL','PERUNGUDI',NULL),
  ('tahsildar.sholinganallur','TN','CHN','SHOL',NULL,NULL),
  ('admin.state','TN',NULL,NULL,NULL,NULL),
  ('viewer.public','TN',NULL,NULL,NULL,NULL),
  ('ro.begur','KA','BLR','SOUTH',NULL,'SRO-BEGUR'),
  ('admin.state.ka','KA',NULL,NULL,NULL,NULL)
) AS j(username, state_code, district_code, taluk_code, village_code, sro_code)
  ON j.username = u.username;

-- Test properties. Each one is wired to a deterministic mock EC / Revenue fixture
-- so every rule-check outcome can be demonstrated: see docs/SLATE_Mock_External_APIs.md.
INSERT INTO core.property
  (state_code, property_ref, ulpin, property_type_code, nature_of_title_code, land_type_code,
   classification_code, extent_value, extent_unit, survey_no, subdivision_no,
   district_code, taluk_code, village_code, sro_code, street, door_no,
   boundary_north, boundary_south, boundary_east, boundary_west,
   guideline_value, guideline_value_reference, is_apartment_unit) VALUES
  ('TN','TN-CHN-00000001','TN33CHN0000112A2','HOUSE_SITE','PURCHASE','NATHAM','HOUSE_SITE',2400,'SQ_FT','112','2A',
   'CHN','SHOL','PERUNGUDI','SRO-ADYAR','1st Main Road','14',
   'Survey 112/2B','Road','Survey 113/1','Survey 112/1', 7500,'TN-GV-2026-01',false),
  ('TN','TN-CHN-00000002',NULL,'HOUSE_SITE','PURCHASE','NATHAM','HOUSE_SITE',4800,'SQ_FT','45','3',
   'CHN','SHOL','THORAIPAKKAM','SRO-ADYAR','Lake View Street','9',
   'Survey 45/2','Survey 45/4','Canal','Road', 6800,'TN-GV-2026-01',false),
  ('TN','TN-CHN-00000003',NULL,'LAND','INHERITANCE','NATHAM','HOUSE_SITE',10000,'SQ_FT','78','1',
   'CHN','SHOL','SHOLINGANALLUR','SRO-SHOL','Sholinganallur Main Road','22',
   'Survey 78/2','Road','Survey 79','Survey 77', 8200,'TN-GV-2026-01',false),
  ('TN','TN-CHN-00000004','TN33CHN0000023A1','HOUSE_SITE','INHERITANCE','NATHAM','HOUSE_SITE',3200,'SQ_FT','23','1',
   'CHN','SHOL','PERUNGUDI','SRO-ADYAR','Temple Street','3',
   'Survey 23/2','Road','Survey 24','Survey 22', 7500,'TN-GV-2026-01',false),
  ('TN','TN-KAN-00000005',NULL,'AGRICULTURAL','INHERITANCE','RURAL','DRY',6000,'SQ_FT','9','2B',
   'KAN','CHENGALPATTU','KELAMBAKKAM','SRO-KELAM','ECR Service Road',NULL,
   'Survey 9/2A','Survey 9/3','Village Road','Canal', 2400,'TN-GV-2026-01',false),
  ('TN','TN-CHN-00000006',NULL,'HOUSE_SITE','PARTITION','NATHAM','HOUSE_SITE',5000,'SQ_FT','200','1',
   'CHN','SHOL','PERUNGUDI','SRO-ADYAR','Bakthavatchalam Street','7',
   'Survey 200/2','Road','Survey 201','Survey 199', 7500,'TN-GV-2026-01',false),
  ('TN','TN-CHN-00000007',NULL,'LAND','INHERITANCE','NATHAM','HOUSE_SITE',12000,'SQ_FT','90','4',
   'CHN','SHOL','SHOLINGANALLUR','SRO-SHOL','Perumbakkam Road','41',
   'Survey 90/3','Survey 90/5','Road','Survey 89', 8200,'TN-GV-2026-01',false),
  ('TN','TN-CHN-00000008',NULL,'APARTMENT_UNIT','PURCHASE','NATHAM','HOUSE_SITE',1180,'SQ_FT','112','2A',
   'CHN','SHOL','PERUNGUDI','SRO-ADYAR','1st Main Road','14/3B',
   'Common Area','Common Area','Unit 3C','Unit 3A', 7500,'TN-GV-2026-01',true),
  ('KA','KA-BLR-00000001',NULL,'HOUSE_SITE','PURCHASE','NATHAM','HOUSE_SITE',2000,'SQ_FT','54','7',
   'BLR','SOUTH','BEGUR','SRO-BEGUR','Begur Main Road','12',
   'Survey 54/6','Survey 54/8','Road','Lake', 6100,'KA-GV-2026-01',false);

INSERT INTO core.property_apartment_detail
  (property_id, parent_land_property_id, flat_no, block_tower, floor, builtup_area, area_unit, uds_fraction, parent_survey_no, parent_subdivision_no)
SELECT u.id, p.id, '3B', 'Tower A', '3', 1180, 'SQ_FT', 0.041667, '112', '2A'
FROM core.property u, core.property p
WHERE u.property_ref = 'TN-CHN-00000008' AND p.property_ref = 'TN-CHN-00000001';

INSERT INTO core.property_owner (property_id, owner_name, share_pct, source, effective_from)
SELECT p.id, o.owner_name, o.share_pct, 'PROPERTY_ENTRY', '2026-01-01'
FROM core.property p JOIN (VALUES
  ('TN-CHN-00000001','Raman Krishnan',100.0000),
  ('TN-CHN-00000002','Suseela Devi',60.0000),
  ('TN-CHN-00000002','Gopal Devi',40.0000),
  ('TN-CHN-00000003','Meenakshi Sundaram',100.0000),
  ('TN-CHN-00000004','Thangaraj Pillai',100.0000),
  ('TN-KAN-00000005','K. Raman',100.0000),
  ('TN-CHN-00000006','Selvi Murugan',50.0000),
  ('TN-CHN-00000006','Arun Murugan',50.0000),
  ('TN-CHN-00000007','Bhaskar Rao',34.0000),
  ('TN-CHN-00000007','Chitra Rao',33.0000),
  ('TN-CHN-00000007','Deepak Rao',33.0000),
  ('TN-CHN-00000008','Nithya Balaji',100.0000),
  ('KA-BLR-00000001','Shivanna Gowda',100.0000)
) AS o(property_ref, owner_name, share_pct) ON o.property_ref = p.property_ref;

INSERT INTO core.chain_of_title
  (property_id, seq, executor_name, claimant_name, transaction_date, nature_of_transaction, reference_no, survey_no)
SELECT p.id, c.seq, c.executor, c.claimant, c.txn_date::date, c.nature, c.ref, p.survey_no
FROM core.property p JOIN (VALUES
  ('TN-CHN-00000001',1,'Govt. Assignment','Raman Krishnan','2009-06-14','Sale','1889/2009'),
  ('TN-CHN-00000002',1,'Perumal Devi','Suseela Devi','2012-02-20','Settlement','442/2012'),
  ('TN-CHN-00000003',1,'Sundaram','Meenakshi Sundaram','2015-11-03','Inheritance','—'),
  ('TN-CHN-00000007',1,'Rao Family','Bhaskar Rao, Chitra Rao, Deepak Rao','2011-08-09','Partition','770/2011')
) AS c(property_ref, seq, executor, claimant, txn_date, nature, ref) ON c.property_ref = p.property_ref;

-- Verified survey lineage used by both engines' fallback path.
INSERT INTO rules.survey_lineage
  (state_code, district_code, taluk_code, revenue_village, from_survey_no, from_subdivision,
   to_survey_no, to_subdivision, effective_date, reason, source_reference, verified_at, verified_by)
VALUES
  ('TN','CHN','SHOL','PERUNGUDI','112','2','112','2A','2019-04-01','SPLIT','TN-SURVEY-API/RESUBDIV/2019/4471', now(), 'SURVEY_DEPT'),
  ('TN','CHN','SHOL','SHOLINGANALLUR','78','0','78','1','2018-07-15','RENUMBER','TN-SURVEY-API/RENUM/2018/1123', now(), 'SURVEY_DEPT');

-- Revenue's own current record for each demo property. Deliberately separate from
-- core.property_owner: Registered Ownership and Revenue Ownership are distinct facts.
INSERT INTO revenue.current_state
  (property_id, revenue_record_ref, revenue_survey_no, revenue_subdivision_no, land_context,
   owners, extent_value, extent_unit, classification, record_status, source_reference)
SELECT p.id, r.rec_ref, p.survey_no, p.subdivision_no, p.land_type_code,
       r.owners::jsonb, p.extent_value, p.extent_unit, p.classification_code, 'ACTIVE',
       'MOCK-REVENUE-API/' || p.property_ref
FROM core.property p JOIN (VALUES
  ('TN-CHN-00000001','PATTA-PER-1187','[{"name":"Raman Krishnan","relationType":null,"relatedPersonName":null}]'),
  ('TN-CHN-00000002','PATTA-THO-2290','[{"name":"Suseela Devi","relationType":null,"relatedPersonName":null},{"name":"Gopal Devi","relationType":"husband of","relatedPersonName":"Suseela Devi"}]'),
  ('TN-CHN-00000003','PATTA-SHO-3311','[{"name":"Meenakshi Sundaram","relationType":null,"relatedPersonName":null}]'),
  ('TN-CHN-00000004','PATTA-PER-1450','[{"name":"Thangaraj Pillai","relationType":null,"relatedPersonName":null}]'),
  ('TN-KAN-00000005','PATTA-KEL-0912','[{"name":"Raman K.","relationType":null,"relatedPersonName":null}]'),
  ('TN-CHN-00000007','PATTA-SHO-4400','[{"name":"Bhaskar Rao","relationType":null,"relatedPersonName":null},{"name":"Chitra Rao","relationType":"wife of","relatedPersonName":"Bhaskar Rao"},{"name":"Deepak Rao","relationType":"son of","relatedPersonName":"Bhaskar Rao"}]'),
  ('TN-CHN-00000008','PATTA-PER-1187','[{"name":"Nithya Balaji","relationType":null,"relatedPersonName":null}]'),
  ('KA-BLR-00000001','RTC-BEG-7781','[{"name":"Shivanna Gowda","relationType":null,"relatedPersonName":null}]')
) AS r(property_ref, rec_ref, owners) ON r.property_ref = p.property_ref;
