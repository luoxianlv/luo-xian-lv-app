"""增量发布的失败隔离、正式包来源与兼容整包行为。"""

import base64
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import prepare_incremental as prepare
import publish_incremental as publish


@contextmanager
def in_directory(path):
    old = Path.cwd()
    os.chdir(path)
    try:
        yield
    finally:
        os.chdir(old)


class IncrementalPublicationTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.directory = self.root / "dist/incremental"
        self.directory.mkdir(parents=True)
        self.apk = self.root / "dist/formal.apk"
        self.apk.write_bytes(b"official signed original APK")
        self.sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.package = {"versionCode": 20, "versionName": "1.2.0"}
        self.patch_hash = "c" * 64
        self.target = dict(self.package, sha256=self.sha, size=self.apk.stat().st_size, certificateSha256="d" * 64)
        self.manifest = {"schema": 1, "target": self.target, "deltas": [{"patch": {
            "sha256": self.patch_hash, "size": 1, "object": "luoxianlv/delta/approved.hpatch"}}]}
        self.envelope = {"schema": 1, "manifest": base64.b64encode(json.dumps(self.manifest).encode()).decode()}
        (self.directory / "delivery.json").write_text(json.dumps(self.envelope))
        (self.directory / "root.public.json").write_text('{"fixture": true}')
        (self.directory / "history.json").write_text('["dist/incremental/history/formal-old.apk"]')
        self.env = {"UPDATE_ROOT_PUBLIC_JSON": '{"fixture": true}', "LXUPDATE_BIN": "fixture-lxupdate",
                    "HDIFFZ": "fixture-hdiff", "HPATCHZ": "fixture-hpatch", "HDIFF_LOCK": "fixture-lock",
                    "ANDROID_AAPT": "fixture-aapt", "ANDROID_APKSIGNER": "fixture-apksigner"}
        self.client = Mock()

    def run_publication(self):
        return publish.verify_and_upload(self.client, "private-fixture", self.apk, self.package, self.sha,
                                         lambda transfer: transfer())

    def test_missing_signed_delivery_keeps_old_full_route(self):
        (self.directory / "delivery.json").unlink()
        with in_directory(self.root), patch.dict(os.environ, {}, clear=True), patch.object(publish.subprocess, "run") as verify:
            self.assertIsNone(self.run_publication())
            verify.assert_not_called()

    def test_verify_all_patches_before_r2_and_emit_only_after_readback(self):
        order = []
        with in_directory(self.root), patch.dict(os.environ, self.env), \
                patch.object(publish.subprocess, "run", side_effect=lambda *a, **kw: order.append("verify")) as verify, \
                patch.object(publish, "upload_and_verify", side_effect=lambda *a, **kw: order.append("readback")) as upload:
            result = self.run_publication()
        self.assertEqual(order, ["verify", "readback"])
        self.assertEqual(result, (self.envelope, "d" * 64))
        self.assertIn("--history", verify.call_args.args[0])
        self.assertEqual(upload.call_args.kwargs["content_type"], "application/octet-stream")

    def test_bad_signature_or_reconstruction_blocks_upload(self):
        with in_directory(self.root), patch.dict(os.environ, self.env), \
                patch.object(publish.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "verify")), \
                patch.object(publish, "upload_and_verify") as upload:
            with self.assertRaises(subprocess.CalledProcessError):
                self.run_publication()
            upload.assert_not_called()

    def test_corrupt_r2_readback_never_returns_deployable_manifest(self):
        with in_directory(self.root), patch.dict(os.environ, self.env), \
                patch.object(publish.subprocess, "run"), patch.object(publish, "upload_and_verify", side_effect=ValueError("readback")):
            with self.assertRaisesRegex(ValueError, "readback"):
                self.run_publication()

    def test_rejects_root_replacement_and_formal_target_mismatch(self):
        for environment, sha in ((dict(self.env, UPDATE_ROOT_PUBLIC_JSON='{"fixture": false}'), self.sha),
                                 (self.env, "0" * 64)):
            with self.subTest(environment=environment), in_directory(self.root), patch.dict(os.environ, environment), \
                    patch.object(publish.subprocess, "run"), patch.object(publish, "upload_and_verify") as upload:
                with self.assertRaises(ValueError):
                    publish.verify_and_upload(self.client, "private-fixture", self.apk, self.package, sha, lambda f: f())
                upload.assert_not_called()

    def test_disabled_and_missing_signing_config_keep_full_route(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertFalse(prepare.enabled())
        with patch.dict(os.environ, {"UPDATE_INCREMENTAL_PREPARE": "true"}, clear=True):
            self.assertFalse(prepare.enabled())
        with patch.dict(os.environ, {"UPDATE_INCREMENTAL_PREPARE": "true", "UPDATE_ROOT_PUBLIC_JSON": "{}"}, clear=True):
            with self.assertRaises(ValueError):
                prepare.enabled()

    def test_actual_signed_apk_version_must_match_release_metadata(self):
        package = {"versionCode": 20, "versionName": "1.2.0"}
        actual = dict(package, applicationId="app.luoxianlv", sha256=self.sha, size=self.apk.stat().st_size)
        prepare.match_package(actual, package, self.sha, self.apk.stat().st_size)
        for changes in ({"versionCode": 19}, {"versionName": "1.1.0"}, {"applicationId": "app.luoxianlv.debug"},
                        {"sha256": "0" * 64}, {"size": 1}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                prepare.match_package(dict(actual, **changes), package, self.sha, self.apk.stat().st_size)

    def test_history_package_metadata_needs_github_asset_digest(self):
        package_raw = b'{"versionCode":19,"versionName":"1.1.0","asset":"luoxianlv-v1.1.0-release.apk"}'
        release = {"tag_name": "v1.1.0", "draft": False, "prerelease": False, "assets": [
            {"name": "luoxianlv-v1.1.0-release.apk"}, {"name": "luoxianlv-v1.1.0-release.apk.sha256"},
            {"name": "release-notes.json"}, {"name": "package.json", "digest": "sha256:" + "0" * 64, "size": len(package_raw)},
        ]}

        def download(command, **kwargs):
            path = Path(command[command.index("--dir") + 1])
            (path / "package.json").write_bytes(package_raw)

        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "fixture/app"}), \
                patch.object(prepare.subprocess, "check_output", return_value=json.dumps([release])), \
                patch.object(prepare.subprocess, "run", side_effect=download) as transfer, \
                patch.object(prepare, "inspect_apk") as inspect:
            with self.assertRaisesRegex(ValueError, "GitHub 资产证据"):
                prepare.histories(self.root, 20, "v1.2.0")
            self.assertEqual(transfer.call_count, 1)
            inspect.assert_not_called()


if __name__ == "__main__":
    unittest.main()
