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

    def source(self, prefix="modules/"):
        for name in (prefix + "app-host/build.gradle.kts", "tools/verify-native-release.ps1"):
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

    def test_old_native_layout_requires_public_configuration_before_signing(self):
        self.source("")
        self.rejected("true", "Missing public native hot-update configuration")

    def test_failed_scope_validation_never_reaches_signing(self):
        self.source()
        self.rejected("true", "Invalid production native hot-update scope", '{"environment":"test"}')

    def test_default_legacy_route_keeps_existing_signing_guard(self):
        self.rejected("false", "缺少签名配置")

    def fixture(self, name, content, executable=False):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")
        if executable:
            path.chmod(0o755)
        return path

    def packaged(self, prefix):
        self.source(prefix)
        self.fixture("gradlew", "exit 0\n")
        self.fixture("bin/jq", '#!/bin/sh\nif [ "$1" = "-n" ]; then printf "{}\\n"; else cat >/dev/null; fi\n', True)
        self.fixture("bin/pwsh", """#!/bin/sh
while [ "$#" -gt 0 ]; do
  if [ "$1" = "-HostApk" ]; then
    shift
    [ "$1" = "${FIXTURE_NATIVE_PREFIX}app-host/build/outputs/apk/release/app-host-release.apk" ] || exit 1
    test -f "$1" || exit 1
    printf '%s' "$1" > audit-host-path
    exit 0
  fi
  shift
done
exit 1
""", True)
        certificate = "0" * 64
        self.fixture("sdk/build-tools/1.0/apksigner",
                     "#!/bin/sh\nprintf 'Signer #1 certificate SHA-256 digest: " + certificate + "\\n'\n", True)
        self.fixture("sdk/build-tools/1.0/aapt",
                     "#!/bin/sh\nprintf \"package: name='app.luoxianlv' versionCode='18' versionName='1.1.0'\\n\"\n", True)
        self.fixture(prefix + "app-host/build/outputs/apk/release/app-host-release.apk", "host-apk")
        self.fixture(prefix + "app-host/build/native-release-verification.json", '{"fixture":true}\n')
        self.fixture(prefix + "hot-contract/build/native-sdk/release/host-contract-sdk.jar", "host-sdk")
        self.fixture(prefix + "app-runtime/build/native-sdk/release/runtime-sdk.jar", "runtime-sdk")
        for role in ("host", "runtime", "business"):
            self.fixture(prefix + f"app-{role}/build/native-report/release/report.json", role + "-report")
        for role in ("runtime", "business"):
            self.fixture(prefix + f"app-{role}/build/native-link/release/{role}.apk", role + "-apk")
        self.fixture(".github/release-notes/1.1.0.json", '{"sections":[]}\n')
        env = {"PATH": str(self.bin) + os.pathsep + os.environ.get("PATH", ""),
               "RUNNER_TEMP": self.runner.as_posix(), "ANDROID_HOME": (self.root / "sdk").as_posix(),
               "GITHUB_STEP_SUMMARY": (self.root / "summary").as_posix(), "RELEASE_TAG": "v1.1.0",
               "NATIVE_RELEASE_PACKAGE": "true", "NATIVE_HOT_CONFIG_JSON": '{"fixture":true}',
               "FIXTURE_NATIVE_PREFIX": prefix, "ANDROID_KEYSTORE_BASE64": "Zml4dHVyZQ==",
               "ANDROID_SIGNING_CERT_SHA256": certificate,
               "ORG_GRADLE_PROJECT_releaseStorePassword": "fixture",
               "ORG_GRADLE_PROJECT_releaseKeyAlias": "fixture",
               "ORG_GRADLE_PROJECT_releaseKeyPassword": "fixture"}
        for name in ("SYSTEMROOT", "WINDIR"):
            if name in os.environ:
                env[name] = os.environ[name]
        result = subprocess.run([self.bash, str(self.script)], cwd=self.root, env=env,
                                capture_output=True, text=True, encoding="utf-8", timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "audit-host-path").read_text(),
                         prefix + "app-host/build/outputs/apk/release/app-host-release.apk")
        self.assertEqual((self.root / "dist/luoxianlv-v1.1.0-release.apk").read_text(), "host-apk")
        self.assertEqual((self.root / "dist/native-release-verification.json").read_text(), '{"fixture":true}\n')
        for role in ("host", "runtime", "business"):
            self.assertEqual((self.root / f"native-release-artifacts/{role}/report.json").read_text(), role + "-report")
        for role in ("runtime", "business"):
            self.assertEqual((self.root / f"native-release-artifacts/{role}/{role}.apk").read_text(), role + "-apk")
        self.assertEqual((self.root / "native-release-artifacts/host-contract-sdk.jar").read_text(), "host-sdk")
        self.assertEqual((self.root / "native-release-artifacts/runtime-sdk.jar").read_text(), "runtime-sdk")
        self.assertFalse((self.runner / "release.jks").exists())

    def test_native_packaging_uses_grouped_module_outputs(self):
        self.packaged("modules/")

    def test_native_packaging_uses_old_tag_module_outputs(self):
        self.packaged("")


if __name__ == "__main__":
    unittest.main()
