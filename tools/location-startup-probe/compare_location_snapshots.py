"""Compare recorded Location fields with a qualified native provider path reference."""

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
from statistics import median
from tempfile import TemporaryDirectory


ORIGINS = ("cache", "callback", "current_location")
ELAPSED = "elapsed_realtime_ns"
RECEIVED = "received_elapsed_realtime_ns"
WALL = "time_ms"
RECEIVED_WALL = "received_wall_ms"
FLAGS = {
    "has_accuracy": ("accuracy_m", 1, 0, None),
    "has_altitude": ("altitude_m", 1, None, None),
    "has_speed": ("speed_m_s", 1, 0, None),
    "has_bearing": ("bearing_deg", 1, 0, 360),
    "has_vertical_accuracy": ("vertical_accuracy_m", 26, 0, None),
    "has_speed_accuracy": ("speed_accuracy_m_s", 26, 0, None),
    "has_bearing_accuracy": ("bearing_accuracy_deg", 26, 0, None),
    "has_elapsed_realtime_uncertainty": ("elapsed_realtime_uncertainty_ns", 29, 0, None),
    "has_msl_altitude": ("msl_altitude_m", 34, None, None),
    "has_msl_altitude_accuracy": ("msl_altitude_accuracy_m", 34, 0, None),
}
BASE_FIELDS = ("provider", WALL, ELAPSED, "latitude", "longitude", "is_mock", "extras_keys")
FIELD_API = {**dict.fromkeys(BASE_FIELDS, 1), "is_complete": 33,
             **{key: spec[1] for flag, spec in FLAGS.items() for key in (flag, spec[0])}}
SATELLITE_KEYS = ("satellites", "satellites_visible", "satellites_used_in_fix", "satellites_in_view")
SATELLITE_INTEGER_TYPES = ("Byte", "Short", "Integer", "Long")
REFERENCE_IDENTITIES = {
    "shell": (2000, "com.android.shell", "adb_shell_uid_client"),
    "system": (1000, "android", "independent_system_uid_client"),
}


def finite_number(value):
    if type(value) is int:
        return True
    return type(value) is float and math.isfinite(value)


def reject_constant(value):
    raise ValueError(f"Invalid JSON number {value}")


def read_capture(path, reference):
    raw = path.read_bytes()
    rows = [json.loads(line, parse_constant=reject_constant) for line in raw.splitlines()]
    start, end = ("reference_start", "reference_end") if reference else ("session_start", "session_end")
    if len(rows) < 2 or not all(isinstance(row, dict) for row in rows):
        raise ValueError("Missing capture rows")
    if rows[0].get("event") != start or rows[-1].get("event") != end:
        raise ValueError("Missing capture boundaries")
    if sum(row.get("event") == start for row in rows) != 1 or sum(row.get("event") == end for row in rows) != 1:
        raise ValueError("Duplicate capture boundaries")
    session = rows[0].get("session_id")
    if not session or any(row.get("session_id") != session or type(row.get("sequence")) is not int
                          or row["sequence"] != index for index, row in enumerate(rows, 1)):
        raise ValueError("Mixed sessions or discontinuous sequence")
    return rows, hashlib.sha256(raw).hexdigest()


def system_host_evidence_gap(directory, claim, digest_pattern):
    fields = ("pid", "kernel_uid", "system_server_pid")
    if (not isinstance(claim, dict) or any(type(claim.get(key)) is not int for key in fields)
            or claim["kernel_uid"] != REFERENCE_IDENTITIES["system"][0] or min(claim["pid"], claim["system_server_pid"]) <= 0
            or claim["pid"] == claim["system_server_pid"]):
        return "reference_system_host_identity_invalid"
    filename, digest = claim.get("evidence_file"), claim.get("evidence_sha256")
    if not isinstance(filename, str) or not filename.strip() or not isinstance(digest, str) or not digest_pattern.fullmatch(digest):
        return "reference_system_host_evidence_unbound"
    try:
        raw = (directory / filename).read_bytes()
        if hashlib.sha256(raw).hexdigest() != digest.lower():
            return "reference_system_host_evidence_hash_mismatch"
        evidence = json.loads(raw, parse_constant=reject_constant)
    except (OSError, ValueError):
        return "reference_system_host_evidence_unreadable"
    if not isinstance(evidence, dict) or any(type(evidence.get(key)) is not int or evidence[key] != claim[key] for key in fields):
        return "reference_system_host_evidence_identity_mismatch"
    return None


