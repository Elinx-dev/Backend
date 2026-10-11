-- A property can span several survey numbers, each with its own optional ULPIN,
-- sub-division and extent. core.property keeps the first record in its
-- ulpin/survey/extent columns for the readers that still use them.
CREATE TABLE IF NOT EXISTS core.property_survey (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  property_id     BIGINT NOT NULL REFERENCES core.property(id),
  seq             INT NOT NULL,
  ulpin           TEXT,
  survey_no       TEXT NOT NULL,
  subdivision_no  TEXT,
  extent_value    NUMERIC(18,4) NOT NULL CHECK (extent_value > 0),
  extent_unit     TEXT NOT NULL,
  UNIQUE (property_id, seq)
);
CREATE INDEX IF NOT EXISTS ix_property_survey_ulpin ON core.property_survey (ulpin) WHERE ulpin IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_property_survey_no ON core.property_survey (survey_no, subdivision_no);

INSERT INTO core.property_survey (property_id, seq, ulpin, survey_no, subdivision_no, extent_value, extent_unit)
SELECT p.id, 1, p.ulpin, p.survey_no, p.subdivision_no, p.extent_value, p.extent_unit
  FROM core.property p
 WHERE p.extent_value > 0
   AND NOT EXISTS (SELECT 1 FROM core.property_survey s WHERE s.property_id = p.id);
