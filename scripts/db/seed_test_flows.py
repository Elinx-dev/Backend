"""Creates test data through the SLATE API: Mint Property -> sale transaction -> Register.

4 success sets reach REGISTERED (set 4 uses Subdivision Required = Yes).
3 error sets stop at the expected refusal: court order found in EC, wrong consent OTP,
Register without payment.

    pip install requests cryptography
    SLATE_API=http://localhost:8081 python3 scripts/db/seed_test_flows.py

Uses the local demo users (password Slate@123) and the mock Revenue/EC fixtures for
KARAPAKKAM village, Sholinganallur taluk, SRO-SHOL.
"""
import base64
import datetime
import os
import random
import uuid

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

API = os.environ.get("SLATE_API", "http://localhost:8081")
PASSWORD = os.environ.get("SLATE_TEST_PASSWORD", "Slate@123")
REGISTRAR = os.environ.get("SLATE_TEST_REGISTRAR", "ro.sholinganallur")


class StepFailed(Exception):
    pass


def login(username):
    pk = requests.get(API + "/api/auth/public-key").json()["publicKey"]
    pem = pk if "BEGIN" in pk else "-----BEGIN PUBLIC KEY-----\n" + pk + "\n-----END PUBLIC KEY-----"
    key = serialization.load_pem_public_key(pem.encode())
    enc = base64.b64encode(key.encrypt(PASSWORD.encode(), padding.OAEP(
        mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))).decode()
    r = requests.post(API + "/api/auth/login", json={"loginId": username, "encryptedPassword": enc}).json()
    if r.get("mfaRequired"):
        r = requests.post(API + "/api/auth/mfa/verify",
                          json={"challengeId": r["challengeId"], "otp": r.get("demoOtp", "123456")}).json()
    return {"Authorization": "Bearer " + r["accessToken"]}


def aadhaar():
    return str(random.randint(2, 9)) + "".join(str(random.randint(0, 9)) for _ in range(11))


def call(h, method, path, body=None, label="", expect_ok=True):
    r = requests.request(method, API + path, headers={**h, "Idempotency-Key": str(uuid.uuid4())}, json=body)
    try:
        data = r.json()
    except ValueError:
        data = r.text
    msg = data.get("message") if isinstance(data, dict) and not r.ok else ""
    print(f"    {label:32s} {r.status_code} {msg}")
    if expect_ok and not r.ok:
        raise StepFailed(f"{label}: {r.status_code} {msg or data}")
    if not expect_ok and r.ok:
        raise StepFailed(f"{label}: expected a refusal but got {r.status_code}")
    return data


def mint(h, survey_no, subdivision_no, owner_name, extent=2400):
    body = {
        "propertyTypeCode": "AGRICULTURAL", "landTypeCode": "RURAL", "classificationCode": "DRY",
        "ownerTypeCode": "INDIVIDUAL", "districtCode": "CHN", "talukCode": "SHOL",
        "villageCode": "KARAPAKKAM", "sroCode": "SRO-SHOL", "street": "OMR Service Road",
        "doorNo": f"{survey_no}/{subdivision_no or '-'}", "guidelineValue": 4500,
        "boundaryNorth": "Road", "boundarySouth": "Canal", "boundaryEast": f"S.No {survey_no}/5",
        "boundaryWest": "Private land",
        "surveyRecords": [{"surveyNo": survey_no, "subdivisionNo": subdivision_no or None,
                           "extentValue": extent, "extentUnit": "SQ_FT"}],
        "owners": [{"ownerName": owner_name, "aadhaarNumber": aadhaar(), "pan": "ABCDE1234F",
                    "mobile": "98" + str(random.randint(10000000, 99999999)),
                    "address": "12 Main Street, Karapakkam, Chennai"}],
        "boundaryMeasurements": [
            {"fromPoint": "NORTH", "toPoint": "SOUTH", "value": 60, "unit": "FT"},
            {"fromPoint": "EAST", "toPoint": "WEST", "value": 40, "unit": "FT"}],
        "chainOfTitle": [{"executorName": "Previous Owner", "claimantName": owner_name,
                          "transactionDate": "2010-05-14", "natureOfTransaction": "SALE",
                          "referenceNo": f"DOC-{survey_no}/2010", "surveyNo": survey_no}],
    }
    p = call(h, "POST", "/api/properties", body, "mint property")
    return p["property_ref"]