def read_context(path):
    if path is None:
        return None, {}, "reference_context_missing"
    try:
        value = json.loads(path.read_text(encoding="utf-8"), parse_constant=reject_constant)
        if not isinstance(value, dict):
            raise ValueError("Expected an object")
        digest = re.compile(r"[0-9a-fA-F]{64}")
        commit = re.compile(r"[0-9a-fA-F]{40}(?:[0-9a-fA-F]{24})?")
        identity_mode = value.get("identity_mode", "shell")
        profile = REFERENCE_IDENTITIES.get(identity_mode)
        if not (digest.fullmatch(value["main_apk_sha256"]) and digest.fullmatch(value["rom_sha256"])
                and commit.fullmatch(value["source_commit"]) and type(value["uid"]) is int
                and profile is not None and (value["uid"], value["package_name"]) == profile[:2]
                and isinstance(value["native_scope_evidence"], list) and value["native_scope_evidence"]
                and all(isinstance(ref, str) and ref.strip() for ref in value["native_scope_evidence"])
                and value["same_boot"] is True and value["same_config"] is True
                and finite_number(value["max_age_ms"]) and value["max_age_ms"] > 0
                and isinstance(value["references"], list) and value["references"]):
            raise ValueError("Incomplete reference context")
        entries = {}
        for entry in value["references"]:
            source = (path.parent / entry["file"]).resolve()
            if source in entries or not digest.fullmatch(entry["sha256"]) or entry["mode"] not in ("cache_only", "request"):
                raise ValueError("Invalid or duplicate reference entry")
            host_gap = system_host_evidence_gap(path.parent, entry.get("host_identity"), digest) if identity_mode == "system" else None
            entries[source] = {**entry, "_system_host_evidence_gap": host_gap}
        return value, entries, None
    except (OSError, KeyError, TypeError, ValueError):
        return None, {}, "reference_context_invalid_or_incomplete"


def reference_capture_gap(rows, actual_digest, entry, context, context_gap):
    if context_gap:
        return context_gap
    if entry is None or entry["sha256"].lower() != actual_digest:
        return "reference_file_not_bound_to_context"
    start, end = rows[0], rows[-1]
    identities = [row for row in rows if row.get("event") == "reference_identity"]
    uid, package = context["uid"], context["package_name"]
    if len(identities) != 1 or type(start.get("uid")) is not int or start["uid"] != uid:
        return "reference_identity_not_verified"
    identity = identities[0]
    if (identity.get("uid_owns_package") is not True or identity.get("package_name") != package
            or any(type(identity.get(key)) is not int or identity[key] != uid for key in ("package_uid", "attribution_uid"))):
        return "reference_identity_not_verified"
    identity_mode = context.get("identity_mode", "shell")
    scope = REFERENCE_IDENTITIES[identity_mode][2]
    if identity_mode == "system":
        if identity.get("attribution_package_name") != package:
            return "reference_identity_not_verified"
        host_gap = entry["_system_host_evidence_gap"]
        if host_gap:
            return host_gap
        host = entry["host_identity"]
        if any(row.get("identity") != identity_mode or row.get("identity_scope") != scope for row in (start, identity)):
            return "reference_identity_mode_mismatch"
        if (type(start.get("required_uid")) is not int or start["required_uid"] != host["kernel_uid"]
                or any(type(row.get("pid")) is not int or row["pid"] != host["pid"] for row in (start, identity))
                or type(identity.get("uid")) is not int or identity["uid"] != host["kernel_uid"]):
            return "reference_system_host_identity_mismatch"
    elif any(row.get("identity", "shell") != "shell" or row.get("identity_scope", scope) != scope for row in (start, identity)):
        return "reference_identity_mode_mismatch"
    mode = entry["mode"]
    if start.get("mode") != mode or start.get("adds_gnss_demand") is not (mode == "request"):
        return "reference_capture_mode_mismatch"
    locations = [row for row in rows if row.get("event") == "location"]
    if mode == "cache_only" and any(row.get("origin") != "cache" for row in locations):
        return "reference_capture_mode_mismatch"
    if type(end.get("error_count")) is not int or end["error_count"] != 0 or end.get("status") not in ("ok", "no_fix"):
        return "reference_capture_error"
    if type(end.get("location_count")) is not int or end["location_count"] != len(locations) or (end["status"] == "ok") != bool(locations):
        return "reference_capture_count_mismatch"
    if type(start.get("sdk_int")) is not int or any(row.get("location_api_level") != start["sdk_int"] for row in locations):
        return "reference_capture_sdk_mismatch"
    return "reference_no_sample" if not locations else None


