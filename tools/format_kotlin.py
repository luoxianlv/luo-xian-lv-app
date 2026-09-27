"""Format Kotlin with a pinned, checksum-verified ktfmt: python tools/format_kotlin.py [--check]."""

import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile
import urllib.request

VERSION = "0.64"
SHA256 = "5b3d5286fd2defcc7dc8e28c21ddf156cc6b2d8682bdcd929ce4333e7a6201f2"
ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / ".gradle" / "formatters" / f"ktfmt-{VERSION}.jar"
URL = f"https://repo.maven.apache.org/maven2/com/facebook/ktfmt/{VERSION}/ktfmt-{VERSION}-with-dependencies.jar"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check formatting without changing files")
    args = parser.parse_args()
    if not JAR.exists():
        JAR.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(URL, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != SHA256:
            raise RuntimeError("ktfmt download checksum mismatch")
        JAR.write_bytes(data)
    if hashlib.sha256(JAR.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError("Cached ktfmt checksum mismatch")

    arguments = ["--kotlinlang-style", "--quiet"]
    if args.check:
        arguments += ["--dry-run", "--set-exit-if-changed"]
    arguments += [str(p.relative_to(ROOT)) for p in sorted((ROOT / "app/src").rglob("*.kt"))]
    # An argument file keeps Windows command lines below their length limit.
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", suffix=".args", delete=False) as argfile:
        argfile.write("\n".join(arguments))
    try:
        return subprocess.run(["java", "-jar", str(JAR), "@" + argfile.name], cwd=ROOT).returncode
    finally:
        Path(argfile.name).unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
