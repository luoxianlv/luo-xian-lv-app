"""原生打包入口在缺少显式范围时必须先拒绝，不接触签名或生成发布包。"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class NativePackagingGuards(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runner = self.root / "runner"
        self.runner.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.script = Path(__file__).resolve().parents[1] / "scripts" / "package-release.sh"
        self.bash = (r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else shutil.which("bash"))
        if not self.bash or not Path(self.bash).is_file():
            self.skipTest("需要本地 Bash")
        for name, body in (("pwsh", "exit 0"), ("jq", "cat >/dev/null; exit 1")):
            path = self.bin / name
            path.write_text("#!/bin/sh\n" + body + "\n", encoding="utf-8")
            path.chmod(0o755)

    def source(self):
        for name in ("app-host/build.gradle.kts", "tools/verify-native-release.ps1"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture", encoding="utf-8")

    def rejected(self, flag, message, config=""):
        # 不继承任何签名/服务环境，只传测试工具路径和新建临时目录。
        env = {"PATH": str(self.bin) + os.pathsep + os.environ.get("PATH", ""),
               "RUNNER_TEMP": str(self.runner), "NATIVE_RELEASE_PACKAGE": flag,
               "NATIVE_HOT_CONFIG_JSON": config}
        for name in ("SYSTEMROOT", "WINDIR"):
            if name in os.environ:
                env[name] = os.environ[name]
        result = subprocess.run([self.bash, str(self.script)], cwd=self.root, env=env,
                                capture_output=True, text=True, encoding="utf-8", timeout=15)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(message, result.stderr)
        self.assertFalse((self.root / "dist").exists())
        self.assertEqual(list(self.runner.iterdir()), [])

    def test_unknown_switch_rejected_before_credentials(self):
        self.rejected("maybe", "Invalid native package switch")

    def test_old_source_cannot_silently_fall_back_to_legacy(self):
        self.rejected("true", "does not support native packaging")

    def test_native_requires_public_configuration_before_signing(self):
        self.source()
        self.rejected("true", "Missing public native hot-update configuration")

    def test_failed_scope_validation_never_reaches_signing(self):
        self.source()
        self.rejected("true", "Invalid production native hot-update scope", '{"environment":"test"}')

    def test_default_legacy_route_keeps_existing_signing_guard(self):
        self.rejected("false", "缺少签名配置")


if __name__ == "__main__":
    unittest.main()
