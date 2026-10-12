// slateland: SLATE land-parcel token chaincode for the local permissioned Fabric network.
//
// Each land parcel is one non-fungible token (tokenRef) held in registry custody. Ownership is
// split into fractional share units (10000 units = 100%) held by pseudonymous holder ids
// (hashes); no names or personal data are stored on the ledger. Deeds, geo-location and the
// full metadata JSON are anchored as SHA-256 hashes. Encumbrances (mortgage, lease, court
// attachment) block transfers while active. Every state change carries the expected version,
// so a second sale of the same parcel version (double sale) is rejected.
package main

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"sort"
	"strconv"
	"strings"

	"github.com/hyperledger/fabric-chaincode-go/v2/pkg/cid"
	"github.com/hyperledger/fabric-chaincode-go/v2/shim"
	pb "github.com/hyperledger/fabric-protos-go-apiv2/peer"
)

const (
	TotalUnits      = 10000
	Custodian       = "SLATE_REGISTRY_CUSTODY"
	RegistrationMSP = "RegistrationMSP"
	RevenueMSP      = "RevenueMSP"
	SurveyMSP       = "SurveyMSP"
)

type Parcel struct {
	DocType         string         `json:"docType"`
	TokenRef        string         `json:"tokenRef"`
	PropertyRefHash string         `json:"propertyRefHash"`
	Status          string         `json:"status"`
	Version         int            `json:"version"`
	StateHash       string         `json:"stateHash"`
	MetadataHash    string         `json:"metadataHash"`
	GeoHash         string         `json:"geoHash,omitempty"`
	Custodian       string         `json:"custodian"`
	Shares          map[string]int `json:"shares"`
	ParentTokenRef  string         `json:"parentTokenRef,omitempty"`
	ChildTokenRefs  []string       `json:"childTokenRefs,omitempty"`
	LastDeedNo      string         `json:"lastDeedNo,omitempty"`
	LastOperation   string         `json:"lastOperation"`
	LastActorRole   string         `json:"lastActorRole,omitempty"`
	LastOrg         string         `json:"lastOrg"`
	LastTxID        string         `json:"lastTxId"`
	UpdatedAt       string         `json:"updatedAt"`
	ActiveEncs      int            `json:"activeEncumbrances"`
}

type Encumbrance struct {
	DocType    string `json:"docType"`
	TokenRef   string `json:"tokenRef"`
	EncID      string `json:"encumbranceId"`
	Type       string `json:"type"`
	HolderHash string `json:"holderHash"`
	DocHash    string `json:"docHash"`
	Status     string `json:"status"`
	CreatedOrg string `json:"createdOrg"`
	CreatedTx  string `json:"createdTxId"`
	ReleasedTx string `json:"releasedTxId,omitempty"`
	ReleaseDoc string `json:"releaseDocHash,omitempty"`
}

type Deed struct {
	DocType  string `json:"docType"`
	DocNo    string `json:"documentNo"`
	DeedHash string `json:"deedHash"`
	TokenRef string `json:"tokenRef,omitempty"`
	TxID     string `json:"txId"`
}

type Chaincode struct{}

func (c *Chaincode) Init(stub shim.ChaincodeStubInterface) *pb.Response {
	return shim.Success(nil)
}

func (c *Chaincode) Invoke(stub shim.ChaincodeStubInterface) *pb.Response {
	fn, args := stub.GetFunctionAndParameters()
	var out []byte
	var err error
	switch fn {
	case "MintParcel":
		out, err = mintParcel(stub, args)
	case "TransferParcel":
		out, err = transferParcel(stub, args)
	case "TransferShares":
		out, err = transferShares(stub, args)
	case "RetireParcel":
		out, err = retireParcel(stub, args)
	case "Annotate":
		out, err = annotate(stub, args)
	case "AddEncumbrance":
		out, err = addEncumbrance(stub, args)
	case "ReleaseEncumbrance":
		out, err = releaseEncumbrance(stub, args)
	case "RecordDeed":
		out, err = recordDeed(stub, args)
	case "ReadParcel":
		out, err = readParcelJSON(stub, args)
	case "HasState":
		out, err = hasState(stub, args)
	case "DeedOf":
		out, err = deedOf(stub, args)
	case "Encumbrances":
		out, err = encumbrances(stub, args)
	case "History":
		out, err = history(stub, args)
	case "CanTransfer":
		out, err = canTransfer(stub, args)
	default:
		err = fmt.Errorf("unknown function %q", fn)
	}
	if err != nil {
		return shim.Error(err.Error())
	}
	return shim.Success(out)
}

