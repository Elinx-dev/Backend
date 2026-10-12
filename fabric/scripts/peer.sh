#!/usr/bin/env bash
# Run peer CLI commands against one organisation's node.
#   docker compose run --rm cli <registration|revenue|survey> info
#   docker compose run --rm cli <org> query <Function> [args...]
#   docker compose run --rm cli <org> channels | chaincode
set -euo pipefail
cd /fabric
export FABRIC_CFG_PATH=/etc/hyperledger/fabric
ORG=${1:?org: registration, revenue or survey}; CMD=${2:-info}; shift 2 || true
DOMAIN=$ORG.slate.local
export CORE_PEER_TLS_ENABLED=true
export CORE_PEER_LOCALMSPID="$(tr '[:lower:]' '[:upper:]' <<< "${ORG:0:1}")${ORG:1}MSP"
export CORE_PEER_TLS_ROOTCERT_FILE=/fabric/organizations/peerOrganizations/$DOMAIN/peers/peer0.$DOMAIN/tls/ca.crt
export CORE_PEER_MSPCONFIGPATH=/fabric/organizations/peerOrganizations/$DOMAIN/users/Admin@$DOMAIN/msp
export CORE_PEER_ADDRESS=peer0.$DOMAIN:7051
echo "== $CORE_PEER_LOCALMSPID @ $CORE_PEER_ADDRESS"
case $CMD in
  info) peer channel getinfo -c slate ;;
  channels) peer channel list ;;
  chaincode) peer lifecycle chaincode querycommitted -C slate -n slateland ;;
  query)
    ARGS=$(printf '"%s",' "$@"); ARGS="[${ARGS%,}]"
    peer chaincode query -C slate -n slateland -c "{\"Args\":$ARGS}" ;;
  *) echo "unknown command $CMD"; exit 1 ;;
esac
