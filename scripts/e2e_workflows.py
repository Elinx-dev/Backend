#!/usr/bin/env python3
"""End-to-end exercise of the SLATE API for all seven workflows."""
import json
import sys
import urllib.request
import urllib.error
import uuid

API = "http://localhost:8080"


def call(method, path, token=None, body=None, idem=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if idem:
        req.add_header("Idempotency-Key", str(uuid.uuid4()))
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"FAIL {method} {path} -> {e.code} {e.read().decode()[:900]}")


def login(user):
    r = call("POST", "/api/auth/login", body={"username": user, "password": "Slate@123"})
    if r.get("challengeId"):
        r = call("POST", "/api/auth/verify-otp",
                 body={"challengeId": r["challengeId"], "otp": "123456"})
    return r["accessToken"]


def run(name, deed, prop, scope, details, parties, ro="ro.adyar", survey=False, measured=None,
        parcel_extents=None):
    print(f"\n=== {name} ({deed}) on {prop} ===")
    t = login(ro)
    txn = call("POST", "/api/transactions", t, {
        "propertyRef": prop, "deedTypeCode": deed, "transferScope": scope}, idem=True)
    ref = txn["txn_ref"]
    print(" txn", ref, txn["status"])
    call("PUT", f"/api/transactions/{ref}/details", t, details)
    call("PUT", f"/api/transactions/{ref}/parties", t, parties)
    call("PUT", f"/api/transactions/{ref}/witnesses", t, [
        {"name": "W. One", "address": "Chennai", "idProofType": "AADHAAR", "idProofRef": "XXXX1111"},
        {"name": "W. Two", "address": "Chennai", "idProofType": "AADHAAR", "idProofRef": "XXXX2222"}])
    call("POST", f"/api/transactions/{ref}/transitions", t, {"actionCode": "REQUEST_CONSENT"}, idem=True)
    reqs = call("POST", f"/api/transactions/{ref}/consent/request", t, {})
    for c in reqs:
        call("POST", f"/api/transactions/{ref}/consent/verify", t,
             {"partyId": c["partyId"], "otp": "654321"})
    call("POST", f"/api/transactions/{ref}/transitions", t, {"actionCode": "CONSENT_COMPLETE"}, idem=True)
    rules = call("POST", f"/api/transactions/{ref}/rule-checks", t, {})
    print(" rules", [(r.get("engine"), r.get("overall_outcome") or r.get("overallOutcome") or r.get("outcome")) for r in rules])
    st = call("GET", f"/api/transactions/{ref}", t)["status"]
    if st == "EXCEPTION":
        call("POST", f"/api/transactions/{ref}/transitions", t,
             {"actionCode": "ACKNOWLEDGE_ADVISORY", "reason": "Advisory during pilot"}, idem=True)
    else:
        call("POST", f"/api/transactions/{ref}/transitions", t, {"actionCode": "RULE_CHECKS_CLEAR"}, idem=True)
    fees = call("POST", f"/api/transactions/{ref}/fees", t)
    print(" fees payable", fees.get("totalPayable") or fees.get("total_payable"))
    payable = fees.get("totalPayable") or fees.get("total_payable")
    call("POST", f"/api/transactions/{ref}/payments", t,
         {"mode": "CHALLAN", "referenceNo": "CH-" + ref, "amount": payable}, idem=True)
    call("POST", f"/api/transactions/{ref}/transitions", t, {"actionCode": "RECORD_PAYMENT"}, idem=True)
    reg = call("POST", f"/api/transactions/{ref}/registration", t, idem=True)
    token = (reg.get("token") or {}).get("token_ref")
    print(" registered", reg.get("registeredDocumentNo"), "token", token, "status", reg.get("status"))

    status = call("GET", f"/api/transactions/{ref}", t)["status"]
    if survey:
        s = login("surveyor.sholinganallur")
        if status == "REGISTERED":
            call("POST", f"/api/transactions/{ref}/transitions", t, {"actionCode": "START_SURVEY"}, idem=True)
        v = call("POST", f"/api/transactions/{ref}/survey/visits", s,
                 {"visitDate": "2026-10-01", "visitTime": "10:00:00"})
        vao = login("vao.perungudi")
        call("POST", f"/api/transactions/{ref}/survey/visits/{v['id']}/accept", vao, {})
        call("POST", f"/api/transactions/{ref}/survey/visits/{v['id']}/check-in", s, {})
        extents = parcel_extents or [1200, 1200]
        sub = call("POST", f"/api/transactions/{ref}/survey/submissions", s, {
            "measuredExtent": measured or sum(extents),
            "extentUnit": "SQ_FT", "fmbSketchReference": "FMB/2026/1",
            "surveyDate": "2026-10-01",
            "parcels": [
                {"extentValue": e, "extentUnit": "SQ_FT",
                 "owners": [{"name": parties[i % len(parties)]["name"], "sharePct": 100}]}
                for i, e in enumerate(extents)],
        }, idem=True)
        print(" survey", sub.get("outcome") or sub.get("status"), "variance", sub.get("variancePct"))
    elif status == "REGISTERED":
        call("POST", f"/api/transactions/{ref}/transitions", t, {"actionCode": "PROPOSE_MUTATION"}, idem=True)

    vao = login("vao.perungudi")
    muts = [m for m in call("GET", "/api/revenue/mutations", vao) if m.get("txn_ref") == ref]
    if not muts:
        print(" !! no revenue mutation queued")
        return ref
    mid = muts[0]["id"]
    call("POST", f"/api/revenue/mutations/{mid}/verify", vao, {"remarks": "Field verified"}, idem=True)
    tah = login("tahsildar.sholinganallur")
    appr = call("POST", f"/api/revenue/mutations/{mid}/approve", tah,
                {"mutationRegisterNumber": "MR/2026/" + str(mid), "remarks": "Approved"}, idem=True)
    print(" revenue approved", appr.get("status"))
    if token:
        v = call("POST", f"/api/tokens/{token}/verify", t)
        print(" token verify", v.get("result") or v.get("outcome"))
    return ref


