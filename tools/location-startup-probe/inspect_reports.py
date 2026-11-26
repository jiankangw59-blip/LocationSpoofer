"""Inspect one or more Location Startup Probe JSONL files locally.

Usage: python tools/location-startup-probe/inspect_reports.py report.jsonl [more.jsonl ...]
       python tools/location-startup-probe/inspect_reports.py --self-test

Exit 1 means a report has an integrity issue. Coverage is reported separately.
"""

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import sys


MS = 1_000_000
DURATION_NS = 30_000 * MS
BURST_STEP_NS = 50 * MS
BURST_SLOTS = 40
MODE_ACTION = "android.location.MODE_CHANGED"
RECEIVED = "received_elapsed_realtime_ns"
WRITTEN = "serialization_elapsed_realtime_ns"
DEADLINE = "deadline_elapsed_realtime_ns"
BEGIN = "sdk_begin_elapsed_ns"
END = "sdk_end_elapsed_ns"
WINDOWS = (("0_200_ms", 0, 200 * MS), ("200_ms_2_s", 200 * MS, 2000 * MS),
           ("2_30_s", 2000 * MS, DURATION_NS))
METRICS = ("cache_attempts", "cache_empty", "cache_errors", "cache", "callback", "current_location")


def integer(value):
    return type(value) is int and value >= 0


def read_jsonl(source, issue):
    rows = []
    for line, raw in enumerate(source, 1):
        try:
            encoding = "utf-8-sig" if line == 1 else "utf-8"
            record = json.loads(raw.decode(encoding))
        except (UnicodeError, ValueError):
            issue("invalid_json_line", line)
            continue
        if not isinstance(record, dict):
            issue("record_is_not_object", line)
            continue
        rows.append((line, record))
    return rows


def valid_rows(rows, issue):
    start_rows = [(line, row) for line, row in rows if row.get("event") == "session_start"]
    end_rows = [(line, row) for line, row in rows if row.get("event") == "session_end"]
    if len(start_rows) != 1 or start_rows[0][0] != 1:
        issue("expected_one_session_start_on_first_line")
    if len(end_rows) != 1:
        issue("expected_one_session_end")

    start = start_rows[0][1] if start_rows else {}
    end_line, end = end_rows[0] if end_rows else (None, {})
    session = start.get("session_id")
    origin = start.get(RECEIVED)
    deadline = start.get(DEADLINE)
    duration_valid = (integer(origin) and integer(deadline) and deadline - origin == DURATION_NS
                      and start.get("duration_ms") == 30_000)
    if not duration_valid:
        issue("invalid_30_second_session_window", start_rows[0][0] if start_rows else None)

    accepted = []
    previous_written = None
    for line, row in rows:
        valid = True
        if not isinstance(session, str) or not session or row.get("session_id") != session:
            issue("missing_or_different_session_id", line)
            valid = False
        sequence = row.get("sequence")
        if not integer(sequence) or sequence != line:
            issue("sequence_not_contiguous_from_one", line)
            valid = False
        event = row.get("event")
        if not isinstance(event, str) or not event:
            issue("missing_event", line)
            valid = False
        observed, written = row.get(RECEIVED), row.get(WRITTEN)
        if not integer(observed) or not integer(written):
            issue("invalid_monotonic_clock", line)
            valid = False
        else:
            if written < observed:
                issue("write_precedes_observation", line)
                valid = False
            if previous_written is not None and written < previous_written:
                issue("write_clock_retreats", line)
                valid = False
            previous_written = written
        if end_line is not None and line > end_line:
            issue("location_after_session_end" if event == "location" else "event_after_session_end", line)
            valid = False
        if event == "location":
            if row.get("origin") not in ("cache", "callback", "current_location"):
                issue("unknown_location_origin", line)
                valid = False
            if duration_valid and integer(observed) and not origin <= observed < deadline:
                issue("location_outside_session_window", line)
                valid = False
            if (integer(observed) and integer(end.get(RECEIVED)) and observed > end[RECEIVED]):
                issue("location_observed_after_session_end", line)
                valid = False
        if valid:
            accepted.append((line, row))
    return start, end, accepted


