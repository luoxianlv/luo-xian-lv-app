import os
from pathlib import Path
import shutil
import subprocess
import unittest


class ReleaseTasksTest(unittest.TestCase):
    def tasks(self, native, skip):
        bash = os.environ.get("RELEASE_TEST_BASH") or shutil.which("bash")
        if not bash:
            self.skipTest("需要 Bash")
        script = Path(__file__).resolve().parents[1] / "scripts/release-build-tasks.sh"
        return subprocess.run([bash, "-c", 'source "$1"; select_release_tasks "$2" "$3" || exit $?; printf "%s\\n" "${release_tasks[@]}"',
                               "--", script.as_posix(), native, skip], capture_output=True, text=True)

    def test_default_native_runs_tests_and_build(self):
        result = self.tasks("true", "false")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(":hot-core:testDebugUnitTest", result.stdout.splitlines())
        self.assertIn(":app-business:testDebugUnitTest", result.stdout.splitlines())
        self.assertIn(":app-host:exportReleaseNativeBuildReport", result.stdout.splitlines())

    def test_manual_skip_keeps_native_build(self):
        result = self.tasks("true", "true")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), [":app-host:exportReleaseNativeBuildReport"])

    def test_legacy_default_and_skip_always_build(self):
        for skip, expected in [("false", ["testDebugUnitTest", "assembleRelease"]), ("true", ["assembleRelease"])]:
            result = self.tasks("false", skip)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.splitlines(), expected)

    def test_invalid_flags_are_rejected(self):
        for native, skip in [("true", "yes"), ("unknown", "false"), ("false", "")]:
            self.assertNotEqual(self.tasks(native, skip).returncode, 0)
