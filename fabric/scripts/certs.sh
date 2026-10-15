#!/usr/bin/env bash
# Generates local development certificates once. Never use these outside local development.
set -euo pipefail
cd /fabric
O=organizations
required=(
  "$O/ordererOrganizations/slate.local/orderers/orderer.slate.local/tls/server.crt"
  "$O/ordererOrganizations/slate.local/users/Admin@slate.local/msp/signcerts"
)
for org in registration revenue survey; do
  required+=("$O/peerOrganizations/$org.slate.local/peers/peer0.$org.slate.local/tls/server.crt")
  required+=("$O/peerOrganizations/$org.slate.local/users/Admin@$org.slate.local/msp/signcerts")
done
complete=true
for f in "${required[@]}"; do [ -e "$f" ] || { complete=false; break; }; done
if $complete; then
  echo "certificates already exist"
  exit 0
fi
# Docker Desktop creates empty folders for missing bind mounts; clear any partial output before generating.
if [ -d "$O" ]; then
  echo "incomplete certificates found, regenerating"
  find "$O" -mindepth 1 -delete
fi
cryptogen generate --config=crypto-config.yaml --output="$O"
chmod -R a+rX "$O"
echo "certificates generated"