def cache_metrics(row):
    event, origin = row["event"], row.get("origin")
    if event == "location" and origin == "cache":
        return ("cache_attempts", "cache")
    if event == "cache_empty":
        return ("cache_attempts", "cache_empty")
    if event == "sdk_error" and row.get("operation") == "last_known":
        return ("cache_attempts", "cache_errors")
    if event == "location":
        return (origin,)
    return ()


def elapsed_windows(rows, anchor_ns):
    if not integer(anchor_ns):
        return None
    output = {}
    for label, lower, upper in WINDOWS:
        counts = Counter(
            metric for _, row in rows if lower <= row[RECEIVED] - anchor_ns < upper
            for metric in cache_metrics(row)
        )
        output[label] = {metric: counts[metric] for metric in METRICS}
    return output


def elapsed_first(rows, anchor_ns):
    first = {}
    for label in ("any_location", "cache", "callback", "current_location"):
        first[label] = None
        if not integer(anchor_ns):
            continue
        matching = [
            (line, row) for line, row in rows
            if row["event"] == "location" and row[RECEIVED] >= anchor_ns
            and (label == "any_location" or row["origin"] == label)
        ]
        if matching:
            line, row = min(matching, key=lambda pair: (pair[1][RECEIVED], pair[0]))
            first[label] = {"delay_ns": row[RECEIVED] - anchor_ns, "line": line, "origin": row["origin"]}
    return first


def sdk_timings(rows, issue):
    timings = defaultdict(list)
    for line, row in rows:
        if BEGIN not in row and END not in row:
            continue
        begin, end = row.get(BEGIN), row.get(END)
        if not (integer(begin) and integer(end) and begin <= end <= row[WRITTEN]):
            issue("invalid_sdk_timing", line)
            continue
        operation = row.get("operation")
        if not isinstance(operation, str) or not operation:
            operation = "last_known" if "cache_attempts" in cache_metrics(row) else row["event"]
        timings[operation].append((end - begin, line))
    return {
        operation: {
            "count": len(attempts), "min_duration_ns": min(attempts)[0],
            "max_duration_ns": max(attempts)[0], "total_duration_ns": sum(duration for duration, _ in attempts),
            "longest_attempt_line": max(attempts)[1],
        }
        for operation, attempts in sorted(timings.items())
    }


