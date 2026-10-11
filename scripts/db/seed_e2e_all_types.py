"""End-to-end test data for every transaction type: Mint Property -> Register -> VAO / Surveyor
-> Tahsildar approval (REVENUE_APPROVED).

    pip install requests cryptography
    SLATE_API=http://localhost:8081 python3 scripts/db/seed_e2e_all_types.py
    SLATE_TEST_ONLY=F1,F7 python3 scripts/db/seed_e2e_all_types.py   # selected sets only

Uses the demo users (password Slate@123), the mock Revenue/EC fixtures for KARAPAKKAM village
(Sholinganallur taluk, SRO-SHOL) and migration V130, which gives vao.perungudi and
surveyor.sholinganallur access to KARAPAKKAM.
"""
import datetime
import os
import random
import uuid

import requests

from seed_test_flows import API, REGISTRAR, StepFailed, aadhaar, call, login, mint

DAY = datetime.date.today()
SLOT_TIMES = ["09:00", "10:30", "12:00", "14:30", "16:00"]
_slot = [0]
PDF = b"%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"


def next_slot():
    """A free (date, time) for each booking, so officers never double-book a slot."""
    i = _slot[0]
    _slot[0] += 1
    return (DAY + datetime.timedelta(days=i // len(SLOT_TIMES))).isoformat(), SLOT_TIMES[i % len(SLOT_TIMES)]


def mobile():
    return "9" + str(random.randint(100000000, 999999999))


def person(name, **extra):
    return {"ownerTypeCode": "INDIVIDUAL", "name": name, "aadhaarNumber": aadhaar(), "pan": "PQRSX6789K",
            "mobile": mobile(), "address": "7 Lake View Road, Velachery, Chennai", **extra}


def upload(h, x, doc_type):
    r = requests.post(f"{API}/api/transactions/{x}/partition/documents", headers={**h, "Idempotency-Key": str(uuid.uuid4())},
                      data={"documentType": doc_type},
                      files={"file": (doc_type.lower() + ".pdf", PDF, "application/pdf")})
    print(f"    {'upload ' + doc_type:32s} {r.status_code}")
    if not r.ok:
        raise StepFailed(f"upload {doc_type}: {r.status_code} {r.text[:300]}")
    return r.json()["documentId"]


def partition_parties(h, x, s, prop):
    owner = prop["registeredOwners"][0]
    death = upload(h, x, "DEATH_CERTIFICATE")
    lhc = upload(h, x, "LEGAL_HEIR_CERTIFICATE")
    heirs = [{"name": n, "relationship": rel, "status": "LIVING", "maritalStatus": "MARRIED",
              "aadhaarNumber": aadhaar(), "pan": "PQRSX6789K", "mobile": mobile(),
              "address": "12 Main Street, Karapakkam, Chennai"} for n, rel in s["heirs"]]
    view = call(h, "PUT", f"/api/transactions/{x}/partition/members", {"owners": [{
        "propertyOwnerId": owner["id"], "status": "DECEASED",
        "death": {"dateOfDeath": "2025-03-10", "certificateNo": "DC/CHN/2025/1187",
                  "certificateDate": "2025-03-20", "certificateDocumentId": death},
        "legalHeirCertificate": {"available": True, "certificateNo": "LHC/SHOL/2025/77",
                                 "issueDate": "2025-05-02", "documentId": lhc},
        "heirs": heirs}]}, "save partition parties")
    share = 2400 / len(s["heirs"])
    schedules = [{"label": f"Schedule {chr(65 + i)}", "allottedRefs": [p["memberRef"]], "extentUnit": "SQ_FT",
                  "northBoundary": "Road", "southBoundary": "Canal", "eastBoundary": f"Schedule {chr(66 + i)}",
                  "westBoundary": "Private land",
                  "surveys": [{"surveyNo": s["survey"], "subdivisionNo": s["subdiv"], "extent": share,
                               "extentUnit": "SQ_FT", "value": share * 4500}]}
                 for i, p in enumerate(view["participants"])]
    call(h, "PUT", f"/api/transactions/{x}/partition/schedules", schedules, "save schedules")


def register(h, s):
    ref = mint(h, s["survey"], s["subdiv"], s["owner"])
    prop = call(h, "GET", f"/api/properties/{ref}", label="load property")
    owner_id = prop["registeredOwners"][0]["id"]
    deed = s["type"]
    sub = s.get("subdivision", False)
    body = {"propertyRef": ref, "deedTypeCode": deed, "sroCode": "SRO-SHOL", "subdivisionRequired": sub,
            "surveyRequiredByParty": False, "guidelineValue": 4500, "extentUnit": "SQ_FT",
            "extentOrShareTransferred": 1000 if sub else 2400, **s.get("details", {})}
    if sub or deed == "PARTITION":
        surveyors = call(h, "GET", "/api/transactions/surveyors", label="list surveyors")
        body.update(surveyorUserId=surveyors[0]["id"], surveyLocationType="VILLAGE")
    x = call(h, "POST", "/api/transactions", body, f"create {deed} transaction")["txn_ref"]
    if deed == "PARTITION":
        partition_parties(h, x, s, prop)
    else:
        call(h, "PUT", f"/api/transactions/{x}/parties", [
            {"side": "SIDE_1", "propertyOwnerId": owner_id},
            person(s["buyer"], side="SIDE_2", extentTransferred=1000 if sub else None, **s.get("party", {}))],
            "save parties")
    call(h, "PUT", f"/api/transactions/{x}/witnesses", [
        {"name": "Witness One", "address": "Karapakkam", "idProofType": "AADHAAR", "idProofRef": aadhaar()},
        {"name": "Witness Two", "address": "Sholinganallur", "idProofType": "AADHAAR", "idProofRef": aadhaar()}],
        "save witnesses")
    call(h, "POST", f"/api/transactions/{x}/consent/request", {}, "request consent")
    txn = call(h, "GET", f"/api/transactions/{x}", label="load parties")
    for p in txn.get("parties", []):
        if p.get("deceased"):
            continue
        call(h, "POST", f"/api/transactions/{x}/consent/verify", {"partyId": p["id"], "otp": "123456"},
             f"verify consent {p.get('name', p['id'])}"[:32])
    call(h, "POST", f"/api/transactions/{x}/rule-checks", {}, "run rule checks")
    call(h, "POST", f"/api/transactions/{x}/transitions", {"actionCode": "RULE_CHECKS_CLEAR"}, "clear rule checks")
    fee = call(h, "POST", f"/api/transactions/{x}/fees", {"pageCount": 4}, "calculate fees")
    amount = fee.get("total_payable") or fee.get("totalPayable") or fee.get("balance")
    call(h, "POST", f"/api/transactions/{x}/payments",
         {"mode": "CASH", "referenceNo": f"RCPT-{x}", "amount": float(amount),
          "paidAt": datetime.datetime.now(datetime.timezone.utc).isoformat()}, "record payment")
    call(h, "POST", f"/api/transactions/{x}/transitions", {"actionCode": "RECORD_PAYMENT"}, "submit (payment complete)")
    call(h, "POST", f"/api/transactions/{x}/registration", {"comment": "Test data"}, "register")
    return x, ref


def book(h, path, label):
    """Books the next free slot; slots already taken by earlier runs come back as 409 and are skipped."""
    for _ in range(50):
        date, time = next_slot()
        r = requests.post(f"{API}{path}", headers={**h, "Idempotency-Key": str(uuid.uuid4())},
                          json={"visitDate": date, "visitTime": time})
        if r.status_code != 409:
            print(f"    {label:32s} {r.status_code} {date} {time}")
            if not r.ok:
                raise StepFailed(f"{label}: {r.status_code} {r.text[:300]}")
            return
    raise StepFailed(label + ": no free slot")


def survey(sv, x):
    book(sv, f"/api/surveyor/records/{x}/book", "surveyor book slot")
    rec = call(sv, "GET", f"/api/surveyor/records/{x}", label="surveyor load record")
    children = rec.get("subdivisionProperties") or rec.get("childProperties") or []
    body = {"measuredExtent": 2400, "extentUnit": "SQ_FT", "fmbSketchReference": f"FMB/{x}",
            "surveyDate": DAY.isoformat(), "boundaryNorth": "Road", "boundarySouth": "Canal",
            "boundaryEast": "Plot 12", "boundaryWest": "Plot 14", "siteNotes": "Test data survey"}
    if children:
        body["parcels"] = [{"propertyRef": c["property_ref"], "surveyNo": str(c.get("survey_no")),
                            "officialSubdivisionNo": f"{c.get('subdivision_no') or ''}{chr(65 + i)}",
                            "extentValue": c.get("extent_value"), "extentUnit": c.get("extent_unit") or "SQ_FT",
                            "boundaryNorth": "Road", "boundarySouth": "Canal", "boundaryEast": "Plot 12",
                            "boundaryWest": "Plot 14"} for i, c in enumerate(children)]
    elif rec.get("deed_type_code") == "PARTITION" or x in PARTITION_REFS:
        body["parcels"] = [{"extentValue": 1200, "extentUnit": "SQ_FT"}, {"extentValue": 1200, "extentUnit": "SQ_FT"}]
    call(sv, "POST", f"/api/surveyor/records/{x}/survey", body, "surveyor submit survey")


def vao_verify(vao, x):
    book(vao, f"/api/vao/records/{x}/book", "VAO book slot")
    muts = [m for m in call(vao, "GET", "/api/revenue/mutations", label="VAO mutations") if m.get("txn_ref") == x]
    if not muts:
        raise StepFailed("no revenue mutation for " + x)
    call(vao, "POST", f"/api/revenue/mutations/{muts[0]['id']}/verify", {"remarks": "Field verified"},
         "VAO verify & forward")


PARTITION_REFS = set()


def to_revenue_approved(h, x):
    vao, sv, tah = login("vao.perungudi"), login("surveyor.sholinganallur"), login("tahsildar.sholinganallur")
    for _ in range(6):
        status = call(h, "GET", f"/api/transactions/{x}", label="status").get("status")
        print(f"      -> {status}")
        if status == "REVENUE_APPROVED":
            return status
        if status == "VAO_PENDING":
            vao_verify(vao, x)
        elif status == "SURVEY_PENDING":
            survey(sv, x)
        elif status == "TAHSILDAR_PENDING":
            call(tah, "POST", f"/api/tahsildar/records/{x}/approve",
                 {"mutationRegisterNumber": f"MR/{x}", "remarks": "Approved"}, "Tahsildar approve")
        else:
            raise StepFailed(f"unexpected status {status}")
    raise StepFailed("did not reach REVENUE_APPROVED")


# KARAPAKKAM 310/1 (Meena Rajan) and 215/4 (Arun Prakash) pass every EC and Revenue rule check.
SETS = [
    {"name": "F1 Sale - full property", "type": "SALE", "survey": "310", "subdiv": "1", "owner": "Meena Rajan",
     "buyer": "Karthik Raja", "details": {"declaredConsideration": 10800000, "modeOfConsideration": "BANK_TRANSFER"}},
    {"name": "F2 Sale - part, Subdivision = Yes", "type": "SALE", "survey": "215", "subdiv": "4",
     "owner": "Arun Prakash", "buyer": "Harish Varma", "subdivision": True,
     "details": {"declaredConsideration": 4500000, "modeOfConsideration": "BANK_TRANSFER"}},
    {"name": "F3 Gift - family", "type": "GIFT", "survey": "310", "subdiv": "1", "owner": "Meena Rajan",
     "buyer": "Priya Rajan", "details": {"relationshipCategory": "FAMILY"}, "party": {"relationshipCode": "DAUGHTER"}},
    {"name": "F4 Gift - part, Subdivision = Yes", "type": "GIFT", "survey": "215", "subdiv": "4",
     "owner": "Arun Prakash", "buyer": "Sanjay Prakash", "subdivision": True,
     "details": {"relationshipCategory": "FAMILY"}, "party": {"relationshipCode": "SON"}},
    {"name": "F5 Settlement - family", "type": "SETTLEMENT", "survey": "310", "subdiv": "1", "owner": "Meena Rajan",
     "buyer": "Vignesh Rajan", "details": {"relationshipCategory": "FAMILY", "basisOfSettlement": "LOVE_AND_AFFECTION"},
     "party": {"relationshipCode": "SON"}},
    {"name": "F6 Settlement - part, Subdivision = Yes", "type": "SETTLEMENT", "survey": "215", "subdiv": "4",
     "owner": "Arun Prakash", "buyer": "Meera Prakash", "subdivision": True,
     "details": {"relationshipCategory": "FAMILY", "basisOfSettlement": "LOVE_AND_AFFECTION"},
     "party": {"relationshipCode": "DAUGHTER"}},
    {"name": "F7 Release - co-owner", "type": "RELEASE", "survey": "310", "subdiv": "1", "owner": "Meena Rajan",
     "buyer": "Suresh Rajan", "details": {"relationshipCategory": "CO_OWNER", "shareBeingReleased": 100}},
    {"name": "F8 Partition - deceased owner, 2 legal heirs", "type": "PARTITION", "survey": "215", "subdiv": "4",
     "owner": "Arun Prakash", "heirs": [("Deepa Arun", "DAUGHTER"), ("Gokul Arun", "SON")]},
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
            x, ref = register(h, s)
            if s["type"] == "PARTITION":
                PARTITION_REFS.add(x)
            status = to_revenue_approved(h, x)
            summary.append((s["name"], ref, x, status, "OK"))
        except StepFailed as e:
            summary.append((s["name"], "", "", "", f"FAILED: {e}"))
    print("\n== Summary")
    for row in summary:
        print(" | ".join(str(c) for c in row))
