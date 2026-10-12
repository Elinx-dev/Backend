#!/usr/bin/env bash
# Generates local development certificates once. Never use these outside local development.
set -euo pipefail
cd /fabric
if [ -d organizations/peerOrganizations/survey.slate.local ]; then
  echo "certificates already exist"
  exit 0
fi
cryptogen generate --config=crypto-config.yaml --output=organizations
chmod -R a+rX organizations
echo "certificates generated"