// ---- helpers

func need(args []string, n int, usage string) error {
	if len(args) != n {
		return fmt.Errorf("expected %d args: %s", n, usage)
	}
	return nil
}

func mspOf(stub shim.ChaincodeStubInterface) (string, error) {
	return cid.GetMSPID(stub)
}

func requireOrg(stub shim.ChaincodeStubInterface, allowed ...string) (string, error) {
	msp, err := mspOf(stub)
	if err != nil {
		return "", err
	}
	for _, a := range allowed {
		if msp == a {
			return msp, nil
		}
	}
	return "", fmt.Errorf("ACCESS_DENIED: %s may not call this function (allowed: %s)", msp, strings.Join(allowed, ", "))
}

// roleOrg maps a SLATE officer role to the organisation that must sign for it.
func roleOrg(role string) string {
	switch role {
	case "VAO", "TAHSILDAR", "REVENUE_INSPECTOR", "RI":
		return RevenueMSP
	case "SURVEYOR", "SURVEY_OFFICER":
		return SurveyMSP
	default:
		return RegistrationMSP
	}
}

func parcelKey(stub shim.ChaincodeStubInterface, tokenRef string) (string, error) {
	return stub.CreateCompositeKey("parcel", []string{tokenRef})
}

func getParcel(stub shim.ChaincodeStubInterface, tokenRef string) (*Parcel, error) {
	key, err := parcelKey(stub, tokenRef)
	if err != nil {
		return nil, err
	}
	raw, err := stub.GetState(key)
	if err != nil {
		return nil, err
	}
	if raw == nil {
		return nil, nil
	}
	var p Parcel
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}
	return &p, nil
}

func mustParcel(stub shim.ChaincodeStubInterface, tokenRef string) (*Parcel, error) {
	p, err := getParcel(stub, tokenRef)
	if err != nil {
		return nil, err
	}
	if p == nil {
		return nil, fmt.Errorf("NOT_FOUND: parcel %s is not on the ledger", tokenRef)
	}
	return p, nil
}

func txTime(stub shim.ChaincodeStubInterface) string {
	ts, err := stub.GetTxTimestamp()
	if err != nil || ts == nil {
		return ""
	}
	return ts.AsTime().UTC().Format("2006-01-02T15:04:05Z")
}

func putParcel(stub shim.ChaincodeStubInterface, p *Parcel, op, role, org string) ([]byte, error) {
	p.DocType = "parcel"
	p.LastOperation = op
	p.LastActorRole = role
	p.LastOrg = org
	p.LastTxID = stub.GetTxID()
	p.UpdatedAt = txTime(stub)
	raw, err := json.Marshal(p)
	if err != nil {
		return nil, err
	}
	key, err := parcelKey(stub, p.TokenRef)
	if err != nil {
		return nil, err
	}
	if err := stub.PutState(key, raw); err != nil {
		return nil, err
	}
	if p.StateHash != "" {
		sk, err := stub.CreateCompositeKey("state", []string{p.TokenRef, strings.ToLower(p.StateHash)})
		if err != nil {
			return nil, err
		}
		if err := stub.PutState(sk, []byte(strconv.Itoa(p.Version))); err != nil {
			return nil, err
		}
	}
	event, _ := json.Marshal(map[string]any{"tokenRef": p.TokenRef, "operation": op, "version": p.Version, "org": org})
	_ = stub.SetEvent("ParcelChanged", event)
	return raw, nil
}