def validate_location(row):
    problems = []
    api = row.get("location_api_level")
    if "location_api_level" in row and (type(api) is not int or api < 1):
        problems.append("location_api_level:invalid_type_or_range")
    if not isinstance(row.get("provider"), str) or not row["provider"]:
        problems.append("provider:missing_or_invalid")
    for key in (ELAPSED, RECEIVED, WALL, RECEIVED_WALL):
        if type(row.get(key)) is not int or row[key] <= 0:
            problems.append(f"{key}:missing_or_invalid_clock")
    for key, minimum, maximum in (("latitude", -90, 90), ("longitude", -180, 180)):
        n = row.get(key)
        if not finite_number(n) or not minimum <= n <= maximum:
            problems.append(f"{key}:invalid_type_or_range")
    for flag, (field, minimum_api, lower, upper) in FLAGS.items():
        if minimum_api != 1 and not (type(api) is int and api >= minimum_api) and flag not in row and field not in row:
            continue
        value = row.get(field)
        if type(row.get(flag)) is not bool or field not in row or row[flag] != (value is not None):
            problems.append(f"{flag}:flag_or_value_missing_or_inconsistent")
        elif value is not None and (not finite_number(value) or (lower is not None and value < lower)
                                    or (upper is not None and value >= upper)):
            problems.append(f"{field}:invalid_type_or_range")
    if type(row.get("is_mock")) is not bool:
        problems.append("is_mock:missing_or_invalid_type")
    if (type(api) is int and api >= 33) or "is_complete" in row:
        if row.get("is_complete") is not True:
            problems.append("is_complete:false_missing_or_invalid")
    if any(key not in row for key in ("extras_keys", "extras_keys_omitted_count", "extras_keys_shortened_count")):
        problems.append("extras_keys:metadata_missing")
    elif row["extras_keys"] is None or not isinstance(row["extras_keys"], list) or any(
        key is not None and not isinstance(key, str) for key in row["extras_keys"]
    ):
        problems.append("extras_keys:unreadable_or_invalid")
    for key in ("extras_keys_omitted_count", "extras_keys_shortened_count"):
        if type(row.get(key)) is not int or row[key] < 0:
            problems.append(f"{key}:invalid_type_or_range")
    satellite = row.get("satellite_extras")
    if "satellite_extras" in row and not isinstance(satellite, dict):
        problems.append("satellite_extras:unreadable_or_invalid")
    if isinstance(satellite, dict):
        for key, entry in satellite.items():
            kind = entry.get("type") if isinstance(entry, dict) else None
            if key not in SATELLITE_KEYS or kind not in (*SATELLITE_INTEGER_TYPES, "Float", "Double", "null", "non_numeric"):
                problems.append("satellite_extras:invalid_schema")
            elif kind in (*SATELLITE_INTEGER_TYPES, "Float", "Double"):
                value = entry.get("value")
                if not finite_number(value) or value < 0 or kind in SATELLITE_INTEGER_TYPES and type(value) is not int:
                    problems.append(f"satellite_extras.{key}:invalid_numeric_value")
    elapsed_age = age(row, ELAPSED, RECEIVED, 1_000_000)
    if elapsed_age is not None and elapsed_age < 0:
        problems.append("elapsed_realtime_ns:future_source")
    return problems


def age(row, source, received, divisor):
    if type(row.get(source)) is int and row[source] > 0 and type(row.get(received)) is int and row[received] > 0:
        try:
            return (row[received] - row[source]) / divisor
        except OverflowError:
            return None
    return None


def field_type(row, key):
    if key not in row:
        return "missing_key"
    value = row[key]
    if value is None:
        return "null"
    if type(value) is bool:
        if key.startswith("has_"):
            return "boolean:true" if value else "boolean:false"
        return "boolean"
    if finite_number(value):
        if key in (ELAPSED, WALL):
            return "integer" if type(value) is int else "float"
        return "number"
    return type(value).__name__


