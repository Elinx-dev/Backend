-- KARAPAKKAM holds the mock EC/Revenue fixtures used for test data; give the demo VAO and
-- Surveyor village-level access so registered KARAPAKKAM records reach their queues.
INSERT INTO sec.user_jurisdiction (user_id, state_code, district_code, taluk_code, village_code, sro_code)
SELECT u.id, 'TN', 'CHN', 'SHOL', 'KARAPAKKAM', NULL
  FROM sec.user u
 WHERE u.username IN ('vao.perungudi', 'surveyor.sholinganallur')
ON CONFLICT DO NOTHING;