func parseShares(s string) (map[string]int, error) {
	shares := map[string]int{}
	if s == "" {
		return shares, nil
	}
	if err := json.Unmarshal([]byte(s), &shares); err != nil {
		return nil, fmt.Errorf("shares must be a JSON object {holderHash: units}: %v", err)
	}
	total := 0
	for h, u := range shares {
		if h == "" || u <= 0 {
			return nil, fmt.Errorf("INVALID_SHARES: every holder needs a positive unit count")
		}
		total += u
	}
	if len(shares) > 0 && total != TotalUnits {
		return nil, fmt.Errorf("INVALID_SHARES: share units total %d, must be %d", total, TotalUnits)
	}
	return shares, nil
}

func checkVersion(p *Parcel, expected string) error {
	v, err := strconv.Atoi(expected)
	if err != nil {
		return fmt.Errorf("expectedVersion must be a number")
	}
	if p.Version != v {
		return fmt.Errorf("DOUBLE_SPEND: parcel %s is at version %d, transaction expected version %d (already transferred or out of date)", p.TokenRef, p.Version, v)
	}
	return nil
}

func transferBlockers(p *Parcel) []string {
	var reasons []string
	if p.Status != "ACTIVE" {
		reasons = append(reasons, "PARCEL_"+p.Status+": parcel is "+p.Status)
	}
	if p.ActiveEncs > 0 {
		reasons = append(reasons, fmt.Sprintf("ENCUMBERED: %d active encumbrance(s) block transfer", p.ActiveEncs))
	}
	return reasons
}

// ---- transactions

// MintParcel(tokenRef, version, propertyRefHash, stateHash, metadataHash, geoHash, sharesJSON, parentTokenRef)
func mintParcel(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 8, "tokenRef, version, propertyRefHash, stateHash, metadataHash, geoHash, sharesJSON, parentTokenRef"); err != nil {
		return nil, err
	}
	org, err := requireOrg(stub, RegistrationMSP, SurveyMSP)
	if err != nil {
		return nil, err
	}
	existing, err := getParcel(stub, args[0])
	if err != nil {
		return nil, err
	}
	if existing != nil {
		return nil, fmt.Errorf("DUPLICATE_TOKEN: parcel %s already minted", args[0])
	}
	version, err := strconv.Atoi(args[1])
	if err != nil || version < 1 {
		return nil, fmt.Errorf("version must be >= 1")
	}
	shares, err := parseShares(args[6])
	if err != nil {
		return nil, err
	}
	p := &Parcel{TokenRef: args[0], Version: version, PropertyRefHash: args[2], StateHash: args[3],
		MetadataHash: args[4], GeoHash: args[5], Shares: shares, ParentTokenRef: args[7],
		Status: "ACTIVE", Custodian: Custodian}
	if p.ParentTokenRef != "" {
		parent, err := getParcel(stub, p.ParentTokenRef)
		if err != nil {
			return nil, err
		}
		if parent != nil {
			parent.ChildTokenRefs = appendUnique(parent.ChildTokenRefs, p.TokenRef)
			if _, err := putParcel(stub, parent, parent.LastOperation, parent.LastActorRole, parent.LastOrg); err != nil {
				return nil, err
			}
		}
	}
	return putParcel(stub, p, "MINT", "", org)
}

// TransferParcel(tokenRef, expectedVersion, stateHash, metadataHash, geoHash, sharesJSON, deedNo)
func transferParcel(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 7, "tokenRef, expectedVersion, stateHash, metadataHash, geoHash, sharesJSON, deedNo"); err != nil {
		return nil, err
	}
	org, err := requireOrg(stub, RegistrationMSP)
	if err != nil {
		return nil, err
	}
	p, err := mustParcel(stub, args[0])
	if err != nil {
		return nil, err
	}
	if err := checkVersion(p, args[1]); err != nil {
		return nil, err
	}
	if reasons := transferBlockers(p); len(reasons) > 0 {
		return nil, fmt.Errorf("TRANSFER_BLOCKED: %s", strings.Join(reasons, "; "))
	}
	shares, err := parseShares(args[5])
	if err != nil {
		return nil, err
	}
	p.Version++
	p.StateHash, p.MetadataHash, p.Shares, p.LastDeedNo = args[2], args[3], shares, args[6]
	if args[4] != "" {
		p.GeoHash = args[4]
	}
	return putParcel(stub, p, "TRANSFER", "REGISTRATION_OFFICER", org)
}

