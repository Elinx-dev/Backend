-- Soft-delete witnesses so edit history is retained while only active rows are returned.
ALTER TABLE core.witness
  ADD COLUMN is_active CHAR(1) NOT NULL DEFAULT 'Y'
  CONSTRAINT ck_witness_is_active CHECK (is_active IN ('Y', 'N'));

ALTER TABLE core.witness
  DROP CONSTRAINT witness_transaction_id_seq_key;

CREATE UNIQUE INDEX ux_witness_active_transaction_seq
  ON core.witness (transaction_id, seq)
  WHERE is_active = 'Y';