def run_flow(h, s):
    ref = mint(h, s["survey"], s["subdiv"], s["owner"])
    prop = call(h, "GET", f"/api/properties/{ref}", label="load property")
    owner_id = prop["registeredOwners"][0]["id"]
    sub = s.get("subdivision", False)
    survey = {}
    if sub:
        surveyors = call(h, "GET", "/api/transactions/surveyors", label="list surveyors")
        survey = {"surveyorUserId": surveyors[0]["id"], "surveyLocationType": "VILLAGE"}
    t = call(h, "POST", "/api/transactions", {
        "propertyRef": ref, "deedTypeCode": "SALE", "sroCode": "SRO-SHOL", "subdivisionRequired": sub,
        "surveyRequiredByParty": False, "declaredConsideration": 10800000,
        "modeOfConsideration": "BANK_TRANSFER", "guidelineValue": 4500,
        "extentOrShareTransferred": 1000 if sub else 2400, "extentUnit": "SQ_FT", **survey},
        "create transaction")
    x = t["txn_ref"]
    call(h, "PUT", f"/api/transactions/{x}/parties", [
        {"side": "SIDE_1", "propertyOwnerId": owner_id},
        {"side": "SIDE_2", "ownerTypeCode": "INDIVIDUAL", "name": s["buyer"], "aadhaarNumber": aadhaar(),
         "pan": "PQRSX6789K", "mobile": "97" + str(random.randint(10000000, 99999999)),
         "address": "7 Lake View Road, Velachery, Chennai", "extentTransferred": 1000 if sub else None}],
        "save parties")
    call(h, "PUT", f"/api/transactions/{x}/witnesses", [
        {"name": "Witness One", "address": "Karapakkam", "idProofType": "AADHAAR", "idProofRef": aadhaar()},
        {"name": "Witness Two", "address": "Sholinganallur", "idProofType": "AADHAAR", "idProofRef": aadhaar()}],
        "save witnesses")
    call(h, "POST", f"/api/transactions/{x}/consent/request", {}, "request consent")
    txn = call(h, "GET", f"/api/transactions/{x}", label="load parties")
    for p in txn.get("parties", []):
        if not p.get("aadhaar_last4") and not p.get("aadhaar_masked") and p.get("side") == "SIDE_1":
            call(h, "PUT", f"/api/transactions/{x}/parties/{p['id']}/aadhaar", {"aadhaarNumber": aadhaar()},
                 "save seller aadhaar")
    wrong_otp = s.get("error") == "WRONG_OTP"
    for i, p in enumerate(txn.get("parties", [])):
        otp = "000000" if wrong_otp and i == 0 else "123456"
        call(h, "POST", f"/api/transactions/{x}/consent/verify", {"partyId": p["id"], "otp": otp},
             f"verify consent party {p['id']}", expect_ok=not (wrong_otp and i == 0))
    if wrong_otp:
        return x, ref
    call(h, "POST", f"/api/transactions/{x}/rule-checks", {}, "run rule checks")
    rule_fail = s.get("error") == "RULE_MISMATCH"
    call(h, "POST", f"/api/transactions/{x}/transitions", {"actionCode": "RULE_CHECKS_CLEAR"},
         "clear rule checks", expect_ok=not rule_fail)
    if rule_fail:
        return x, ref
    fee = call(h, "POST", f"/api/transactions/{x}/fees", {"pageCount": 4}, "calculate fees")
    no_pay = s.get("error") == "NO_PAYMENT"
    if not no_pay:
        amount = fee.get("total_payable") or fee.get("totalPayable") or fee.get("balance")
        call(h, "POST", f"/api/transactions/{x}/payments",
             {"mode": "CASH", "referenceNo": f"RCPT-{x}", "amount": float(amount),
              "paidAt": datetime.datetime.now(datetime.timezone.utc).isoformat()}, "record payment")
    call(h, "POST", f"/api/transactions/{x}/transitions", {"actionCode": "RECORD_PAYMENT"},
         "submit (payment complete)", expect_ok=not no_pay)
    call(h, "POST", f"/api/transactions/{x}/registration", {"comment": "Test data"}, "register",
         expect_ok=not no_pay)
    return x, ref


# Only KARAPAKKAM 310/1 and 215/4 have mock EC/Revenue records that pass every rule check;
# 330/2 carries a court order in its EC, which the rule checks refuse.
SETS = [
    {"name": "S1 Full sale", "survey": "310", "subdiv": "1", "owner": "Meena Rajan", "buyer": "Karthik Raja"},
    {"name": "S2 Full sale", "survey": "215", "subdiv": "4", "owner": "Arun Prakash", "buyer": "Divya Menon"},
    {"name": "S3 Full sale", "survey": "310", "subdiv": "1", "owner": "Meena Rajan", "buyer": "Farhan Ali"},
    {"name": "S4 Part sale, Subdivision = Yes", "survey": "215", "subdiv": "4", "owner": "Arun Prakash",
     "buyer": "Harish Varma", "subdivision": True},
    {"name": "E1 Court order in EC blocks rule checks", "survey": "330", "subdiv": "2",
     "owner": "Senthil Kumar", "buyer": "Ganesh Iyer", "error": "RULE_MISMATCH"},
    {"name": "E2 Wrong Aadhaar consent OTP", "survey": "215", "subdiv": "4", "owner": "Arun Prakash",
     "buyer": "Revathi S", "error": "WRONG_OTP"},
    {"name": "E3 Register without fee payment", "survey": "310", "subdiv": "1", "owner": "Meena Rajan",
     "buyer": "Naveen Kumar", "error": "NO_PAYMENT"},
]

if __name__ == "__main__":
    h = login(REGISTRAR)
    summary = []
    only = [x.strip() for x in os.environ.get("SLATE_TEST_ONLY", "").split(",") if x.strip()]
    for s in SETS:
        if only and s["name"].split()[0] not in only:
            continue
        print(f"== {s['name']}")
        try:
            x, ref = run_flow(h, s)
            status = call(h, "GET", f"/api/transactions/{x}", label="final status").get("status")
            summary.append((s["name"], ref, x, status, "OK"))
        except StepFailed as e:
            summary.append((s["name"], "", "", "", f"FAILED: {e}"))
    print("\n== Summary")
    for row in summary:
        print(" | ".join(str(c) for c in row))