// TransferShares(tokenRef, expectedVersion, fromHolder, toHolder, units, stateHash)
func transferShares(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 6, "tokenRef, expectedVersion, fromHolder, toHolder, units, stateHash"); err != nil {
		return nil, err
	}
	org, err := requireOrg(stub, RegistrationMSP)
	if err != nil {
		return nil, err
	}
	p, err := mustParcel(stub, args[0])
	if err != nil {
		return nil, err
	}
	if err := checkVersion(p, args[1]); err != nil {
		return nil, err
	}
	if reasons := transferBlockers(p); len(reasons) > 0 {
		return nil, fmt.Errorf("TRANSFER_BLOCKED: %s", strings.Join(reasons, "; "))
	}
	units, err := strconv.Atoi(args[4])
	if err != nil || units <= 0 {
		return nil, fmt.Errorf("units must be positive")
	}
	if p.Shares[args[2]] < units {
		return nil, fmt.Errorf("INSUFFICIENT_SHARES: holder has %d units, tried to move %d", p.Shares[args[2]], units)
	}
	p.Shares[args[2]] -= units
	if p.Shares[args[2]] == 0 {
		delete(p.Shares, args[2])
	}
	p.Shares[args[3]] += units
	p.Version++
	p.StateHash = args[5]
	return putParcel(stub, p, "SHARE_TRANSFER", "REGISTRATION_OFFICER", org)
}

// RetireParcel(tokenRef, expectedVersion, stateHash, status, childTokenRefsJSON, role)
func retireParcel(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 6, "tokenRef, expectedVersion, stateHash, status, childTokenRefsJSON, role"); err != nil {
		return nil, err
	}
	org, err := requireOrg(stub, RegistrationMSP, SurveyMSP)
	if err != nil {
		return nil, err
	}
	p, err := mustParcel(stub, args[0])
	if err != nil {
		return nil, err
	}
	if err := checkVersion(p, args[1]); err != nil {
		return nil, err
	}
	if args[3] != "SUPERSEDED" && args[3] != "INACTIVE" {
		return nil, fmt.Errorf("status must be SUPERSEDED or INACTIVE")
	}
	if p.ActiveEncs > 0 {
		return nil, fmt.Errorf("TRANSFER_BLOCKED: release %d active encumbrance(s) before split", p.ActiveEncs)
	}
	var children []string
	if args[4] != "" {
		if err := json.Unmarshal([]byte(args[4]), &children); err != nil {
			return nil, fmt.Errorf("childTokenRefsJSON must be a JSON array")
		}
	}
	for _, ch := range children {
		p.ChildTokenRefs = appendUnique(p.ChildTokenRefs, ch)
	}
	p.Version++
	p.StateHash, p.Status = args[2], args[3]
	return putParcel(stub, p, "RETIRE", args[5], org)
}

// Annotate(tokenRef, expectedVersion, stateHash, action, role, decision)
func annotate(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 6, "tokenRef, expectedVersion, stateHash, action, role, decision"); err != nil {
		return nil, err
	}
	org, err := requireOrg(stub, roleOrg(args[4]))
	if err != nil {
		return nil, err
	}
	p, err := mustParcel(stub, args[0])
	if err != nil {
		return nil, err
	}
	if err := checkVersion(p, args[1]); err != nil {
		return nil, err
	}
	p.Version++
	p.StateHash = args[2]
	return putParcel(stub, p, "ANNOTATE:"+args[3]+":"+args[5], args[4], org)
}

