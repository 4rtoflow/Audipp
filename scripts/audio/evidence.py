"""Validate versioned test evidence before accepting a recorded outcome."""

import argparse
from datetime import datetime
import json
import math
from pathlib import Path
import re

OUTCOMES = {"PASS", "FAIL", "BLOCKED", "NOT_RUN", "NOT_APPLICABLE"}
MATRIX_FIELDS = {
    "device_vendor", "model", "Android_version", "build_fingerprint_hash", "API_level",
    "ABI", "audio_output_type", "DAC_vendor/product", "USB_VID/PID", "codec",
    "sampleRate", "bitDepth", "playback_mode", "DSP_state",
}
FIELDS = {
    "schema_version", "session_test_id", "test_ids", "requirement_ids", "app_commit",
    "recorded_at_utc", "outcome", "reason", "known_limitations", "device",
    "fixture", "capture_point", "evidence_type", "instrument", "settings", "result",
}


def nonblank(value):
    return isinstance(value, str) and bool(value.strip())


def digest(value, length):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{" + str(length) + r"}", value) is not None


def validate(record):
    try:
        json.dumps(record, allow_nan=False)
    except (TypeError, ValueError) as error:
        raise ValueError("evidence must contain only finite JSON values") from error
    if not isinstance(record, dict) or set(record) != FIELDS:
        raise ValueError("missing or unknown evidence fields")
    if type(record["schema_version"]) is not int or record["schema_version"] != 1:
        raise ValueError("unsupported evidence version")
    if record["outcome"] not in OUTCOMES:
        raise ValueError("unknown outcome")
    for field in ("session_test_id", "reason", "known_limitations"):
        if not nonblank(record[field]):
            raise ValueError(f"{field} must be explicit")
    for field in ("test_ids", "requirement_ids"):
        if not isinstance(record[field], list) or not record[field] or not all(nonblank(x) for x in record[field]):
            raise ValueError(f"{field} must identify the tested scope")
    timestamp = record["recorded_at_utc"]
    if not isinstance(timestamp, str) or not timestamp.endswith("Z"):
        raise ValueError("recorded_at_utc must be UTC with Z suffix")
    datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    if not isinstance(record["device"], dict) or set(record["device"]) != MATRIX_FIELDS:
        raise ValueError("device matrix fields must be present; unknown values use null")
    if record["evidence_type"] not in {"OFFLINE_REFERENCE", "APP_OBSERVED", "EXTERNAL_DIGITAL", "EXTERNAL_ANALOG", "UNKNOWN"}:
        raise ValueError("unknown evidence type")
    if record["outcome"] in {"PASS", "FAIL"}:
        if not digest(record["app_commit"], 40) or not nonblank(record["capture_point"]):
            raise ValueError("executed measurements require full app commit and capture point")
        instrument = record["instrument"]
        if not isinstance(instrument, dict) or set(instrument) != {"name", "version"} or not all(nonblank(x) for x in instrument.values()):
            raise ValueError("executed measurements require instrument name and version")
        fixture = record["fixture"]
        if not isinstance(fixture, dict) or set(fixture) != {"sha256", "codec", "sample_rate_hz", "bit_depth", "channels"}:
            raise ValueError("fixture identity and format are required")
        if not digest(fixture["sha256"], 64) or not nonblank(fixture["codec"]):
            raise ValueError("invalid fixture identity")
        if any(type(fixture[key]) is not int or fixture[key] <= 0 for key in ("sample_rate_hz", "bit_depth", "channels")):
            raise ValueError("fixture format must be known")
        if not isinstance(record["settings"], dict) or not record["settings"]:
            raise ValueError("measurement settings must be recorded")
        result = record["result"]
        if not isinstance(result, dict) or set(result) != {"artifact_sha256", "metrics"} or not digest(result["artifact_sha256"], 64):
            raise ValueError("capture/report artifact hash is required")
        if not isinstance(result["metrics"], dict) or not result["metrics"]:
            raise ValueError("measurement metrics must be recorded")
        for metric in result["metrics"].values():
            if not isinstance(metric, dict) or set(metric) != {"value", "minimum", "maximum", "unit"}:
                raise ValueError("metrics need value, bounds and unit")
            if not nonblank(metric["unit"]) or type(metric["value"]) not in (int, float) or not math.isfinite(metric["value"]):
                raise ValueError("metric value must be finite")
            bounds = [metric["minimum"], metric["maximum"]]
            if all(x is None for x in bounds) or any(x is not None and (type(x) not in (int, float) or not math.isfinite(x)) for x in bounds):
                raise ValueError("at least one finite bound is required")
            if all(x is not None for x in bounds) and bounds[0] > bounds[1]:
                raise ValueError("metric bounds are reversed")
        passed = all((m["minimum"] is None or m["value"] >= m["minimum"]) and
                     (m["maximum"] is None or m["value"] <= m["maximum"])
                     for m in result["metrics"].values())
        if (record["outcome"] == "PASS") != passed:
            raise ValueError("outcome disagrees with metric bounds")
        if record["evidence_type"] == "UNKNOWN":
            raise ValueError("executed measurements need evidence type")
        if record["evidence_type"] != "OFFLINE_REFERENCE":
            for key in ("device_vendor", "model", "Android_version", "build_fingerprint_hash", "ABI", "audio_output_type", "playback_mode", "DSP_state"):
                if not nonblank(record["device"][key]):
                    raise ValueError(f"device measurement requires {key}")
            if not digest(record["device"]["build_fingerprint_hash"], 64):
                raise ValueError("build fingerprint must be hashed")
            if type(record["device"]["API_level"]) is not int or record["device"]["API_level"] < 23:
                raise ValueError("device API level must be known")
    elif record["result"] is not None:
        raise ValueError("unexecuted/inapplicable results must be null")
    return record


def save(record, path):
    validate(record)
    Path(path).write_text(json.dumps(record, indent=2, allow_nan=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("record", type=Path)
    args = parser.parse_args()
    validate(json.loads(args.record.read_text(encoding="utf-8")))
    print("Valid evidence record")
