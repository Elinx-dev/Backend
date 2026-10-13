# SLATE local Hyperledger Fabric network

Local-only permissioned ledger for SLATE land tokenisation. No external chains, no data shared outside this PC.

| Item | Value |
|---|---|
| Organisations | `RegistrationMSP` (peer :7051), `RevenueMSP` (peer :8051), `SurveyMSP` (peer :9051) |
| Orderer | `orderer.slate.local` (Raft, :7050) |
| Channel | `slate` |
| Chaincode | `slateland` (Go, chaincode-as-a-service, container `slateland-cc`) |
| Endorsement | majority (2 of 3 organisations) |

Certificates are generated locally by `cryptogen` into `organizations/` (git-ignored). They are development-only identities.

## Start (Windows, Docker Desktop running)

PowerShell, from the Backend folder:

```
cd fabric
docker compose up -d --build
docker compose logs -f setup
```

Wait for `SLATE Fabric network ready: channel=slate chaincode=slateland` (first run 3-5 minutes: image pulls and chaincode build).
`docker ps` then shows `orderer.slate.local`, the 3 `peer0.*` containers and `slateland-cc`.

Daily use: `docker compose stop` / `docker compose start` (ledger data is kept in Docker volumes; a PC restart keeps it too).
Wipe the ledger: `docker compose down -v`, then delete the `organizations` and `channel-artifacts` folders.

## Start the Backend

The Backend reads the identities from `./fabric/organizations` (relative to the Backend folder), so run it with the
Backend folder as working directory (IntelliJ: Working directory = `$PROJECT_DIR$`). No chain environment variables are
needed. Optional overrides: `FABRIC_CRYPTO_PATH`, `FABRIC_CHANNEL`, `FABRIC_CHAINCODE`, `FABRIC_REGISTRATION_PEER`,
`FABRIC_REVENUE_PEER`, `FABRIC_SURVEY_PEER`, `SLATE_CHAIN_ENABLED=false` (turns the ledger off; entries are SKIPPED).

## What goes on the ledger

Only hashes and pseudonymous ids. Names, Aadhaar, PAN, extent and boundaries stay in PostgreSQL.

| SLATE action | Chaincode function | Signing org |
|---|---|---|
| First registration (mint) | `MintParcel` | Registration |
| Sale / transfer | `TransferParcel` (expected version, so a double sale is rejected) | Registration |
| Partition / subdivision | `MintParcel` (children) + `RetireParcel` (parent) | Registration / Survey |
| VAO, Tahsildar, Surveyor action on a subdivision | `Annotate` | Revenue / Survey |
| Mortgage, lease | `AddEncumbrance` / `ReleaseEncumbrance` | Registration |
| Court attachment | `AddEncumbrance` / `ReleaseEncumbrance` | Registration or Revenue |
| Deed registered | `RecordDeed` | Registration |

Reads: `ReadParcel`, `HasState`, `DeedOf`, `Encumbrances`, `History`, `CanTransfer`.
Transfer is blocked while any encumbrance is active. Fractional ownership is 10,000 share units per parcel.

## Test each node

```
docker compose --profile tools run --rm cli registration info      # height + block hash on that peer
docker compose --profile tools run --rm cli revenue info
docker compose --profile tools run --rm cli survey info
docker compose --profile tools run --rm cli survey chaincode        # chaincode committed + approvals
docker compose --profile tools run --rm cli revenue query ReadParcel SLATE-TN-00000001
docker compose --profile tools run --rm cli survey query History SLATE-TN-00000001
docker compose --profile tools run --rm cli revenue query CanTransfer SLATE-TN-00000001 1
docker compose --profile tools run --rm cli registration query DeedOf 000005/2026
```

All three peers must show the same height and block hash. Node-down test: `docker stop peer0.survey.slate.local`,
register a transaction (still MINED: 2 of 3 orgs endorse), `docker start peer0.survey.slate.local`, then `info` on
survey catches up to the same height. With 2 peers stopped, rows go FAILED and are retried once they are back.

## Check from SQL

```
SELECT id, function_name, chaincode_function, org_msp, status, block_number, tx_hash, error_message
  FROM chain.blockchain_transaction ORDER BY id DESC LIMIT 20;
```

`MINED` = committed in a block, `REJECTED` = blocked by a chaincode rule (not retried), `FAILED` = network down (retried),
`SKIPPED` = ledger off or identities missing when the row was created.

## Mock rule check

Court cases and land-revenue dues come from a mock (`SLATE_CONNECTORS_MODE=MOCK`). Every property is clean until flagged:

```
Invoke-RestMethod -Method Put -ContentType "application/json" -Uri "http://localhost:8081/mock/token-rules?propertyRef=TN-CHN-00000009" -Body '{"courtCase":true,"courtCaseRef":"OS 12/2026","taxDues":1500}'
Invoke-RestMethod -Method Delete -Uri "http://localhost:8081/mock/token-rules?propertyRef=TN-CHN-00000009"
```

A failed check blocks Register with HTTP 409. The `/mock/**` endpoints only answer calls from the same machine (localhost) and only exist while `SLATE_CONNECTORS_MODE=MOCK`.

## APIs (login required)

- `GET /api/tokens/{tokenRef}/ledger`: NFT on ledger vs SLATE, shares, metadata, encumbrances, ledger history, integrity
- `GET /api/tokens/{tokenRef}/transfer-check`
- `GET|POST /api/tokens/{tokenRef}/encumbrances`, `POST /api/tokens/{tokenRef}/encumbrances/{encRef}/release`
- `POST /api/tokens/{tokenRef}/demo/double-sale`
- `GET /api/tokens/metrics/summary`