def describe(records):
    rows = [record["row"] for record in records]
    result = {"sample_count": len(rows)}
    for label, clock, receipt, divisor in (("elapsed_age_ms", ELAPSED, RECEIVED, 1_000_000),
                                           ("wall_age_ms", WALL, RECEIVED_WALL, 1)):
        ages = [value for row in rows if (value := age(row, clock, receipt, divisor)) is not None]
        result[label] = {"observed": len(ages), "minimum": min(ages), "median": median(ages), "maximum": max(ages),
                         "negative_count": sum(value < 0 for value in ages)} if ages else {"observed": 0}
    result["issues"] = [{"source": r["source"], "issues": r["issues"]} for r in records if r["issues"]]
    return result


def compare_schema(probe, reference):
    differences = {}
    unobserved = []
    for key, api_required in FIELD_API.items():
        shapes = []
        for group in (probe, reference):
            observed = set()
            for record in group:
                row = record["row"]
                api = row.get("location_api_level")
                if key in row or api_required == 1 or type(api) is int and api >= api_required:
                    observed.add(field_type(row, key))
            shapes.append(sorted(observed))
        left, right = shapes
        if not left or not right:
            unobserved.append(key)
        elif left != right:
            differences[key] = {"probe": left, "reference": right}
    satellite_types = {}
    for key in SATELLITE_KEYS:
        by_side = {}
        for label, records in (("probe", probe), ("reference", reference)):
            types = set()
            for record in records:
                extras = record["row"].get("satellite_extras")
                value = extras.get(key) if isinstance(extras, dict) else None
                kind = value.get("type") if isinstance(value, dict) else None
                types.add(kind if isinstance(kind, str) else "missing_or_unreadable")
            by_side[label] = sorted(types)
        if by_side["probe"] != by_side["reference"]:
            satellite_types[key] = by_side
    return {"observed_field_presence_or_type_differences": differences, "unobserved_optional_fields": unobserved,
            "observed_satellite_extra_type_differences": satellite_types}


def compare(probe_paths, reference_paths, context_path):
    context, entries, context_gap = read_context(context_path)
    groups = defaultdict(lambda: {"probe": [], "reference": []})
    sources = []
    for role, paths in (("probe", probe_paths), ("reference", reference_paths)):
        for path in paths:
            source = {"file": str(path), "role": role, "sample_count": 0}
            sources.append(source)
            try:
                rows, digest = read_capture(path, role == "reference")
            except (OSError, ValueError) as error:
                source["gap"] = f"capture_unreadable_or_incomplete:{type(error).__name__}"
                continue
            source["sha256"] = digest
            if role == "reference":
                source["identity"] = [
                    {key: row.get(key) for key in ("identity", "identity_scope", "pid", "uid", "package_name", "package_uid",
                                                   "uid_owns_package", "op_package_name", "attribution_package_name", "attribution_uid")}
                    for row in rows if row.get("event") == "reference_identity"
                ]
            gap = (reference_capture_gap(rows, digest, entries.get(path.resolve()), context, context_gap)
                   if role == "reference" else None)
            if gap:
                source["gap"] = gap
            source["sample_count"] = sum(row.get("event") == "location" for row in rows)
            source["absent_events"] = dict(Counter(str(row.get("origin")) for row in rows
                                                if row.get("event") in ("location_absent", "cache_empty", "current_location_empty")))
            for line, row in enumerate(rows, 1):
                if row.get("event") != "location":
                    continue
                origin, request, provider = (row.get(key) for key in ("origin", "requested_provider", "provider"))
                issues = validate_location(row)
                if origin not in ORIGINS or not isinstance(request, str) or not request or not isinstance(provider, str) or not provider:
                    source.setdefault("unroutable_sample_lines", []).append(line)
                    continue
                omitted = []
                if role == "reference":
                    if gap:
                        omitted.append(gap)
                    if (request, provider) != ("gps", "gps"):
                        omitted.append("reference_not_gps_route")
                    if row.get("is_mock") is True:
                        omitted.append("reference_mock_reported")
                    if any(":missing_or_invalid_clock" in issue for issue in issues):
                        omitted.append("reference_missing_or_invalid_source_or_receipt_time")
                    if issues:
                        omitted.append("reference_field_contract_issue")
                    elapsed_age = age(row, ELAPSED, RECEIVED, 1_000_000)
                    if context and elapsed_age is not None and elapsed_age > context["max_age_ms"]:
                        omitted.append("reference_older_than_context_limit")
                groups[(origin, request, provider)][role].append({"row": row, "issues": issues,
                      "source": {"file": str(path), "line": line}, "unqualified": omitted})
    output = []
    for (origin, request, provider), observations in sorted(groups.items()):
        probe, references = observations["probe"], observations["reference"]
        eligible = [r for r in references if not r["unqualified"]]
        paired = bool(probe and eligible)
        group = {"origin": origin, "requested_provider": request, "provider": provider,
                 "status": "COMPARED" if paired else "UNOBSERVED",
                 "probe": describe(probe), "reference": describe(eligible),
                 "excluded_reference_records": [{"source": r["source"], "reasons": r["unqualified"], "issues": r["issues"]}
                                                for r in references if r["unqualified"]]}
        if paired:
            group.update(compare_schema(probe, eligible))
        output.append(group)
    return {"status": "COMPARED" if any(row["status"] == "COMPARED" for row in output) else "UNOBSERVED",
            "reference_context": context, "reference_context_gap": context_gap, "sources": sources,
            "reference_identity_mode": context.get("identity_mode", "shell") if context else None,
            "groups": output, "scope": "Native GPS path, matched by origin, requested provider and reported provider.",
            "limits": ["The comparison applies to the declared caller and API path. Physical origin requires independent evidence.",
                       "The system UID path has a distinct platform permission scope from ordinary apps.",
                       "Ages use the matching receipt clock for each source clock. No age equality is required.",
                       "Numeric fields are range-checked. Different coordinates, altitude or accuracy values do not constitute a mismatch."]}


