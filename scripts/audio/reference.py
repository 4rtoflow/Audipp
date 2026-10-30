"""Offline Float64 oracle for captured, frame-major normalized PCM."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import sys

VERSION = "audipp-float64-1"


def read_pcm_wav(path):
    content = Path(path).read_bytes()
    if len(content) < 12 or content[:4] != b"RIFF" or content[8:12] != b"WAVE":
        raise ValueError("not a RIFF WAVE file")
    if int.from_bytes(content[4:8], "little") + 8 != len(content):
        raise ValueError("truncated or trailing RIFF data")
    chunks = {}
    offset = 12
    while offset < len(content):
        if offset + 8 > len(content):
            raise ValueError("truncated chunk header")
        name = content[offset:offset + 4]
        size = int.from_bytes(content[offset + 4:offset + 8], "little")
        start = offset + 8
        if start + size + size % 2 > len(content) or name in chunks:
            raise ValueError("truncated or duplicate chunk")
        chunks[name] = content[start:start + size]
        offset = start + size + size % 2
    fmt, data = chunks.get(b"fmt ", b""), chunks.get(b"data", b"")
    if len(fmt) < 16 or not data:
        raise ValueError("missing PCM format/data")
    tag, channels, rate, byte_rate, alignment, bits = struct.unpack("<HHIIHH", fmt[:16])
    if tag == 0xFFFE:
        if len(fmt) != 40 or fmt[16:18] != b"\x16\x00" or fmt[24:] != bytes.fromhex("0100000000001000800000aa00389b71"):
            raise ValueError("unsupported extensible format")
        if int.from_bytes(fmt[18:20], "little") != bits:
            raise ValueError("unsupported valid bit depth")
        mask = int.from_bytes(fmt[20:24], "little")
        if channels != 6 or mask != 0x3F:
            raise ValueError("unsupported extensible channel layout")
    elif tag != 1 or channels not in (1, 2):
        raise ValueError("unsupported PCM format or channel layout")
    if bits not in (16, 24, 32) or not 1 <= channels <= 6 or rate <= 0:
        raise ValueError("unsupported PCM format")
    width = bits // 8
    if alignment != channels * width or byte_rate != rate * alignment or len(data) % alignment:
        raise ValueError("inconsistent PCM frame format")
    samples = [int.from_bytes(data[i:i + width], "little", signed=True) / (1 << (bits - 1))
               for i in range(0, len(data), width)]
    return {"sample_rate_hz": rate, "bit_depth": bits, "channels": channels}, [
        samples[i:i + channels] for i in range(0, len(samples), channels)]


def pcm_frames(frames):
    if sys.float_info.mant_dig != 53:
        raise ValueError("IEEE-754 Float64 is required")
    if not frames or not frames[0] or len(frames[0]) > 6:
        raise ValueError("expected nonempty PCM with 1..6 channels")
    channels = len(frames[0])
    for frame in frames:
        if len(frame) != channels or any(type(x) not in (int, float) or not math.isfinite(x) for x in frame):
            raise ValueError("PCM must be rectangular and finite")
    return [[float(x) for x in frame] for frame in frames]


def fir_reference(frames, taps):
    frames = pcm_frames(frames)
    if not taps or any(type(x) not in (int, float) or not math.isfinite(x) for x in taps):
        raise ValueError("expected finite FIR taps")
    # Direct convolution intentionally shares no partitioning/FFT with the native DSP.
    return [[math.fsum(frames[n - k][ch] * float(tap)
                       for k, tap in enumerate(taps) if 0 <= n - k < len(frames))
             for ch in range(len(frames[0]))]
            for n in range(len(frames) + len(taps) - 1)]


def compare(reference, actual, absolute_tolerance):
    reference, actual = pcm_frames(reference), pcm_frames(actual)
    if type(absolute_tolerance) not in (int, float) or not math.isfinite(absolute_tolerance) or absolute_tolerance < 0:
        raise ValueError("tolerance must be finite and nonnegative")
    if len(reference) != len(actual) or len(reference[0]) != len(actual[0]):
        return {"outcome": "FAIL", "reason": "FRAME_OR_CHANNEL_COUNT_MISMATCH"}
    errors = [abs(expected - observed) for ref, got in zip(reference, actual)
              for expected, observed in zip(ref, got)]
    if not all(math.isfinite(error) for error in errors):
        raise ValueError("nonfinite comparison error")
    maximum = max(errors)
    # Scaling first avoids overflow when accumulating large finite errors.
    rms = maximum * math.sqrt(math.fsum((x / maximum) ** 2 for x in errors) / len(errors)) if maximum else 0.0
    return {"outcome": "PASS" if maximum <= absolute_tolerance else "FAIL",
            "absolute_tolerance": absolute_tolerance, "max_absolute_error": maximum,
            "rms_error": rms, "sample_count": len(errors), "reference_version": VERSION}


def compare_files(source, actual, taps, tolerance):
    source_format, frames = read_pcm_wav(source)
    actual_format, captured = read_pcm_wav(actual)
    if source_format["sample_rate_hz"] != actual_format["sample_rate_hz"]:
        raise ValueError("sample rate mismatch; SRC reference is not implemented")
    result = compare(fir_reference(frames, taps), captured, tolerance)
    return {**result, "source_sha256": hashlib.sha256(Path(source).read_bytes()).hexdigest(),
            "actual_sha256": hashlib.sha256(Path(actual).read_bytes()).hexdigest(),
            "source_format": source_format, "actual_format": actual_format,
            "reference_version": VERSION, "fir_taps": taps}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("actual", type=Path)
    parser.add_argument("--taps", type=float, nargs="+", default=[1.0])
    parser.add_argument("--absolute-tolerance", type=float, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = compare_files(args.source, args.actual, args.taps, args.absolute_tolerance)
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    raise SystemExit(0 if report["outcome"] == "PASS" else 1)
