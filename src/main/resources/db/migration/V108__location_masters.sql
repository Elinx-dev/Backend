-- Location hierarchy used by property entry dropdowns:
--   state -> registration district -> sub-registrar office -> taluk -> revenue village
-- Each level references its parent by id; codes are unique within the parent.

CREATE TABLE master.registration_district (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code     CHAR(2) NOT NULL REFERENCES cfg.state(state_code),
  district_code  TEXT NOT NULL,
  district_name  TEXT NOT NULL,
  sort_order     INT NOT NULL DEFAULT 0,
  active         BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (state_code, district_code)
);

CREATE TABLE master.sub_registrar_office (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  district_id  BIGINT NOT NULL REFERENCES master.registration_district(id),
  sro_code     TEXT NOT NULL,
  sro_name     TEXT NOT NULL,
  sort_order   INT NOT NULL DEFAULT 0,
  active       BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (district_id, sro_code)
);
CREATE INDEX ix_sro_district ON master.sub_registrar_office (district_id);

CREATE TABLE master.taluk (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  sro_id      BIGINT NOT NULL REFERENCES master.sub_registrar_office(id),
  taluk_code  TEXT NOT NULL,
  taluk_name  TEXT NOT NULL,
  sort_order  INT NOT NULL DEFAULT 0,
  active      BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (sro_id, taluk_code)
);
CREATE INDEX ix_taluk_sro ON master.taluk (sro_id);

CREATE TABLE master.revenue_village (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  taluk_id      BIGINT NOT NULL REFERENCES master.taluk(id),
  village_code  TEXT NOT NULL,
  village_name  TEXT NOT NULL,
  sort_order    INT NOT NULL DEFAULT 0,
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (taluk_id, village_code)
);
CREATE INDEX ix_village_taluk ON master.revenue_village (taluk_id);

GRANT SELECT, INSERT, UPDATE ON master.registration_district, master.sub_registrar_office,
  master.taluk, master.revenue_village TO slate_app;
GRANT SELECT ON master.registration_district, master.sub_registrar_office,
  master.taluk, master.revenue_village TO slate_readonly;
GRANT USAGE, SELECT ON SEQUENCE master.registration_district_id_seq, master.sub_registrar_office_id_seq,
  master.taluk_id_seq, master.revenue_village_id_seq TO slate_app;