// AddEncumbrance(tokenRef, encumbranceId, type, holderHash, docHash)
func addEncumbrance(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 5, "tokenRef, encumbranceId, type, holderHash, docHash"); err != nil {
		return nil, err
	}
	var allowed []string
	switch args[2] {
	case "MORTGAGE", "LEASE":
		allowed = []string{RegistrationMSP}
	case "COURT_ATTACHMENT":
		allowed = []string{RegistrationMSP, RevenueMSP}
	default:
		return nil, fmt.Errorf("type must be MORTGAGE, LEASE or COURT_ATTACHMENT")
	}
	org, err := requireOrg(stub, allowed...)
	if err != nil {
		return nil, err
	}
	p, err := mustParcel(stub, args[0])
	if err != nil {
		return nil, err
	}
	key, err := stub.CreateCompositeKey("enc", []string{args[0], args[1]})
	if err != nil {
		return nil, err
	}
	if raw, err := stub.GetState(key); err != nil {
		return nil, err
	} else if raw != nil {
		return nil, fmt.Errorf("DUPLICATE_ENCUMBRANCE: %s already recorded", args[1])
	}
	e := Encumbrance{DocType: "encumbrance", TokenRef: args[0], EncID: args[1], Type: args[2], HolderHash: args[3],
		DocHash: args[4], Status: "ACTIVE", CreatedOrg: org, CreatedTx: stub.GetTxID()}
	raw, _ := json.Marshal(e)
	if err := stub.PutState(key, raw); err != nil {
		return nil, err
	}
	p.ActiveEncs++
	if _, err := putParcel(stub, p, "ENCUMBER:"+args[2], "", org); err != nil {
		return nil, err
	}
	return raw, nil
}

// ReleaseEncumbrance(tokenRef, encumbranceId, releaseDocHash)
func releaseEncumbrance(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 3, "tokenRef, encumbranceId, releaseDocHash"); err != nil {
		return nil, err
	}
	key, err := stub.CreateCompositeKey("enc", []string{args[0], args[1]})
	if err != nil {
		return nil, err
	}
	raw, err := stub.GetState(key)
	if err != nil {
		return nil, err
	}
	if raw == nil {
		return nil, fmt.Errorf("NOT_FOUND: encumbrance %s", args[1])
	}
	var e Encumbrance
	_ = json.Unmarshal(raw, &e)
	if e.Status != "ACTIVE" {
		return nil, fmt.Errorf("ALREADY_RELEASED: encumbrance %s", args[1])
	}
	org, err := requireOrg(stub, RegistrationMSP, e.CreatedOrg)
	if err != nil {
		return nil, err
	}
	p, err := mustParcel(stub, args[0])
	if err != nil {
		return nil, err
	}
	e.Status, e.ReleasedTx, e.ReleaseDoc = "RELEASED", stub.GetTxID(), args[2]
	raw, _ = json.Marshal(e)
	if err := stub.PutState(key, raw); err != nil {
		return nil, err
	}
	if p.ActiveEncs > 0 {
		p.ActiveEncs--
	}
	if _, err := putParcel(stub, p, "RELEASE:"+e.Type, "", org); err != nil {
		return nil, err
	}
	return raw, nil
}

// RecordDeed(documentNo, deedHash, tokenRef)
func recordDeed(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 3, "documentNo, deedHash, tokenRef"); err != nil {
		return nil, err
	}
	if _, err := requireOrg(stub, RegistrationMSP); err != nil {
		return nil, err
	}
	key, err := stub.CreateCompositeKey("deed", []string{args[0]})
	if err != nil {
		return nil, err
	}
	raw, err := stub.GetState(key)
	if err != nil {
		return nil, err
	}
	if raw != nil {
		var d Deed
		_ = json.Unmarshal(raw, &d)
		if strings.EqualFold(d.DeedHash, args[1]) {
			return raw, nil
		}
		return nil, fmt.Errorf("DEED_EXISTS: document %s is already recorded with a different hash", args[0])
	}
	raw, _ = json.Marshal(Deed{DocType: "deed", DocNo: args[0], DeedHash: args[1], TokenRef: args[2], TxID: stub.GetTxID()})
	return raw, stub.PutState(key, raw)
}

// ---- queries

func readParcelJSON(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 1, "tokenRef"); err != nil {
		return nil, err
	}
	p, err := mustParcel(stub, args[0])
	if err != nil {
		return nil, err
	}
	return json.Marshal(p)
}

