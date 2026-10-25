-- Ledger queue rows name the Fabric chaincode they were submitted to, not the retired PROPERTY_TOKEN label.
UPDATE chain.blockchain_transaction SET contract_code = 'slateland' WHERE contract_code = 'PROPERTY_TOKEN';
