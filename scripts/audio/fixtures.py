"""Small, deterministic synthetic PCM corpus; no application DSP imports."""

import argparse
from decimal import Decimal, ROUND_HALF_EVEN, localcontext
import hashlib
import json
from pathlib import Path
import struct

VERSION = "audipp-fixtures-1"
DEFAULT_SEED = 0x41554449
SAMPLE_RATE = 48000
FRAMES = 4096
RIGHTS = {
    "origin": "Synthetic mathematical signals; no third-party recordings",
    "license": "CC0-1.0",
    "permission": "Generate, use, modify and redistribute the generated signal files",
}


def sine_table():
    # Decimal and integer phase avoid platform libm differences in committed hashes.
    with localcontext() as context:
        context.prec = 50
        pi = Decimal("3.1415926535897932384626433832795028841971693993751")
        table = []
        for i in range(1024):
            x = 2 * pi * Decimal(i if i <= 512 else i - 1024) / 1024
            term = total = x
            for k in range(1, 40):
                term *= -x * x / ((2 * k) * (2 * k + 1))
                total += term
            table.append(int((total * 32767).to_integral_value(rounding=ROUND_HALF_EVEN)))
        return table


def signal(kind, bits, channels, seed):
    scale = 1 << (bits - 1)
    state = seed
    phase = 0
    table = sine_table() if kind == "sweep" else None
    samples = []
    for frame in range(FRAMES):
        for channel in range(channels):
            if kind == "prng":
                state = (1664525 * state + 1013904223) & 0xFFFFFFFF
                value = (state >> (32 - bits)) - scale
                if frame < 4:
                    value = (-scale, scale - 1, -1, 0)[frame]
            elif kind == "impulse":
                value = scale // 2 if frame == 32 * (channel + 1) else 0
            elif kind == "sweep":
                value = table[(phase * 1024 // SAMPLE_RATE) % 1024] * (scale // 32768)
            else:
                value = 0
            samples.append(value)
        phase += 20 + (20000 - 20) * frame // (FRAMES - 1)
    return samples


def wav_bytes(samples, bits, channels):
    width = bits // 8
    pcm = b"".join(value.to_bytes(width, "little", signed=True) for value in samples)
    # WAVE_FORMAT_EXTENSIBLE declares 5.1 order instead of leaving it ambiguous.
    if channels == 6:
        fmt = struct.pack("<HHIIHHHHI", 0xFFFE, channels, SAMPLE_RATE,
                          SAMPLE_RATE * channels * width, channels * width, bits,
                          22, bits, 0x3F)
        fmt += bytes.fromhex("0100000000001000800000aa00389b71")
    else:
        fmt = struct.pack("<HHIIHH", 1, channels, SAMPLE_RATE,
                          SAMPLE_RATE * channels * width, channels * width, bits)
    chunks = b"fmt " + struct.pack("<I", len(fmt)) + fmt
    chunks += b"data" + struct.pack("<I", len(pcm)) + pcm
    return b"RIFF" + struct.pack("<I", 4 + len(chunks)) + b"WAVE" + chunks


def generate(output, seed=DEFAULT_SEED):
    if type(seed) is not int or not 0 <= seed <= 0xFFFFFFFF:
        raise ValueError("seed must be an unsigned 32-bit integer")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    entries = []

    def save(name, content, bits, channels, expected, frames):
        (output / name).write_bytes(content)
        entries.append({
            "file": name, "sha256": hashlib.sha256(content).hexdigest(),
            "container": "WAV", "codec": "PCM_SIGNED_LE", "bit_depth": bits,
            "sample_rate_hz": SAMPLE_RATE, "channels": channels,
            "channel_order": ["FL", "FR", "FC", "LFE", "BL", "BR"] if channels == 6 else ["FL", "FR"],
            "frames": frames, "duration_seconds": frames / SAMPLE_RATE,
            "expected": expected, "rights": RIGHTS,
        })

    for bits in (16, 24, 32):
        for kind in ("prng", "impulse", "sweep", "silence"):
            content = wav_bytes(signal(kind, bits, 2, seed), bits, 2)
            save(f"{kind}-{bits}-stereo.wav", content, bits, 2, "DECODE_EXACT_PCM", FRAMES)
    content = wav_bytes(signal("impulse", 24, 6, seed), 24, 6)
    save("impulse-24-5point1.wav", content, 24, 6, "CHANNEL_IMPULSES_AT_32_TIMES_CHANNEL_INDEX_PLUS_ONE", FRAMES)
    content = wav_bytes(signal("prng", 16, 2, seed), 16, 2)
    save("truncated.wav", content[:83], 16, 2, "REJECT_TRUNCATED_DATA", FRAMES)
    save("invalid-header.wav", b"NOT_A_WAVE_FILE", 16, 2, "REJECT_INVALID_HEADER", 0)
    manifest = {"schema_version": 1, "generator_version": VERSION, "seed": seed,
                "fixtures": entries}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--seed", type=lambda value: int(value, 0), default=DEFAULT_SEED)
    args = parser.parse_args()
    generate(args.output, args.seed)
