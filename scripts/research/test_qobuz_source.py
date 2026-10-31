"""Offline source-review checks for #20; these do not execute or authenticate qobine."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import unittest


FIXTURE = Path(__file__).resolve().parents[2] / "docs/evidence/issue-20/source.json"


def verify_source(root, fixture):
    revision = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "HEAD"],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    if revision != fixture["commit"]:
        raise ValueError("Source commit differs from reviewed commit")
    for name, expected in fixture["sha256"].items():
        if hashlib.sha256((root / name).read_bytes()).hexdigest() != expected:
            raise ValueError(f"Source digest differs: {name}")


def function(source, start, end):
    return source.split(start, 1)[1].split(end, 1)[0]


class QobuzSourceReview(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE.read_text())
        verify_source(SOURCE, cls.fixture)
        cls.client = (SOURCE / "qobuz-client/src/client.rs").read_text()
        cls.player = (SOURCE / "player-module/src/client.rs").read_text()

    # unittest's substring diagnostics would otherwise print source containing secrets.
    def assert_marker(self, marker, source):
        self.assertTrue(marker in source, f"Expected source marker missing: {marker}")

    def assert_absent(self, marker, source):
        self.assertFalse(marker in source, f"Unexpected source marker: {marker}")

    def test_wrong_revision_is_rejected(self):
        fixture = {**self.fixture, "commit": "0" * 40}
        with self.assertRaisesRegex(ValueError, "commit differs"):
            verify_source(SOURCE, fixture)

    def test_changed_file_is_rejected(self):
        fixture = {**self.fixture, "sha256": {"Cargo.toml": "0" * 64}}
        with self.assertRaisesRegex(ValueError, "digest differs"):
            verify_source(SOURCE, fixture)

    def test_callback_has_reviewed_adoption_blockers(self):
        login = function(self.client, "pub async fn browser_oauth_login", "async fn read_code_from_stdin")
        self.assert_marker('TcpListener::bind("0.0.0.0:0")', login)
        self.assert_marker('params.get("code_autorisation")', login)
        self.assert_absent('params.get("state")', login)
        self.assert_marker('tracing::debug!("Received authorization code:', login)
        url = function(self.client, "fn build_oauth_url", "pub struct Secrets")
        self.assert_marker("http://localhost:", url)
        self.assert_absent("state=", url)

    def test_player_identity_is_not_an_audipp_grant(self):
        identity = function(self.player, "pub async fn app_id", "pub async fn connect_token")
        self.assert_marker("self.secrets().await?.app_id", identity)
        exchange = function(self.client, "pub async fn exchange_oauth_code", "fn build_oauth_url")
        self.assert_marker("OAUTH_PRIVATE_KEY", exchange)

    def test_resolution_paths_remain_distinct(self):
        session = function(self.client, "pub async fn get_streaming_info", "pub async fn get_file_based_streaming_info")
        file_based = function(self.client, "pub async fn get_file_based_streaming_info", "pub async fn stream_track_file_based")
        self.assert_marker("ensure_valid_session().await?", session)
        self.assert_marker("Endpoint::File", session)
        self.assert_marker("Error::SecretNotSet", file_based)
        self.assert_marker("track_url(", file_based)
        self.assert_absent("get_file_based_streaming_info", session)

    def test_file_path_has_persistent_cache_blocker(self):
        file_based = (SOURCE / "qobuz-client/src/stream/file_based.rs").read_text()
        downloader = (SOURCE / "player-module/src/downloader.rs").read_text()
        self.assert_marker("std::fs::rename(partial, final_path)", file_based)
        self.assert_marker("DownloadResult::Cached(cache_path)", downloader)

    def test_reports_and_session_expiry_exist_only_as_source_evidence(self):
        self.assert_marker("pub async fn report_streaming_start", self.client)
        self.assert_marker("pub async fn report_streaming_end", self.client)
        self.assert_marker("u64::from(s.expires_at) <= now", self.client)
        report = (SOURCE / "player-module/src/report.rs").read_text()
        self.assert_marker("Giving up on {batch} streamed stretches", report)

    def test_license_and_edition_match_review(self):
        self.assert_marker("Version 3, 29 June 2007", (SOURCE / "LICENSE").read_text())
        self.assert_marker('edition = "2024"', (SOURCE / "Cargo.toml").read_text())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Fixed qobine checkout")
    args, test_args = parser.parse_known_args()
    SOURCE = args.source.resolve()
    unittest.main(argv=[__file__, *test_args])
