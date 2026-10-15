-- Removes tables that no application code reads or writes: the retired Besu/Merkle
-- anchoring design (replaced by the Fabric ledger in V132) and config tables that were
-- never wired up. All were empty apart from seed rows for the old Besu network.
ALTER TABLE core.document DROP CONSTRAINT IF EXISTS fk_doc_batch;
ALTER TABLE core.document DROP COLUMN IF EXISTS merkle_batch_id;

DROP TABLE IF EXISTS
  chain.merkle_leaf,
  chain.merkle_batch,
  chain.blockchain_event,
  chain.endorsement,
  chain.indexer_checkpoint,
  chain.signing_key,
  cfg.chain_contract,
  cfg.chain_endorser,
  cfg.chain_validator,
  cfg.chain_network_config,
  cfg.config_change_log,
  cfg.config_version,
  cfg.connector_config,
  core.chain_of_title_owner,
  survey.attachment;