def burst_slots(rows, issue):
    known = {
        anchor: {"origin_ns": None, "dispatched": set(), "shared": set(), "missed": set()}
        for anchor in ("attach", "session_start", "window_focus")
    }
    dispatched_timestamps = set()
    provider_misses = defaultdict(list)
    reads_after_slot = defaultdict(list)
    for line, row in rows:
        event, anchor = row["event"], row.get("burst_anchor")
        if event == "burst_provider_missed" or row.get("slot_completed_in_window") is False:
            slot, provider = row.get("slot"), row.get("requested_provider")
            if (not isinstance(anchor, str) or anchor not in known or not integer(slot)
                    or slot not in known[anchor]["dispatched"] or not isinstance(provider, str)):
                issue("provider_result_without_matching_burst_slot", line)
                continue
            destination = provider_misses if event == "burst_provider_missed" else reads_after_slot
            destination[anchor].append({"slot": slot, "requested_provider": provider, "line": line})
            continue
        if event not in ("burst_slot", "burst_slot_shared", "burst_slots_missed"):
            continue
        if not isinstance(anchor, str) or anchor not in known:
            issue("unknown_burst_anchor", line)
            continue
        anchor_state = known[anchor]
        origin = row.get("burst_origin_elapsed_ns")
        if (not integer(origin) or
                (anchor_state["origin_ns"] is not None and origin != anchor_state["origin_ns"])):
            issue("invalid_or_changed_burst_origin", line)
            continue
        anchor_state["origin_ns"] = origin
        if event == "burst_slots_missed":
            slot, count, key = row.get("first_slot"), row.get("count"), "missed"
            nominal = row.get("first_nominal_elapsed_ns")
        else:
            slot, count = row.get("slot"), 1
            key = "shared" if event == "burst_slot_shared" else "dispatched"
            nominal = row.get("nominal_elapsed_ns")
        if not (integer(slot) and integer(count) and count > 0 and slot + count <= BURST_SLOTS
                and nominal == origin + slot * BURST_STEP_NS):
            issue("invalid_burst_slot_range_or_time", line)
            continue
        if key == "shared":
            shared = row.get("shared_sample_elapsed_ns")
            if not (integer(shared) and nominal <= shared < nominal + BURST_STEP_NS
                    and shared in dispatched_timestamps):
                issue("shared_slot_without_matching_dispatch", line)
                continue
        slots = set(range(slot, slot + count))
        if slots & (anchor_state["dispatched"] | anchor_state["shared"] | anchor_state["missed"]):
            issue("duplicate_or_conflicting_burst_slot", line)
            continue
        anchor_state[key].update(slots)
        if key == "dispatched":
            dispatched_timestamps.add(row[RECEIVED])
    return {
        anchor: {
            "anchor_elapsed_ns": values["origin_ns"],
            "dispatched_slots": sorted(values["dispatched"]),
            "shared_slots": sorted(values["shared"]),
            "recorded_missed_slots": sorted(values["missed"]),
            "provider_reads_skipped_after_slot": provider_misses[anchor],
            "provider_reads_completed_after_slot": reads_after_slot[anchor],
            "unaccounted_slots": (
                sorted(set(range(BURST_SLOTS)) - values["dispatched"] - values["shared"] - values["missed"])
                if values["origin_ns"] is not None else None
            ),
        }
        for anchor, values in known.items()
    }


def broadcast_observations(rows):
    broadcasts = []
    mode_broadcasts = []
    transitions = []
    previous = None
    for line, row in sorted(rows, key=lambda pair: (pair[1][RECEIVED], pair[0])):
        event = row["event"]
        if event == "location_broadcast":
            broadcasts.append(line)
            if row.get("action") == MODE_ACTION:
                mode_broadcasts.append(line)
            enabled = row.get("location_enabled_extra")
        elif event == "provider_state":
            enabled = row.get("location_enabled")
        else:
            continue
        if type(enabled) is not bool:
            continue
        state = {"line": line, "event": event, "elapsed_ns": row[RECEIVED], "enabled": enabled}
        if previous and previous["enabled"] is False and enabled is True:
            transitions.append({"off": previous, "on": state})
        previous = state
    return broadcasts, mode_broadcasts, transitions


def status(value, reasons=()):
    return {"status": value, "reasons": list(reasons)}


