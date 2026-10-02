ALTER TABLE core.chain_of_title
  ADD COLUMN IF NOT EXISTS property_value NUMERIC(18,2),
  ADD COLUMN IF NOT EXISTS registration_fee NUMERIC(18,2),
  ADD COLUMN IF NOT EXISTS registering_office TEXT;

CREATE TABLE IF NOT EXISTS core.chain_of_title_owner (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  chain_of_title_id  BIGINT NOT NULL REFERENCES core.chain_of_title(id) ON DELETE CASCADE,
  seq                INT NOT NULL,
  owner_name         TEXT NOT NULL,
  address            TEXT NOT NULL,
  aadhaar_number     TEXT NOT NULL,
  pan                TEXT NOT NULL,
  share_pct          NUMERIC(7,4) NOT NULL CHECK (share_pct >= 0 AND share_pct <= 100),
  UNIQUE (chain_of_title_id, seq)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON core.chain_of_title_owner TO slate_app;
GRANT USAGE, SELECT ON SEQUENCE core.chain_of_title_owner_id_seq TO slate_app;
