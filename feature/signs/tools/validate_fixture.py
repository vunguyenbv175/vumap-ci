"""Host-side check for the P1 synthetic fixture (stdlib only).

Usage:
    python tools/validate_fixture.py fixtures/synthetic_vn_signs_p1.json

Enforces the CI budget (<=50 points) plus the same core rules as
SignPackValidator: schema, provenance, VN bbox, P127/R420 payload shape,
duplicate id / near-duplicate location, and prints SHA-256 for manifests.
"""
import hashlib
import json
import math
import sys

MIN_LAT, MAX_LAT = 8.0, 23.5
MIN_LON, MAX_LON = 102.0, 110.0
MAX_SYNTHETIC = 50


def haversine_m(lat1, lon1, lat2, lon2):
    earth = 6371000.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(
        math.radians(lat2)
    ) * math.sin(dlon / 2) ** 2
    return 2 * earth * math.asin(min(1.0, math.sqrt(a)))


def main(path):
    raw = open(path, "rb").read()
    print("file_bytes=%d" % len(raw))
    print("sha256=%s" % hashlib.sha256(raw).hexdigest())
    doc = json.loads(raw.decode("utf-8"))
    errors = []

    if doc.get("schemaVersion") != 1:
        errors.append("UNSUPPORTED_SCHEMA:%r" % doc.get("schemaVersion"))
    prov = doc.get("provenance") or {}
    for key in ("source", "license", "version"):
        if not prov.get(key):
            errors.append("MISSING_PROVENANCE_%s" % key.upper())

    points = doc.get("points") or []
    print("points=%d (budget<=%d)" % (len(points), MAX_SYNTHETIC))
    if len(points) == 0:
        errors.append("TRUNCATED_NO_POINTS")
    if len(points) > MAX_SYNTHETIC:
        errors.append("FIXTURE_OVER_BUDGET:%d" % len(points))

    edge_ids = {e.get("id") for e in (doc.get("edges") or []) if e.get("id")}
    seen_ids = set()
    for i, p in enumerate(points):
        where = "POINT[%d]" % i
        pid = p.get("id")
        if not pid:
            errors.append(where + ":MISSING_ID")
        elif pid in seen_ids:
            errors.append(where + ":DUPLICATE_ID:" + pid)
        else:
            seen_ids.add(pid)
        ptype = p.get("type")
        if ptype not in ("R420", "R421", "P127"):
            errors.append(where + ":BAD_TYPE:%r" % ptype)
        lat, lon = p.get("lat"), p.get("lon")
        if not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
            errors.append(where + ":BAD_COORDS")
        elif not (MIN_LAT <= lat <= MAX_LAT and MIN_LON <= lon <= MAX_LON):
            errors.append(where + ":OUT_OF_VN_RANGE:%r,%r" % (lat, lon))
        conf = p.get("confidence")
        if not isinstance(conf, (int, float)) or not (0.0 <= conf <= 1.0):
            errors.append(where + ":BAD_CONFIDENCE:%r" % conf)
        if not p.get("edgeId"):
            errors.append(where + ":MISSING_EDGE:%r" % pid)
        elif p.get("edgeId") not in edge_ids:
            errors.append(where + ":UNKNOWN_EDGE:%r" % p.get("edgeId"))
        if not p.get("source"):
            errors.append(where + ":MISSING_SOURCE:%r" % pid)
        if ptype == "P127":
            v = p.get("valueKmh")
            if not isinstance(v, int) or not (5 <= v <= 120):
                errors.append(where + ":BAD_P127_VALUE:%r" % v)
        elif ptype in ("R420", "R421"):
            if p.get("valueKmh") is not None:
                errors.append(where + ":ZONE_SIGN_MUST_NOT_CARRY_VALUE:%r" % pid)

    for i in range(len(points)):
        for j in range(i + 1, len(points)):
            a, b = points[i], points[j]
            if a.get("type") != b.get("type"):
                continue
            try:
                if haversine_m(a["lat"], a["lon"], b["lat"], b["lon"]) < 2.0:
                    errors.append("DUPLICATE_LOCATION:%s~%s" % (a.get("id"), b.get("id")))
                    break
            except (KeyError, TypeError):
                pass

    if errors:
        print("RESULT: FAILED")
        for e in errors:
            print("  - " + e)
        return 1
    print("RESULT: FIXTURE OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "fixtures/synthetic_vn_signs_p1.json"))