def inspect_stream(source, path, issues=None):
    if issues is None:
        issues = []

    def issue(code, line=None):
        issues.append({"code": code, "line": line})

    rows = read_jsonl(source, issue)
    start, end, accepted = valid_rows(rows, issue)
    timings = sdk_timings(accepted, issue)
    origin, deadline = start.get(RECEIVED), start.get(DEADLINE)
    end_elapsed = end.get(RECEIVED)
    observed = [
        (line, row) for line, row in accepted
        if integer(origin) and integer(deadline) and origin <= row[RECEIVED] < min(deadline, origin + DURATION_NS)
        and (not integer(end_elapsed) or row[RECEIVED] <= end_elapsed)
    ]
    slots = burst_slots(observed, issue)
    focus = min(
        (row[RECEIVED] for _, row in observed if row["event"] == "activity_window_focus" and row.get("focused") is True),
        default=None,
    )
    started_at_attach = start.get("trigger") == "application_attach"
    attach = start.get("attach_entry_elapsed_ns") if started_at_attach else None
    first = elapsed_first(observed, origin)
    broadcast_lines, mode_broadcast_lines, transitions = broadcast_observations(observed)
    total_samples = Counter(metric for _, row in observed for metric in cache_metrics(row))

    errors = Counter()
    for _, row in accepted:
        if row["event"] not in ("sdk_error", "error"):
            continue
        operation = row.get("operation", row.get("phase"))
        if not isinstance(operation, str):
            operation = "unspecified"
        errors[operation] += 1
    reasons = []
    if issues:
        reasons.append("integrity_issues")
    if end.get("reason") != "duration_elapsed" or not (integer(end_elapsed) and integer(deadline) and end_elapsed >= deadline):
        reasons.append("session_incomplete_or_ended_early")
    if start.get("fine_permission") is not True and start.get("coarse_permission") is not True:
        reasons.append("location_permission_missing_or_unconfirmed")
    if errors or (integer(end.get("errors")) and end["errors"] > 0):
        reasons.append("recorder_or_sdk_errors")
    checkpoints = [row for _, row in accepted if row["event"] == "registration_checkpoint"]
    listener_errors = any(not integer(row.get("listeners_ready")) or row["listeners_ready"] == 0 for row in checkpoints)
    callbacks = ["no_valid_callback"] if first["callback"] is None else []
    if listener_errors and not any(row["event"] == "listener_registration" for _, row in accepted):
        callbacks.append("listener_registration_unconfirmed")
    off_to_on = status("NOT_COVERED", ("off_to_on_not_observed",))
    if transitions:
        observed_after = any(
            row["event"] == "location" and row["origin"] == "callback"
            and any(row[RECEIVED] >= change["on"]["elapsed_ns"] for change in transitions)
            for _, row in observed
        )
        off_on_reasons = reasons + ([] if observed_after else ["no_valid_callback_after_off_to_on"])
        off_to_on = status("INCONCLUSIVE", off_on_reasons) if off_on_reasons else status("OBSERVED")
    startup_reasons = reasons + ([] if first["any_location"] else ["no_valid_location_sample"])
    coverage = {
        "startup_data": status("INCONCLUSIVE", startup_reasons) if startup_reasons else status("OBSERVED"),
        "callback_delivery": status("INCONCLUSIVE", reasons + callbacks) if reasons or callbacks else status("OBSERVED"),
        "location_off_to_on": off_to_on,
        "window_focus": status("OBSERVED") if focus is not None else status("NOT_COVERED", ("first_focus_not_observed",)),
    }
    return {
        "file": str(path), "session_id": start.get("session_id") if isinstance(start.get("session_id"), str) else None,
        "integrity": {"status": "INVALID" if issues else "COMPLETE", "issues": issues, "parsed_records": len(rows)},
        "coverage": coverage, "session_end_reason": end.get("reason"),
        "sample_time_basis": "received_elapsed_realtime_ns; lower bounds inclusive, upper bounds exclusive",
        "sample_totals": {metric: total_samples[metric] for metric in METRICS},
        "samples_at_session_start": elapsed_windows(observed, origin),
        "samples_by_anchor": {
            "attach": {"anchor_elapsed_ns": attach, "windows": elapsed_windows(observed, attach)},
            "window_focus": {"anchor_elapsed_ns": focus, "windows": elapsed_windows(observed, focus)},
        },
        "first_observations_from_session_start": first,
        "sdk_intervals": timings, "burst_slots": slots,
        "location_broadcasts": {
            "observed": bool(broadcast_lines), "lines": broadcast_lines,
            "mode_changed_observed": bool(mode_broadcast_lines), "mode_changed_lines": mode_broadcast_lines,
        },
        "off_to_on_transitions": transitions, "error_operations": dict(errors),
    }


def inspect(path):
    try:
        with path.open("rb") as source:
            return inspect_stream(source, path)
    except OSError:
        return inspect_stream((), path, [{"code": "file_unreadable", "line": None}])


