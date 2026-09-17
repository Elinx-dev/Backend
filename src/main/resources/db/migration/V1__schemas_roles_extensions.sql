-- SLATE physical layout: schemas, extensions, application roles.
-- Reference: SLATE Database Design §2.1

CREATE SCHEMA IF NOT EXISTS cfg;      -- configuration: states, workflows, fields, rules, chain bindings
CREATE SCHEMA IF NOT EXISTS master;   -- reference/master data (fee master, deed types, jurisdiction)
CREATE SCHEMA IF NOT EXISTS core;     -- property, transactions, parties, consent, documents, fees
CREATE SCHEMA IF NOT EXISTS rules;    -- rule check engines: requests, results, EC, revenue snapshots
CREATE SCHEMA IF NOT EXISTS survey;   -- site visits, submissions, measurements, resulting parcels
CREATE SCHEMA IF NOT EXISTS revenue;  -- VAO / Tahsildar mutation lifecycle
CREATE SCHEMA IF NOT EXISTS chain;    -- off-chain mirror of the ledger + keys + merkle batches
CREATE SCHEMA IF NOT EXISTS sec;      -- users, roles, permissions, append-only audit
CREATE SCHEMA IF NOT EXISTS rpt;      -- read models for portals and the common public view

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS btree_gin;

-- The application role. It is deliberately not the owner: it gets no DDL, and
-- UPDATE/DELETE are revoked from the append-only tables in V11.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'slate_app') THEN
    CREATE ROLE slate_app LOGIN PASSWORD 'slate_app';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'slate_readonly') THEN
    CREATE ROLE slate_readonly LOGIN PASSWORD 'slate_readonly';
  END IF;
END
$$;

-- Raised by the BEFORE UPDATE OR DELETE triggers on every append-only table.
CREATE OR REPLACE FUNCTION sec.raise_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'Table %.% is append-only; % is not permitted',
    TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;
