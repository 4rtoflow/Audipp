import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
import wave

from scripts.audio.evidence import MATRIX_FIELDS, save, validate
from scripts.audio.fixtures import DEFAULT_SEED, FRAMES, generate
from scripts.audio.reference import compare, compare_files, fir_reference, read_pcm_wav

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "tests/audio"


class FixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.output = Path(cls.directory.name)
        cls.manifest = generate(cls.output)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_committed_manifest_and_every_generated_hash(self):
        self.assertEqual(json.loads((FIXTURES / "manifest.json").read_text()), self.manifest)
        for entry in self.manifest["fixtures"]:
            with self.subTest(file=entry["file"]):
                self.assertEqual(entry["sha256"], hashlib.sha256((self.output / entry["file"]).read_bytes()).hexdigest())
                self.assertEqual("CC0-1.0", entry["rights"]["license"])

    def test_same_seed_regenerates_identical_files(self):
        with tempfile.TemporaryDirectory() as output:
            self.assertEqual(self.manifest, generate(output))
            for path in self.output.iterdir():
                self.assertEqual(path.read_bytes(), (Path(output) / path.name).read_bytes())

    def test_seed_changes_only_prng_signals(self):
        with tempfile.TemporaryDirectory() as output:
            other = generate(output, DEFAULT_SEED + 1)
            before = {entry["file"]: entry["sha256"] for entry in self.manifest["fixtures"]}
            for entry in other["fixtures"]:
                with self.subTest(file=entry["file"]):
                    changed = entry["file"].startswith("prng") or entry["file"] == "truncated.wav"
                    self.assertEqual(changed, before[entry["file"]] != entry["sha256"])

    def test_integer_boundaries_preserve_all_16_24_32_bits(self):
        for bits in (16, 24, 32):
            fmt, frames = read_pcm_wav(self.output / f"prng-{bits}-stereo.wav")
            self.assertEqual(bits, fmt["bit_depth"])
            scale = 1 << (bits - 1)
            self.assertEqual([-1.0, (scale - 1) / scale, -1 / scale, 0.0], [frame[0] for frame in frames[:4]])
            self.assertEqual(FRAMES, len(frames))

    def test_generated_stereo_is_readable_by_independent_stdlib_decoder(self):
        for bits in (16, 24, 32):
            with wave.open(str(self.output / f"prng-{bits}-stereo.wav"), "rb") as reader:
                self.assertEqual((2, bits // 8, 48000, FRAMES),
                                 (reader.getnchannels(), reader.getsampwidth(), reader.getframerate(), reader.getnframes()))
                self.assertEqual(-(1 << (bits - 1)), int.from_bytes(reader.readframes(1)[:bits // 8], "little", signed=True))

    def test_channel_order_and_no_cross_channel_leakage(self):
        fmt, frames = read_pcm_wav(self.output / "impulse-24-5point1.wav")
        self.assertEqual(6, fmt["channels"])
        self.assertEqual([[i for i, frame in enumerate(frames) if frame[ch]] for ch in range(6)],
                         [[32 * (ch + 1)] for ch in range(6)])

    def test_silence_and_sweep_are_distinct_valid_signals(self):
        _, silence = read_pcm_wav(self.output / "silence-16-stereo.wav")
        _, sweep = read_pcm_wav(self.output / "sweep-16-stereo.wav")
        self.assertTrue(all(frame == [0.0, 0.0] for frame in silence))
        self.assertGreater(max(frame[0] for frame in sweep), 0.99)
        self.assertLess(min(frame[0] for frame in sweep), -0.99)

    def test_corrupt_inputs_rejected(self):
        for name in ("truncated.wav", "invalid-header.wav"):
            with self.subTest(file=name), self.assertRaises(ValueError):
                read_pcm_wav(self.output / name)

    def test_invalid_seed_rejected(self):
        for seed in (-1, 2**32, True, "1"):
            with self.subTest(seed=seed), self.assertRaises(ValueError):
                generate(self.output, seed)

    def test_fixture_to_reference_integration_and_rate_mismatch(self):
        path = self.output / "prng-32-stereo.wav"
        self.assertEqual("PASS", compare_files(path, path, [1.0], 0)["outcome"])
        content = bytearray(path.read_bytes())
        content[24:32] = struct.pack("<II", 44100, 44100 * 8)
        with tempfile.TemporaryDirectory() as output:
            actual = Path(output) / "actual.wav"
            actual.write_bytes(content)
            with self.assertRaisesRegex(ValueError, "sample rate mismatch"):
                compare_files(path, actual, [1.0], 0)


class ReferenceTest(unittest.TestCase):
    def test_hand_calculated_stereo_fir_includes_tail(self):
        self.assertEqual([[0.5, 1.0], [0.75, 2.0], [-0.25, 0.5], [-0.125, 0.0]],
                         fir_reference([[1, 2], [-0.5, 0]], [0.5, 1, 0.25]))
        self.assertEqual([[-0.125, 0.0]], fir_reference([[-0.5, 0]], [0.25]))
        self.assertEqual([[0.5], [1.0], [0.25]], fir_reference([[1]], [0.5, 1, 0.25]))

    def test_direct_convolution_uses_float64_accumulation(self):
        self.assertEqual(1.0, fir_reference([[1e16], [1.0], [-1e16]], [1, 1, 1])[2][0])

    def test_tolerance_boundary_and_finite_metrics(self):
        self.assertEqual("PASS", compare([[0]], [[0.125]], 0.125)["outcome"])
        result = compare([[0]], [[0.125]], 0.124)
        self.assertEqual("FAIL", result["outcome"])
        self.assertEqual(0.125, result["rms_error"])
        self.assertEqual(0.125, result["max_absolute_error"])

    def test_channel_swap_truncation_insertion_and_tail_loss_fail(self):
        reference = fir_reference([[1, 0], [0, 1]], [1, 0.5])
        for actual in (reference[:-1], reference + [[0, 0]], [frame[::-1] for frame in reference]):
            with self.subTest(actual=actual):
                self.assertEqual("FAIL", compare(reference, actual, 0)["outcome"])

    def test_nonfinite_malformed_and_invalid_tolerance_rejected(self):
        for frames in ([], [[]], [[1], [1, 2]], [[float("nan")]], [[float("inf")]], [[True]]):
            with self.subTest(frames=frames), self.assertRaises(ValueError):
                compare([[0]], frames, 0)
        for tolerance in (-1, float("nan"), float("inf"), True):
            with self.subTest(tolerance=tolerance), self.assertRaises(ValueError):
                compare([[0]], [[0]], tolerance)
        for taps in ([], [float("nan")], [True]):
            with self.subTest(taps=taps), self.assertRaises(ValueError):
                fir_reference([[1]], taps)


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.record = json.loads((FIXTURES / "result-template.json").read_text())

    def measured(self):
        record = copy.deepcopy(self.record)
        record.update(outcome="PASS", app_commit="a" * 40, capture_point="offline FIR output",
                      evidence_type="OFFLINE_REFERENCE", instrument={"name": "Float64 oracle", "version": "1"},
                      fixture={"sha256": "b" * 64, "codec": "PCM_SIGNED_LE", "sample_rate_hz": 48000, "bit_depth": 24, "channels": 2},
                      settings={"fir_taps": [1.0]}, result={"artifact_sha256": "c" * 64, "metrics": {
                          "max_absolute_error": {"value": 0.0, "minimum": 0.0, "maximum": 1e-5, "unit": "normalized PCM"}}})
        return record

    def test_unexecuted_outcomes_preserve_null_and_reason(self):
        for outcome in ("NOT_RUN", "BLOCKED", "NOT_APPLICABLE"):
            self.record["outcome"] = outcome
            with tempfile.TemporaryDirectory() as output:
                path = Path(output) / "result.json"
                save(self.record, path)
                self.assertEqual(self.record, json.loads(path.read_text()))
        self.record["reason"] = " "
        with self.assertRaises(ValueError):
            validate(self.record)

    def test_pass_requires_commit_capture_and_version(self):
        for field in ("app_commit", "capture_point", "instrument", "fixture", "result", "settings"):
            record = self.measured()
            record[field] = None
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate(record)
        record = self.measured()
        record["instrument"]["version"] = " "
        with self.assertRaises(ValueError):
            validate(record)

    def test_bounds_control_outcome_including_fail(self):
        record = self.measured()
        validate(record)
        metric = record["result"]["metrics"]["max_absolute_error"]
        metric["value"] = 1e-4
        with self.assertRaises(ValueError):
            validate(record)
        record["outcome"] = "FAIL"
        validate(record)
        metric["value"] = 0.0
        with self.assertRaises(ValueError):
            validate(record)

    def test_unknown_version_fields_status_and_nonfinite_metrics_rejected(self):
        for field, value in (("schema_version", 2), ("outcome", "SUCCESS"), ("extra", "ignored"),
                             ("recorded_at_utc", "2026-10-30T00:00:00+09:00")):
            record = self.measured()
            record[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate(record)

    def test_nonfinite_settings_and_reversed_or_missing_bounds_rejected(self):
        record = self.measured()
        record["settings"]["gain"] = float("nan")
        with self.assertRaises(ValueError):
            validate(record)
        for minimum, maximum in ((None, None), (2, 1), (False, 1)):
            record = self.measured()
            record["result"]["metrics"]["max_absolute_error"].update(minimum=minimum, maximum=maximum)
            with self.subTest(minimum=minimum, maximum=maximum), self.assertRaises(ValueError):
                validate(record)
        for value in (float("nan"), float("inf"), True):
            record = self.measured()
            record["result"]["metrics"]["max_absolute_error"]["value"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate(record)

    def test_unexecuted_records_cannot_carry_a_success_result(self):
        record = self.measured()
        record["outcome"] = "NOT_RUN"
        with self.assertRaises(ValueError):
            validate(record)

    def test_device_evidence_needs_actual_device_context(self):
        record = self.measured()
        record["evidence_type"] = "EXTERNAL_DIGITAL"
        with self.assertRaises(ValueError):
            validate(record)
        record["device"] = dict.fromkeys(MATRIX_FIELDS, "test condition")
        record["device"].update(API_level=36, build_fingerprint_hash="d" * 64)
        validate(record)
        record["device"]["model"] = None
        with self.assertRaises(ValueError):
            validate(record)


if __name__ == "__main__":
    unittest.main()