func hasState(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 2, "tokenRef, stateHash"); err != nil {
		return nil, err
	}
	key, err := stub.CreateCompositeKey("state", []string{args[0], strings.ToLower(args[1])})
	if err != nil {
		return nil, err
	}
	raw, err := stub.GetState(key)
	if err != nil {
		return nil, err
	}
	return []byte(strconv.FormatBool(raw != nil)), nil
}

func deedOf(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 1, "documentNo"); err != nil {
		return nil, err
	}
	key, err := stub.CreateCompositeKey("deed", []string{args[0]})
	if err != nil {
		return nil, err
	}
	raw, err := stub.GetState(key)
	if err != nil {
		return nil, err
	}
	if raw == nil {
		return []byte(""), nil
	}
	var d Deed
	_ = json.Unmarshal(raw, &d)
	return []byte(d.DeedHash), nil
}

func encumbrances(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 1, "tokenRef"); err != nil {
		return nil, err
	}
	it, err := stub.GetStateByPartialCompositeKey("enc", []string{args[0]})
	if err != nil {
		return nil, err
	}
	defer it.Close()
	out := []Encumbrance{}
	for it.HasNext() {
		kv, err := it.Next()
		if err != nil {
			return nil, err
		}
		var e Encumbrance
		if json.Unmarshal(kv.Value, &e) == nil {
			out = append(out, e)
		}
	}
	return json.Marshal(out)
}

func history(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 1, "tokenRef"); err != nil {
		return nil, err
	}
	key, err := parcelKey(stub, args[0])
	if err != nil {
		return nil, err
	}
	it, err := stub.GetHistoryForKey(key)
	if err != nil {
		return nil, err
	}
	defer it.Close()
	type entry struct {
		TxID      string  `json:"txId"`
		Timestamp string  `json:"timestamp"`
		Parcel    *Parcel `json:"parcel"`
	}
	out := []entry{}
	for it.HasNext() {
		m, err := it.Next()
		if err != nil {
			return nil, err
		}
		var p Parcel
		e := entry{TxID: m.TxId}
		if m.Timestamp != nil {
			e.Timestamp = m.Timestamp.AsTime().UTC().Format("2006-01-02T15:04:05Z")
		}
		if json.Unmarshal(m.Value, &p) == nil {
			e.Parcel = &p
		}
		out = append(out, e)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Parcel == nil || out[j].Parcel == nil {
			return false
		}
		return out[i].Parcel.Version < out[j].Parcel.Version
	})
	return json.Marshal(out)
}

// CanTransfer(tokenRef, expectedVersion) -> {"allowed": bool, "reasons": [...], "version": n}
func canTransfer(stub shim.ChaincodeStubInterface, args []string) ([]byte, error) {
	if err := need(args, 2, "tokenRef, expectedVersion"); err != nil {
		return nil, err
	}
	p, err := getParcel(stub, args[0])
	if err != nil {
		return nil, err
	}
	if p == nil {
		return json.Marshal(map[string]any{"allowed": true, "onLedger": false, "reasons": []string{}})
	}
	reasons := transferBlockers(p)
	if err := checkVersion(p, args[1]); err != nil && args[1] != "" {
		reasons = append(reasons, err.Error())
	}
	if reasons == nil {
		reasons = []string{}
	}
	return json.Marshal(map[string]any{"allowed": len(reasons) == 0, "onLedger": true, "version": p.Version, "reasons": reasons})
}

func appendUnique(list []string, v string) []string {
	for _, x := range list {
		if x == v {
			return list
		}
	}
	return append(list, v)
}

func main() {
	address := os.Getenv("CHAINCODE_SERVER_ADDRESS")
	ccid := os.Getenv("CHAINCODE_ID")
	if address == "" || ccid == "" {
		log.Fatal("CHAINCODE_SERVER_ADDRESS and CHAINCODE_ID are required")
	}
	server := &shim.ChaincodeServer{
		CCID:     ccid,
		Address:  address,
		CC:       &Chaincode{},
		TLSProps: shim.TLSProperties{Disabled: true},
	}
	log.Printf("slateland chaincode serving %s on %s", ccid, address)
	if err := server.Start(); err != nil {
		log.Fatalf("chaincode server failed: %v", err)
	}
}
