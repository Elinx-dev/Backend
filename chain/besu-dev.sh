#!/usr/bin/env bash
# Local single-node Besu dev chain for SLATE (development only, not a production validator setup).
# The dev network pre-funds well-known test accounts; pass one of their keys to the backend as
# CHAIN_SUBMITTER_KEY together with --slate.chain.dev-deploy=true to deploy SlatePropertyToken on startup.
set -euo pipefail
NAME=${BESU_CONTAINER:-slate-besu}
if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
  docker start "$NAME" >/dev/null
else
  docker run -d --name "$NAME" -p 8545:8545 hyperledger/besu:24.12.2 \
    --network=dev --miner-enabled --miner-coinbase=0xfe3b557e8fb62b89f4916b721be55ceb828dbd73 \
    --rpc-http-enabled --rpc-http-api=ETH,NET,WEB3 --host-allowlist='*' \
    --rpc-http-cors-origins='*' --min-gas-price=0 >/dev/null
fi
for _ in $(seq 1 30); do
  if curl -sf -X POST -H 'Content-Type: application/json' \
      --data '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":1}' http://localhost:8545 >/dev/null; then
    echo "Besu dev node is up on http://localhost:8545 (chain id 1337)"; exit 0
  fi
  sleep 2
done
echo "Besu did not start; see: docker logs $NAME" >&2; exit 1
