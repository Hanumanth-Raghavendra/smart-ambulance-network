#!/usr/bin/env python3
"""
End-to-end smoke test: plays two complete emergencies against a running backend.

  Trip A: police approve the green corridor  -> ambulance should lose (almost) no time at red lights
  Trip B: police ignore the corridor         -> ambulance waits at red lights

Usage (backend must be running on :8080 with the demo data):
    python3 scripts/e2e_flow.py [http://localhost:8080]
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
PASSWORD = "Demo@123"


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} -> {e.code}: {e.read().decode()}")


def login(email):
    return call("POST", "/api/auth/login", body={"email": email, "password": PASSWORD})["token"]


def check(cond, msg):
    print(("  PASS  " if cond else "  FAIL  ") + msg)
    if not cond:
        global failures
        failures += 1


failures = 0


def wait_for(token, rid, wanted, timeout=240, on_poll=None):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        v = call("GET", f"/api/requests/{rid}", token)
        if v["status"] != last:
            print(f"      status -> {v['status']}  (hospital={v['hospital']['name'] if v['hospital'] else None})")
            last = v["status"]
        if on_poll:
            on_poll(v)
        if v["status"] in wanted:
            return v
        time.sleep(1.0)
    raise SystemExit(f"timed out waiting for {wanted}, last status {last}")


def run_trip(label, patient_email, hospital_email, lat, lng, severity, speciality, approve):
    print(f"\n=== {label} ===")
    patient = login(patient_email)
    hospital = login(hospital_email)
    police = login("police@sanet.demo")

    r = call("POST", "/api/requests", patient, {
        "lat": lat, "lng": lng, "severity": severity, "requiredSpeciality": speciality,
        "description": "e2e test", "address": "test location"})
    rid = r["id"]
    print(f"  request #{rid} created, ranking:")
    for h in r["ranking"]:
        print(f"      {h['name']:<42} travel={h['travelMinutes']:>5} min  match={h['specialityMatch']}  beds={h['bedsAvailable']}  score={h['score']}")
    check(r["status"] == "PENDING_HOSPITAL", "request is waiting for the best-ranked hospital")
    best = r["ranking"][0]["hospitalId"]
    check(r["hospital"]["id"] == best, "the lowest score hospital was offered the request")

    # the staff account for the offered hospital accepts
    staff_email = f"hospital{best}@sanet.demo"
    hospital = login(staff_email)
    beds_before = r["hospital"]["bedsAvailable"]
    r = call("POST", f"/api/requests/{rid}/accept", hospital)
    check(r["status"] in ("DISPATCHED", "WAITING_AMBULANCE"), f"hospital accepted -> {r['status']}")
    check(r["hospital"]["bedsAvailable"] == beds_before - 1, "a bed was reserved for the patient")
    check(r["ambulance"] is not None, f"ambulance assigned: {r['ambulance']['vehicleNo'] if r['ambulance'] else None}")
    check(len(r["stops"]) > 0, f"route to patient has {len(r['stops'])} junctions with ETAs")
    amb_idx = r["ambulance"]["vehicleNo"][-1]
    driver = login(f"ambulance{amb_idx}@sanet.demo")

    me = call("GET", "/api/ambulance/me", driver)
    check(me["request"] and me["request"]["id"] == rid, "ambulance driver sees the dispatch")

    r = call("POST", f"/api/requests/{rid}/start", driver, {"mode": "SIMULATED"})
    check(r["status"] == "EN_ROUTE_TO_PATIENT", "driver accepted, ambulance is moving")

    if approve:
        res = call("POST", f"/api/police/requests/{rid}/clearances/approve-all", police)
        check(all(c["status"] in ("APPROVED", "GREEN_SENT", "PASSED") for c in res), f"police approved corridor ({len(res)} junctions)")

    seen = set()

    def poll(v):
        seen.add(v["status"])
        for s in v["stops"]:
            if s["signalForced"]:
                seen.add("forced-green")

    # leg 1 -> patient, auto pickup in simulation, leg 2 -> hospital
    v = wait_for(driver, rid, {"AT_PATIENT", "EN_ROUTE_TO_HOSPITAL"}, on_poll=poll)
    v = wait_for(driver, rid, {"EN_ROUTE_TO_HOSPITAL"}, on_poll=poll)
    check(True, "patient picked up, now heading to the hospital")
    if approve:
        res = call("POST", f"/api/police/requests/{rid}/clearances/approve-all", police)
        check(len(res) > 0, f"police approved the hospital leg ({len(res)} junctions)")
    call("PUT", f"/api/requests/{rid}/condition", driver, {"note": "stable, oxygen on", "heartRate": 96, "spo2": 94})

    v = wait_for(hospital, rid, {"ARRIVED_AT_HOSPITAL"}, on_poll=poll)
    check(v["etaHospitalSec"] == 0, "ETA is zero on arrival")
    v = call("POST", f"/api/requests/{rid}/handover", hospital)
    check(v["status"] == "COMPLETED", "hospital confirmed handover")
    check(v["conditionNote"] == "stable, oxygen on" and v["heartRate"] == 96, "hospital sees the patient condition")
    t = v["timeline"]
    trip = t["arrivedHospital"] - t["dispatched"]
    print(f"  trip time (dispatch -> hospital) = {trip:.0f} s simulated; delay saved = {v['delaySavedSec']:.0f} s; waited at red = {v['waitedSec']:.0f} s")
    print(f"  alerts sent to road users: {v['alertsSent']}")
    if approve:
        check("forced-green" in seen, "at least one junction was forced green for the ambulance")
        check(v["delaySavedSec"] > 0 or v["waitedSec"] == 0, "green corridor saved time or avoided waiting")
    else:
        check("forced-green" not in seen, "no junction was forced green without approval")
    return v


def main():
    print(f"Testing {BASE}")
    # access control checks
    check(call("GET", "/api/network", login("patient@sanet.demo"))["signals"] is not None, "network endpoint works")
    try:
        urllib.request.urlopen(urllib.request.Request(BASE + "/api/admin/users", headers={"Authorization": "Bearer " + login("patient@sanet.demo")}))
        check(False, "patient must not reach admin API")
    except urllib.error.HTTPError as e:
        check(e.code == 403, "patient is forbidden from admin API (403)")
    try:
        urllib.request.urlopen(BASE + "/api/network")
        check(False, "anonymous must not read network")
    except urllib.error.HTTPError as e:
        check(e.code in (401, 403), "anonymous request is rejected")

    # Trip A near Panjagutta / Ameerpet, critical cardiac
    a = run_trip("Trip A - corridor approved", "patient@sanet.demo", "hospital1@sanet.demo",
                 17.4300, 78.4500, "CRITICAL", "CARDIAC", approve=True)
    # Trip B over the same city but from the east side, corridor ignored
    b = run_trip("Trip B - corridor ignored", "patient2@sanet.demo", "hospital1@sanet.demo",
                 17.4120, 78.4900, "SERIOUS", "GENERAL", approve=False)

    admin = login("admin@sanet.demo")
    an = call("GET", "/api/admin/analytics", admin)
    print("\n=== analytics ===")
    print(json.dumps({k: an[k] for k in ("totalRequests", "completed", "avgResponseSec", "avgTripSec",
                                          "totalDelaySavedSec", "clearanceRequests", "clearancesApproved",
                                          "greenSent", "alertsSent")}, indent=2))
    check(an["completed"] >= 2, "analytics counts completed trips")
    check(an["clearancesApproved"] > 0 and an["greenSent"] > 0, "analytics counts approvals and green signals")

    print("\nRESULT:", "ALL CHECKS PASSED" if failures == 0 else f"{failures} CHECK(S) FAILED")
    sys.exit(1 if failures else 0)


main()
