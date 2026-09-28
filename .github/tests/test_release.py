"""发布链路回归：内容完整性、分片重试、清单来源与失败隔离。"""

from contextlib import redirect_stdout
from copy import deepcopy
from email.message import Message
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
from release_package import validate_package
from validate_manifest import validate_manifest
from deploy_manifest import deploy


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


publish = load_script("publish-oss")
provenance = load_script("validate-oss-run")


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / "dist"
        self.directory.mkdir()
        self.data = b"signed APK fixture"
        self.sha = hashlib.sha256(self.data).hexdigest()
        self.asset = "luoxianlv-v1.0.8-release.apk"
        self.apk = self.directory / self.asset
        self.apk.write_bytes(self.data)
        self.package = {"versionName": "1.0.8", "versionCode": 13, "asset": self.asset}
        self.notes = {"sections": [{"title": "更新内容", "items": ["修复闪退"]}]}
        self.release = {
            "tag_name": "v1.0.8", "draft": False, "prerelease": False,
            "assets": [{"id": 1, "name": self.asset, "digest": f"sha256:{self.sha}",
                        "size": len(self.data), "browser_download_url":
                        f"https://github.com/luoxianlv/luo-xian-lv-app/releases/download/v1.0.8/{self.asset}"}],
        }
        self.write_json("package.json", self.package)
        self.write_json("release-notes.json", self.notes)
        self.write_json("github-release.json", self.release)
        (self.directory / f"{self.asset}.sha256").write_text(f"{self.sha}  {self.asset}\n")

    def write_json(self, name, value):
        (self.directory / name).write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")

    def validate(self):
        return validate_package(self.directory, "v1.0.8", self.release)

    def test_valid_release(self):
        self.assertEqual(self.validate()[3], self.sha)

    def test_reject_bad_tag(self):
        with self.assertRaises(ValueError):
            validate_package(self.directory, "../v1.0.8", self.release)

    def test_reject_unpublished_or_wrong_release(self):
        for key, value in (("draft", True), ("prerelease", True), ("tag_name", "v1.0.7")):
            with self.subTest(key=key):
                release = dict(self.release, **{key: value})
                with self.assertRaises(ValueError):
                    validate_package(self.directory, "v1.0.8", release)

    def test_reject_metadata_tampering(self):
        for key, value in (("asset", "../../other.apk"), ("versionName", "1.0.7"), ("versionCode", True), ("versionCode", -1)):
            with self.subTest(key=key):
                self.write_json("package.json", dict(self.package, **{key: value}))
                with self.assertRaises(ValueError):
                    self.validate()

    def test_reject_corrupt_apk(self):
        self.apk.write_bytes(b"bad APK")
        with self.assertRaises(ValueError):
            self.validate()

    def test_reject_github_digest_or_size_mismatch(self):
        for key, value in (("digest", "sha256:bad"), ("size", 1)):
            with self.subTest(key=key):
                release = deepcopy(self.release)
                release["assets"][0][key] = value
                with self.assertRaises(ValueError):
                    validate_package(self.directory, "v1.0.8", release)

    def test_reject_bad_notes(self):
        self.write_json("release-notes.json", {"sections": []})
        with self.assertRaises(ValueError):
            self.validate()

    def response(self, data=None, content_type="application/vnd.android.package-archive"):
        response = io.BytesIO(self.data if data is None else data)
        response.headers = Message()
        response.headers["Content-Type"] = content_type
        return response

    def transfer(self, bucket, response):
        with patch.object(publish.urllib.request, "urlopen", return_value=response), redirect_stdout(io.StringIO()):
            publish.upload_and_verify(bucket, Mock(), self.apk, "key", self.sha, Mock())

    def test_multipart_config_and_verified_download(self):
        bucket = Mock()
        bucket.head_object.side_effect = publish.oss2.exceptions.NoSuchKey(404, {}, b"", {})
        with patch.object(publish.oss2, "resumable_upload") as upload:
            self.transfer(bucket, self.response())
            self.assertEqual(upload.call_args.kwargs["num_threads"], 4)
            self.assertEqual(upload.call_args.kwargs["part_size"], 4 * 1024 * 1024)
            self.assertEqual(upload.call_args.kwargs["headers"]["x-oss-meta-sha256"], self.sha)

    def test_part_progress_before_acknowledgement(self):
        output = io.StringIO()
        expected = Mock()
        callback = Mock()

        def send(*args, **kwargs):
            kwargs["progress_callback"](5, 10)
            self.assertIn("分片 2 已发送：50%", output.getvalue())
            self.assertNotIn("已获 OSS 确认", output.getvalue())
            return expected

        bucket = publish.ReportingBucket(publish.oss2.AnonymousAuth(), "https://example.test", "fixture")
        with patch.object(publish.oss2.Bucket, "upload_part", side_effect=send), redirect_stdout(output):
            actual = bucket.upload_part("key", "upload", 2, io.BytesIO(b"0123456789"), progress_callback=callback)
        self.assertIs(actual, expected)
        callback.assert_called_once_with(5, 10)
        self.assertIn("分片 2 已获 OSS 确认", output.getvalue())

    def test_failed_part_never_reports_acknowledgement(self):
        output = io.StringIO()
        bucket = publish.ReportingBucket(publish.oss2.AnonymousAuth(), "https://example.test", "fixture")
        with patch.object(publish.oss2.Bucket, "upload_part", side_effect=OSError("fixture")), redirect_stdout(output):
            with self.assertRaises(OSError):
                bucket.upload_part("key", "upload", 1, io.BytesIO(b"part"))
        self.assertNotIn("已获 OSS 确认", output.getvalue())

    def test_existing_object_still_download_verified(self):
        bucket = Mock()
        bucket.head_object.return_value.headers = {"Content-Length": str(len(self.data)), "X-Oss-Meta-Sha256": self.sha}
        with patch.object(publish.oss2, "resumable_upload") as upload:
            self.transfer(bucket, self.response())
            upload.assert_not_called()

    def test_reject_corrupt_or_wrong_type_download(self):
        bucket = Mock()
        bucket.head_object.return_value.headers = {}
        for data, content_type in ((b"wrong", "application/vnd.android.package-archive"), (self.data * 2, "application/vnd.android.package-archive"), (self.data, "text/html")):
            with self.subTest(content_type=content_type, size=len(data)), patch.object(publish.oss2, "resumable_upload"):
                with self.assertRaises(ValueError):
                    self.transfer(bucket, self.response(data, content_type))

    def run_main(self, side_effect):
        previous = Path.cwd()
        os.chdir(self.root)
        try:
            with patch.dict(os.environ, {"RELEASE_TAG": "v1.0.8", "OSS_ENDPOINT": "example.test", "OSS_BUCKET": "fixture", "OSS_ACCESS_KEY_ID": "fixture", "OSS_ACCESS_KEY_SECRET": "fixture"}), patch.object(publish, "upload_and_verify", side_effect=side_effect) as transfer, patch.object(publish.time, "sleep"), redirect_stdout(io.StringIO()):
                publish.main()
                return transfer
        finally:
            os.chdir(previous)

    def test_retry_reuses_checkpoint_and_writes_valid_manifest(self):
        transfer = self.run_main([OSError("network"), None])
        self.assertEqual(transfer.call_count, 2)
        self.assertIs(transfer.call_args_list[0].args[-1], transfer.call_args_list[1].args[-1])
        manifest = json.loads((self.directory / "stable.json").read_text(encoding="utf-8"))
        validate_manifest(manifest)
        self.assertEqual(manifest["releaseNotes"], self.notes)
        for channel in ("oss", "github"):
            broken = deepcopy(manifest)
            broken["channels"][channel]["sha256"] = "0" * 64
            with self.assertRaises(ValueError):
                validate_manifest(broken)

    def test_failure_removes_stale_manifest(self):
        self.write_json("stable.json", {"stale": True})
        with self.assertRaises(OSError):
            self.run_main(OSError("network"))
        self.assertFalse((self.directory / "stable.json").exists())

    def test_manifest_provenance(self):
        run = {"path": ".github/workflows/oss.yml", "event": "workflow_dispatch", "head_branch": "main", "status": "completed", "conclusion": "success", "repository": {"full_name": "luoxianlv/luo-xian-lv-app"}}
        provenance.validate_run(run, "luoxianlv/luo-xian-lv-app")
        for key, value in (("path", ".github/workflows/ci.yml"), ("event", "pull_request"), ("head_branch", "other"), ("status", "in_progress"), ("conclusion", "failure"), ("repository", {"full_name": "other/repo"})):
            with self.subTest(key=key), self.assertRaises(ValueError):
                provenance.validate_run(dict(run, **{key: value}), "luoxianlv/luo-xian-lv-app")

    def test_deploy_rejects_downgrade_and_same_version_replacement(self):
        original = {"latestVersionCode": 13, "apkSha256": self.sha}
        self.write_json("stable.json", original)
        for incoming in ({"latestVersionCode": 12, "apkSha256": self.sha}, {"latestVersionCode": 13, "apkSha256": "different"}):
            self.write_json("incoming.json", incoming)
            with self.assertRaises(ValueError):
                deploy(self.directory / "incoming.json")
            self.assertEqual(json.loads((self.directory / "stable.json").read_text()), original)
            self.assertFalse((self.directory / "incoming.json").exists())

    def test_deploy_keeps_backup_and_allows_identical_retry(self):
        original = {"latestVersionCode": 12, "apkSha256": "previous"}
        new = {"latestVersionCode": 13, "apkSha256": self.sha}
        self.write_json("stable.json", original)
        self.write_json("incoming.json", new)
        with redirect_stdout(io.StringIO()):
            deploy(self.directory / "incoming.json")
            self.assertEqual(json.loads((self.directory / "stable.previous.json").read_text()), original)
            self.assertEqual(json.loads((self.directory / "stable.json").read_text()), new)
            self.write_json("incoming.json", new)
            deploy(self.directory / "incoming.json")


if __name__ == "__main__":
    unittest.main()
