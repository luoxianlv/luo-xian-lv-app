"""用本机 ossutil 发布默认壁纸，回读校验后生成服务端清单；不会触发 APP 发版。"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--title", required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--ossutil", default=str(Path.home() / "tools/ossutil/ossutil.exe"))
    args = parser.parse_args()
    archive = args.archive.resolve(strict=True)
    size = archive.stat().st_size
    if not 0 < size <= 256 * 1024 * 1024:
        raise ValueError("壁纸 ZIP 必须小于 256 MiB")
    with zipfile.ZipFile(archive) as package:
        project = json.loads(package.read("project.json").decode("utf-8-sig"))
        if project["file"].replace("\\", "/") not in package.namelist():
            raise ValueError("默认壁纸 ZIP 根目录必须包含 project.json 和项目入口")
        if package.testzip() is not None:
            raise ValueError("ZIP 文件损坏")
    with archive.open("rb") as source:
        sha = hashlib.file_digest(source, "sha256").hexdigest()
    key = f"luoxianlv/wallpapers/{sha}/default.zip"
    uri = f"oss://luoxianlv/{key}"
    with tempfile.TemporaryDirectory(prefix="wallpaper-publish-") as temporary:
        subprocess.run([
            args.ossutil, "cp", str(archive), uri, "--force",
            "--parallel", "4", "--part-size", "4Mi", "--bigfile-threshold", "1Mi",
            "--checkpoint-dir", temporary, "--content-type", "application/zip",
            "--cache-control", "private, max-age=0",
        ], check=True)
        copy = Path(temporary) / "verified.zip"
        subprocess.run([args.ossutil, "cp", uri, str(copy)], check=True)
        with copy.open("rb") as downloaded:
            if hashlib.file_digest(downloaded, "sha256").hexdigest() != sha:
                raise ValueError("OSS 回读校验失败，未生成清单")
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps({"title": args.title, "object": key, "sha256": sha, "size": size}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"壁纸上传并校验完成：{size} 字节；SHA-256 {sha}")
    print(f"服务端清单：{args.manifest.resolve()}")


if __name__ == "__main__":
    main()