def self_test():
    import io

    zero = 9_000_000_000

    def record(event, time, **fields):
        return {
            "event": event, "session_id": "synthetic.jsonl",
            RECEIVED: zero + time * MS, WRITTEN: zero + time * MS,
            **fields,
        }

    def start():
        return record("session_start", 0, trigger="application_attach", duration_ms=30_000,
                      deadline_elapsed_realtime_ns=zero + DURATION_NS, attach_entry_elapsed_ns=zero,
                      fine_permission=True, coarse_permission=True)

    def end():
        return record("session_end", 30_000, reason="duration_elapsed", errors=0)

    def check(name, data, verify, suffix=b""):
        source = io.BytesIO(b"".join(
            (json.dumps(dict(row, sequence=row.get("sequence", index))) + "\n").encode()
            for index, row in enumerate(data, 1)
        ) + suffix)
        result = inspect_stream(source, name)
        verify(result)
        print(f"ok {name}", file=sys.stderr)

    def codes(result):
        return {error["code"] for error in result["integrity"]["issues"]}

    def complete(result):
        assert result["integrity"]["status"] == "COMPLETE"
        assert result["coverage"]["startup_data"]["status"] == "OBSERVED"
        assert result["coverage"]["callback_delivery"]["status"] == "OBSERVED"
        assert result["coverage"]["location_off_to_on"]["status"] == "OBSERVED"
        assert result["first_observations_from_session_start"]["any_location"]["delay_ns"] == 199 * MS
        assert result["first_observations_from_session_start"]["callback"]["delay_ns"] == 2_000 * MS
        at = result["samples_by_anchor"]
        assert at["attach"]["windows"]["0_200_ms"]["cache"] == 1
        assert at["window_focus"]["windows"]["0_200_ms"]["cache"] == 1
        assert at["attach"]["windows"]["2_30_s"]["callback"] == 1
        assert at["attach"]["windows"]["2_30_s"]["current_location"] == 1
        assert result["sdk_intervals"]["last_known"]["max_duration_ns"] == MS
        assert result["burst_slots"]["attach"]["recorded_missed_slots"] == [1, 2]
        assert result["location_broadcasts"]["mode_changed_observed"] is True
        output = json.dumps(result)
        assert all(field not in output for field in ("latitude", "longitude", "is_mock"))

    check("complete-fixtures", [
        start(), record("provider_state", 10, location_enabled=False),
        record("burst_slot", 20, burst_anchor="attach", burst_origin_elapsed_ns=zero,
               nominal_elapsed_ns=zero, slot=0),
        record("burst_slots_missed", 150, burst_anchor="attach", burst_origin_elapsed_ns=zero,
               first_nominal_elapsed_ns=zero + 50 * MS, first_slot=1, count=2),
        record("activity_window_focus", 190, focused=True),
        record("location", 199, origin="cache", latitude=12.3456, longitude=42.1234, is_mock=False,
               sdk_begin_elapsed_ns=zero + 198 * MS, sdk_end_elapsed_ns=zero + 199 * MS),
        record("location_broadcast", 200, location_enabled_extra=True, action=MODE_ACTION),
        record("location", 2_000, origin="callback"), record("location", 2_001, origin="current_location"), end(),
    ], complete)

    def zero_samples(result):
        assert result["integrity"]["status"] == "COMPLETE"
        assert result["coverage"]["startup_data"]["status"] == "INCONCLUSIVE"
        assert result["coverage"]["callback_delivery"]["status"] == "INCONCLUSIVE"
        assert result["coverage"]["location_off_to_on"]["status"] == "NOT_COVERED"
        assert result["samples_at_session_start"]["0_200_ms"]["cache_attempts"] == 1
        assert result["samples_at_session_start"]["0_200_ms"]["cache"] == 0

    check("zero-location-samples", [
        start(), record("provider_state", 1, location_enabled=False), record("cache_empty", 7, origin="cache"), end(),
    ], zero_samples)

    def truncated(result):
        assert {"invalid_json_line", "expected_one_session_end"} <= codes(result)
        assert result["coverage"]["callback_delivery"]["status"] == "INCONCLUSIVE"

    check("truncated-file", [start(), record("location", 140, origin="callback")], truncated, b'{"event":')

    def deadline(result):
        assert {"location_outside_session_window", "location_after_session_end"} <= codes(result)
        assert result["first_observations_from_session_start"]["callback"] is None

    check("strict-deadline-and-post-end", [
        start(), record("location", 30_000, origin="callback"), end(), record("location", 30_001, origin="callback"),
    ], deadline)

    def repeated(result):
        assert {"sequence_not_contiguous_from_one", "missing_or_different_session_id", "write_clock_retreats"} <= codes(result)

    check("duplicate-sequence-mixed-session-and-clock", [
        start(), record("location", 10, origin="callback", sequence=1),
        record("cache_empty", 5, session_id="other.jsonl"), end(),
    ], repeated)

    def incomplete(result):
        assert result["coverage"]["callback_delivery"]["status"] == "INCONCLUSIVE"
        assert {"recorder_or_sdk_errors", "session_incomplete_or_ended_early"} <= set(
            result["coverage"]["callback_delivery"]["reasons"])
        assert result["samples_at_session_start"]["0_200_ms"]["cache_errors"] == 1

    check("early-end-with-sdk-error", [
        start(), record("sdk_error", 1, operation="last_known", exception_class="java.lang.SecurityException"),
        record("location", 2, origin="callback"), record("session_end", 3, reason="manual_stop", errors=1),
    ], incomplete)
    def shared_slot(result):
        assert result["integrity"]["status"] == "COMPLETE"
        assert result["burst_slots"]["attach"]["dispatched_slots"] == [0]
        assert result["burst_slots"]["window_focus"]["shared_slots"] == [0]
        assert result["sample_totals"]["cache_attempts"] == 1
        attach = result["burst_slots"]["attach"]
        assert attach["provider_reads_skipped_after_slot"][0]["requested_provider"] == "network"
        assert attach["provider_reads_completed_after_slot"][0]["requested_provider"] == "gps"

    check("shared-slot-with-single-cache-attempt", [
        start(), record("activity_window_focus", 10, focused=True),
        record("burst_slot", 20, burst_anchor="attach", burst_origin_elapsed_ns=zero,
               nominal_elapsed_ns=zero, slot=0),
        record("cache_empty", 51, origin="cache", burst_anchor="attach", slot=0, requested_provider="gps",
               slot_completed_in_window=False),
        record("burst_provider_missed", 52, burst_anchor="attach", slot=0, requested_provider="network",
               nominal_elapsed_ns=zero),
        record("burst_slot_shared", 53, burst_anchor="window_focus", burst_origin_elapsed_ns=zero + 10 * MS,
               nominal_elapsed_ns=zero + 10 * MS, shared_sample_elapsed_ns=zero + 20 * MS, slot=0),
        end(),
    ], shared_slot)

    def warm_anchor(result):
        assert result["integrity"]["status"] == "COMPLETE"
        assert result["samples_by_anchor"]["attach"]["windows"] is None
        assert result["burst_slots"]["attach"]["anchor_elapsed_ns"] is None
        assert result["burst_slots"]["session_start"]["dispatched_slots"] == [0]
        assert result["samples_at_session_start"]["0_200_ms"]["cache"] == 1

    check("warm-session-start-anchor", [
        dict(start(), trigger="warm_foreground", attach_entry_elapsed_ns=zero - 500 * MS),
        record("burst_slot", 14, burst_anchor="session_start", burst_origin_elapsed_ns=zero,
               nominal_elapsed_ns=zero, slot=0),
        record("location", 20, origin="cache", burst_anchor="session_start", slot=0), end(),
    ], warm_anchor)

    print(json.dumps({"synthetic_checks": 8, "passed": True}))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("reports", nargs="*", type=Path)
    parser.add_argument("--self-test", action="store_true", help="run offline synthetic integrity and coverage checks")
    args = parser.parse_args()
    if args.self_test and not args.reports:
        return self_test()
    if not args.reports or args.self_test:
        parser.error("provide one or more reports, or --self-test")
    reports = [inspect(path) for path in args.reports]
    print(json.dumps({"reports": reports}, indent=2, ensure_ascii=True))
    return int(any(report["integrity"]["status"] == "INVALID" for report in reports))


if __name__ == "__main__":
    sys.exit(main())
