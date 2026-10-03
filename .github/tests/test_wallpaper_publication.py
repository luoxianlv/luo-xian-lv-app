"""默认壁纸双存储发布：只有两处均校验成功才能更新共用清单。"""

from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch
import zipfile


SCRIPT = Path(__file__).resolve().parents[2] / "tools" / "publish_wallpaper.py"
SPEC = importlib.util.spec_from_file_location("wallpaper_publication", SCRIPT)
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class WallpaperPublicationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.archive = root / "wallpaper.zip"
        self.manifest = root / "default.json"
        with zipfile.ZipFile(self.archive, "w") as package:
            package.writestr("project.json", json.dumps({"file": "background.png", "type": "image"}))
            package.writestr("background.png", b"fixture image")

    def run_publish(self, provider=None):
        command = [str(SCRIPT), str(self.archive), "--title", "测试壁纸", "--manifest", str(self.manifest)]
        if provider is not None:
            command += ["--provider", provider]
        with patch("sys.argv", command), redirect_stdout(io.StringIO()):
            publisher.main()

    def test_dual_waits_for_both_backends_and_keeps_one_object_identity(self):
        sequence = []

        def record(name):
            def completed(*arguments):
                self.assertFalse(self.manifest.exists())
                sequence.append((name, arguments))
            return completed

        with patch.object(publisher, "publish_oss", side_effect=record("oss")), \
                patch.object(publisher, "publish_r2", side_effect=record("r2")):
            self.run_publish("dual")
        self.assertEqual([name for name, _ in sequence], ["oss", "r2"])
        self.assertEqual(sequence[0][1][:3], sequence[1][1])
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        sha = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.assertEqual(manifest, {"title": "测试壁纸", "object": f"luoxianlv/wallpapers/{sha}/default.zip",
                                    "sha256": sha, "size": self.archive.stat().st_size})

    def test_failure_in_either_backend_preserves_previous_manifest(self):
        self.manifest.write_bytes(b"previous valid manifest")
        for failed_backend in ("publish_oss", "publish_r2"):
            with self.subTest(failed_backend=failed_backend), \
                    patch.object(publisher, "publish_oss"), patch.object(publisher, "publish_r2"), \
                    patch.object(publisher, failed_backend, side_effect=OSError("fixture")):
                with self.assertRaises(OSError):
                    self.run_publish("dual")
                self.assertEqual(self.manifest.read_bytes(), b"previous valid manifest")

    def test_legacy_command_does_not_require_new_credentials(self):
        with patch.object(publisher, "publish_oss") as oss, patch.object(publisher, "publish_r2") as r2:
            self.run_publish()
        oss.assert_called_once()
        r2.assert_not_called()
        self.assertTrue(self.manifest.is_file())

    def test_manifest_commit_failure_keeps_previous_file_and_cleans_pending(self):
        self.manifest.write_bytes(b"previous valid manifest")
        with patch.object(publisher, "publish_oss"), patch.object(publisher, "publish_r2"), \
                patch.object(Path, "replace", side_effect=OSError("fixture disk failure")):
            with self.assertRaises(OSError):
                self.run_publish("dual")
        self.assertEqual(self.manifest.read_bytes(), b"previous valid manifest")
        self.assertEqual(list(self.manifest.parent.glob(".wallpaper-manifest-*")), [])

    def test_invalid_archive_never_reaches_storage(self):
        with zipfile.ZipFile(self.archive, "w") as package:
            package.writestr("project.json", '{"file":"missing.png"}')
        with patch.object(publisher, "publish_oss") as oss, patch.object(publisher, "publish_r2") as r2:
            with self.assertRaises(ValueError):
                self.run_publish("dual")
        oss.assert_not_called()
        r2.assert_not_called()
        self.assertFalse(self.manifest.exists())

    def test_oss_complete_readback_suppresses_raw_sdk_output(self):
        sha = hashlib.sha256(self.archive.read_bytes()).hexdigest()

        def transfer(arguments, **options):
            self.assertIs(options["stdout"], subprocess.DEVNULL)
            self.assertIs(options["stderr"], subprocess.DEVNULL)
            self.assertTrue(options["check"])
            if arguments[2].startswith("oss://"):
                Path(arguments[3]).write_bytes(self.archive.read_bytes())

        output = io.StringIO()
        with patch.object(publisher.subprocess, "run", side_effect=transfer) as run, redirect_stdout(output):
            publisher.publish_oss(self.archive, "fixture", sha, "ossutil")
        self.assertEqual(run.call_count, 2)
        self.assertIn("OSS 完整回读校验通过", output.getvalue())

    def test_oss_corrupt_full_readback_is_rejected(self):
        sha = hashlib.sha256(self.archive.read_bytes()).hexdigest()

        def transfer(arguments, **options):
            if arguments[2].startswith("oss://"):
                Path(arguments[3]).write_bytes(b"wrong content")

        with patch.object(publisher.subprocess, "run", side_effect=transfer), redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(ValueError, "OSS 回读校验失败"):
                publisher.publish_oss(self.archive, "fixture", sha, "ossutil")


if __name__ == "__main__":
    unittest.main()