def self_test():
    def write_capture(path, reference, rows, start_fields=None, identity_fields=None):
        start = {"event": "reference_start", "uid": 2000, "mode": "cache_only", "adds_gnss_demand": False,
                 "sdk_int": 36} if reference else {"event": "session_start"}
        identity = {"event": "reference_identity", "package_name": "com.android.shell", "package_uid": 2000,
                    "attribution_package_name": "android", "attribution_uid": 2000,
                    "op_package_name": "android", "uid_owns_package": True}
        end = {"event": "reference_end", "status": "ok" if rows else "no_fix", "location_count": len(rows),
               "error_count": 0} if reference else {"event": "session_end"}
        start.update(start_fields or {})
        identity.update(identity_fields or {})
        contents = [start, *([identity] if reference else []), *rows, end]
        path.write_text("".join(json.dumps({**row, "session_id": path.name, "sequence": index}) + "\n"
                                for index, row in enumerate(contents, 1)), encoding="utf-8")

    sample = {"event": "location", "origin": "cache", "requested_provider": "gps", "provider": "gps",
              ELAPSED: 999_000_000, RECEIVED: 1_000_000_000, WALL: 10_000, RECEIVED_WALL: 10_001,
              "latitude": 10.0, "longitude": 20.0, "location_api_level": 36, "is_complete": True,
              "is_mock": False, "extras_keys": [], "extras_keys_omitted_count": 0, "extras_keys_shortened_count": 0}
    for flag, (field, _, _, _) in FLAGS.items():
        sample[flag] = False
        sample[field] = None
    sample.update(has_accuracy=True, accuracy_m=5)
    with TemporaryDirectory() as directory:
        root = Path(directory)
        probe, reference, context = (root / name for name in ("probe.jsonl", "reference.jsonl", "context.json"))
        write_capture(probe, False, [sample])

        def write_context(uid, package_name, identity_mode=None, host_identity=None):
            entry = {"file": reference.name, "mode": "cache_only", "sha256": hashlib.sha256(reference.read_bytes()).hexdigest()}
            if host_identity is not None:
                entry["host_identity"] = host_identity
            value = {"main_apk_sha256": "a" * 64, "rom_sha256": "b" * 64, "source_commit": "c" * 40,
                     "uid": uid, "package_name": package_name, "native_scope_evidence": ["capture notes"],
                     "same_boot": True, "same_config": True, "max_age_ms": 50, "references": [entry]}
            if identity_mode is not None:
                value["identity_mode"] = identity_mode
            context.write_text(json.dumps(value), encoding="utf-8")

        def run(reference_samples):
            write_capture(reference, True, reference_samples)
            write_context(2000, "com.android.shell")
            return compare([probe], [reference], context)

        actual = run([{**sample, "latitude": 12.0, "accuracy_m": 30}])
        assert actual["status"] == "COMPARED" and not actual["groups"][0]["observed_field_presence_or_type_differences"]
        assert compare([probe], [reference], None)["status"] == "UNOBSERVED"
        assert run([])["sources"][1]["gap"] == "reference_no_sample"
        for update, expected in (({"is_mock": True}, "reference_mock_reported"),
                                 ({ELAPSED: None}, "reference_missing_or_invalid_source_or_receipt_time"),
                                 ({ELAPSED: 1}, "reference_older_than_context_limit"),
                                 ({ELAPSED: 2_000_000_000}, "reference_field_contract_issue")):
            candidate = run([{**sample, **update}])
            assert candidate["status"] == "UNOBSERVED" and expected in candidate["groups"][0]["excluded_reference_records"][0]["reasons"]
        assert run([{**sample, "provider": "network", "requested_provider": "network"}])["status"] == "UNOBSERVED"
        write_capture(probe, False, [{**sample, "has_vertical_accuracy": True, "vertical_accuracy_m": -1,
                                      "has_bearing": True, "bearing_deg": 360}])
        actual = run([sample])
        group = actual["groups"][0]
        assert set(group["probe"]["issues"][0]["issues"]) == {"vertical_accuracy_m:invalid_type_or_range", "bearing_deg:invalid_type_or_range"}
        assert "has_vertical_accuracy" in group["observed_field_presence_or_type_differences"]
        with reference.open("a", encoding="utf-8") as target:
            target.write("{\\n")
        assert compare([probe], [reference], context)["status"] == "UNOBSERVED"

        write_capture(probe, False, [sample])
        host_file = root / "host-identity.json"
        host_values = {"pid": 8123, "kernel_uid": 1000, "system_server_pid": 4665}
        system_fields = {"identity": "system", "identity_scope": "independent_system_uid_client", "pid": 8123, "uid": 1000}
        system_context_fields = {**system_fields, "package_name": "android", "package_uid": 1000, "attribution_uid": 1000}

        def write_system(start_patch=None, host_patch=None, evidence_patch=None, identity_patch=None):
            write_capture(reference, True, [sample], {**system_fields, "required_uid": 1000, **(start_patch or {})},
                          {**system_context_fields, **(identity_patch or {})})
            host_file.write_text(json.dumps({**host_values, **(evidence_patch or {})}), encoding="utf-8")
            claim = {**host_values, "evidence_file": host_file.name,
                     "evidence_sha256": hashlib.sha256(host_file.read_bytes()).hexdigest(), **(host_patch or {})}
            write_context(1000, "android", "system", claim)
            return claim

        def exclude_system(reason):
            failed = compare([probe], [reference], context)
            assert failed["status"] == "UNOBSERVED" and failed["sources"][1]["gap"] == reason

        claim = write_system()
        actual = compare([probe], [reference], context)
        assert actual["status"] == "COMPARED" and actual["reference_identity_mode"] == "system"
        write_context(1000, "android", host_identity=claim)
        exclude_system("reference_context_invalid_or_incomplete")
        write_context(1000, "android", "system")
        exclude_system("reference_system_host_identity_invalid")
        write_system(host_patch={"system_server_pid": 8123}, evidence_patch={"system_server_pid": 8123})
        exclude_system("reference_system_host_identity_invalid")
        write_system()
        host_file.write_bytes(host_file.read_bytes() + b"\n")
        exclude_system("reference_system_host_evidence_hash_mismatch")
        write_system(evidence_patch={"kernel_uid": 2000})
        exclude_system("reference_system_host_evidence_identity_mismatch")
        write_system(start_patch={"pid": 8124})
        exclude_system("reference_system_host_identity_mismatch")
        write_system(start_patch={"identity": "shell"})
        exclude_system("reference_identity_mode_mismatch")
        write_system(identity_patch={"attribution_package_name": "com.android.shell"})
        exclude_system("reference_identity_not_verified")
        write_context(0, "android", "system", claim)
        exclude_system("reference_context_invalid_or_incomplete")
    print(json.dumps({"synthetic_checks": 20, "passed": True}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", type=Path, nargs="+", default=[])
    parser.add_argument("--reference", type=Path, nargs="+", default=[])
    parser.add_argument("--reference-context", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    elif not args.probe:
        parser.error("--probe is required unless --self-test is used")
    else:
        print(json.dumps(compare(args.probe, args.reference, args.reference_context), indent=2))


if __name__ == "__main__":
    main()
