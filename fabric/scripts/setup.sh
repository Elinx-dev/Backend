#!/usr/bin/env bash
# Creates channel "slate", joins the three peers and deploys chaincode "slateland" (chaincode-as-a-service).
# Safe to re-run: every step checks what already exists.
set -euo pipefail
cd /fabric
export FABRIC_CFG_PATH=/etc/hyperledger/fabric
CHANNEL=slate
CC_NAME=slateland
CC_VERSION=1.0
CC_SEQUENCE=1
ORG_DIR=/fabric/organizations
ORDERER_TLS=$ORG_DIR/ordererOrganizations/slate.local/orderers/orderer.slate.local/tls
ORDERER_CA=$ORDERER_TLS/ca.crt
mkdir -p channel-artifacts

use_org() {
  local org=$1 domain=$1.slate.local
  export CORE_PEER_TLS_ENABLED=true
  export CORE_PEER_LOCALMSPID="$(tr '[:lower:]' '[:upper:]' <<< "${org:0:1}")${org:1}MSP"
  export CORE_PEER_TLS_ROOTCERT_FILE=$ORG_DIR/peerOrganizations/$domain/peers/peer0.$domain/tls/ca.crt
  export CORE_PEER_MSPCONFIGPATH=$ORG_DIR/peerOrganizations/$domain/users/Admin@$domain/msp
  export CORE_PEER_ADDRESS=peer0.$domain:7051
}

wait_for() {
  for _ in $(seq 1 60); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  "$@"
}

echo "== channel $CHANNEL"
if [ ! -f channel-artifacts/$CHANNEL.block ]; then
  configtxgen -configPath /fabric -profile SlateChannel -outputBlock channel-artifacts/$CHANNEL.block -channelID $CHANNEL
fi
OSN="osnadmin channel list -o orderer.slate.local:7053 --ca-file $ORDERER_CA --client-cert $ORDERER_TLS/server.crt --client-key $ORDERER_TLS/server.key"
wait_for $OSN
if ! $OSN | grep -q "\"$CHANNEL\""; then
  osnadmin channel join --channelID $CHANNEL --config-block channel-artifacts/$CHANNEL.block \
    -o orderer.slate.local:7053 --ca-file $ORDERER_CA --client-cert $ORDERER_TLS/server.crt --client-key $ORDERER_TLS/server.key
fi

for org in registration revenue survey; do
  use_org $org
  wait_for peer channel list
  if ! peer channel list 2>/dev/null | grep -qx "$CHANNEL"; then
    echo "== join peer0.$org"
    wait_for peer channel join -b channel-artifacts/$CHANNEL.block
  fi
done

echo "== package $CC_NAME (ccaas)"
PKG=channel-artifacts/$CC_NAME.tar.gz
if [ ! -f $PKG ]; then
  tmp=$(mktemp -d)
  cat > $tmp/connection.json <<JSON
{"address":"slateland-cc:9999","dial_timeout":"10s","tls_required":false}
JSON
  cat > $tmp/metadata.json <<JSON
{"type":"ccaas","label":"${CC_NAME}_${CC_VERSION}"}
JSON
  tar -C $tmp -czf $tmp/code.tar.gz connection.json
  tar -C $tmp -czf $PKG metadata.json code.tar.gz
  rm -rf $tmp
fi
PACKAGE_ID=$(peer lifecycle chaincode calculatepackageid $PKG)
echo "$PACKAGE_ID" > channel-artifacts/$CC_NAME.ccid
echo "package id $PACKAGE_ID"

for org in registration revenue survey; do
  use_org $org
  if ! peer lifecycle chaincode queryinstalled 2>/dev/null | grep -q "$PACKAGE_ID"; then
    echo "== install on peer0.$org"
    peer lifecycle chaincode install $PKG
  fi
done

COMMITTED=$(peer lifecycle chaincode querycommitted -C $CHANNEL -n $CC_NAME 2>/dev/null || true)
if ! grep -q "Sequence: $CC_SEQUENCE" <<< "$COMMITTED"; then
  for org in registration revenue survey; do
    use_org $org
    echo "== approve for $CORE_PEER_LOCALMSPID"
    peer lifecycle chaincode approveformyorg -o orderer.slate.local:7050 --tls --cafile $ORDERER_CA \
      --channelID $CHANNEL --name $CC_NAME --version $CC_VERSION --package-id "$PACKAGE_ID" --sequence $CC_SEQUENCE
  done
  echo "== commit"
  peer lifecycle chaincode commit -o orderer.slate.local:7050 --tls --cafile $ORDERER_CA \
    --channelID $CHANNEL --name $CC_NAME --version $CC_VERSION --sequence $CC_SEQUENCE \
    --peerAddresses peer0.registration.slate.local:7051 --tlsRootCertFiles $ORG_DIR/peerOrganizations/registration.slate.local/peers/peer0.registration.slate.local/tls/ca.crt \
    --peerAddresses peer0.revenue.slate.local:7051 --tlsRootCertFiles $ORG_DIR/peerOrganizations/revenue.slate.local/peers/peer0.revenue.slate.local/tls/ca.crt \
    --peerAddresses peer0.survey.slate.local:7051 --tlsRootCertFiles $ORG_DIR/peerOrganizations/survey.slate.local/peers/peer0.survey.slate.local/tls/ca.crt
fi
use_org registration
peer lifecycle chaincode querycommitted -C $CHANNEL -n $CC_NAME
echo "SLATE Fabric network ready: channel=$CHANNEL chaincode=$CC_NAME"