SELLER = {"side": "SIDE_1", "role": "SELLER", "partyType": "INDIVIDUAL", "name": "Raman Krishnan",
          "aadhaarNumber": "999912345678", "pan": "ABCDE1234F", "address": "Perungudi",
          "existingSharePct": 100, "shareTransferredPct": 100}
BUYER = {"side": "SIDE_2", "role": "BUYER", "partyType": "INDIVIDUAL", "name": "Anitha Sekar",
         "aadhaarNumber": "999987654321", "pan": "ZYXWV9876K", "address": "Adyar",
         "resultingSharePct": 100}


def main():
    run("W1 Sale - Full Property", "SALE_FULL", "TN-CHN-00000001", "FULL_PROPERTY",
        {"declaredConsideration": 18000000, "modeOfConsideration": "BANK_TRANSFER"},
        [SELLER, BUYER])

    run("W2 Sale - Undivided Share", "SALE_UNDIVIDED_SHARE", "TN-CHN-00000002", "UNDIVIDED_SHARE",
        {"declaredConsideration": 9000000, "modeOfConsideration": "BANK_TRANSFER",
         "extentOrShareTransferred": 60, "extentUnit": "PERCENT"},
        [dict(SELLER, name="Suseela Devi", existingSharePct=60, shareTransferredPct=60),
         dict(BUYER, resultingSharePct=60)])

    run("W3 Sale - Partial / Subdivision", "SALE_PARTIAL_SUBDIVISION", "TN-CHN-00000003",
        "PHYSICAL_PARTIAL_EXTENT_SUBDIVISION",
        {"declaredConsideration": 12000000, "modeOfConsideration": "BANK_TRANSFER",
         "extentOrShareTransferred": 5000, "extentUnit": "SQ_FT"},
        [dict(SELLER, name="Meenakshi Sundaram"), BUYER], survey=True,
        measured=10000, parcel_extents=[5000, 5000])

    run("W4 Gift", "GIFT", "TN-CHN-00000004", "FULL_PROPERTY",
        {"relationshipCategory": "FAMILY"},
        [dict(SELLER, role="DONOR", name="Thangaraj Pillai"),
         dict(BUYER, role="DONEE", name="Kavitha Thangaraj", relationshipCode="DAUGHTER")])

    run("W5 Settlement", "SETTLEMENT", "TN-KAN-00000005", "FULL_PROPERTY",
        {"relationshipCategory": "FAMILY", "basisOfSettlement": "LOVE_AND_AFFECTION"},
        [dict(SELLER, role="SETTLOR", name="K. Raman"),
         dict(BUYER, role="SETTLEE", name="R. Vimala", relationshipCode="WIFE")])

    run("W6 Release / Relinquishment", "RELEASE_RELINQUISHMENT", "TN-CHN-00000006", "UNDIVIDED_SHARE",
        {"shareBeingReleased": 50},
        [dict(SELLER, role="RELEASOR", name="Selvi Murugan", existingSharePct=50, shareTransferredPct=50),
         dict(BUYER, role="RELEASEE", name="Arun Murugan", resultingSharePct=100)])

    run("W7 Partition", "PARTITION", "TN-CHN-00000007", "PHYSICAL_PARTIAL_EXTENT_SUBDIVISION",
        {"resultingSubparcelCount": 3, "extentOrShareTransferred": 12000, "extentUnit": "SQ_FT"},
        [dict(SELLER, role="COPARCENER", name="Bhaskar Rao", existingSharePct=34, shareTransferredPct=34),
         dict(BUYER, role="COPARCENER", name="Chitra Rao", resultingSharePct=33)],
        ro="ro.sholinganallur", survey=True, measured=12000, parcel_extents=[4000, 4000, 4000])
    print("\nAll seven workflows exercised.")


if __name__ == "__main__":
    sys.exit(main())
