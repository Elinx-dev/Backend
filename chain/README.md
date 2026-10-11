# SLATE blockchain (local development)

Only hashes and references go on chain (token ref, state hash, deed SHA-256, document number). No names, Aadhaar, PAN, mobile or address.

## Run locally
1. `chain/besu-dev.sh` starts a single Besu dev node on `http://localhost:8545` (chain id 1337).
2. Start the backend with:
   - `CHAIN_ID=1337`
   - `CHAIN_SUBMITTER_KEY=<a Besu dev pre-funded test key>` (never commit a key; production keys belong in an HSM/KMS)
   - `--slate.chain.dev-deploy=true`: deploys `SlatePropertyToken` and records it in `cfg.chain_contract` (re-deploys if the recorded address has no code).

## How it works
- `ChainService.anchor` only queues a row in `chain.blockchain_transaction` (QUEUED). Register never waits for the chain.
- A worker (`slate.chain.worker-delay-ms`, default 5000) submits rows (SUBMITTED), polls receipts (MINED with block number and gas, or FAILED) and retries FAILED rows up to `slate.chain.max-attempts` (default 5). With no contract or key, rows are SKIPPED.
- Read-back: `hasState(tokenRef, stateHash)`, `latestState(tokenRef)` and `deedOf(documentNo)` on the contract.
- `GET /api/public/verify?documentNo=` (certificate QR target) compares the DB deed hash and the latest token state with the contract.
- `GET /api/lineage` adds `onchain_status` (VERIFIED / MISMATCH / NOT_ANCHORED / NOT_CONNECTED) per node.
- Reconciliation: nightly (`slate.chain.reconcile-cron`, default 01:30) and `POST /api/chain/reconcile` (state/central admin). Results are stored in `chain.reconciliation_run`.

## Contract
`contracts/SlatePropertyToken.sol` is append-only: a correction is a new state, never an edit. Rebuild it with:
`docker run --rm -v "$PWD/chain/contracts":/src ethereum/solc:0.8.24 --evm-version paris --optimize --bin --abi -o /src/out /src/SlatePropertyToken.sol --overwrite`,
then copy `out/*.abi|bin` to `src/main/resources/chain/`.
