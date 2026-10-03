"""发布默认壁纸到私有 R2 或兼容旧 OSS，完整回读后生成清单；不触发 APP 发版。"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile


def publish_r2(archive, key, sha):
    # 复用正式 APK 的有界分片、回读校验和凭据限制；只更换文件类型。
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".github" / "scripts"))
    import publish_r2 as storage

    client, bucket = storage.create_client()
    try:
        storage.upload_and_verify(client, bucket, archive, key, sha, content_type="application/zip")
    finally:
        client.close()


def publish_oss(archive, key, sha, ossutil):
    uri = f"oss://luoxianlv/{key}"
    with tempfile.TemporaryDirectory(prefix="wallpaper-publish-") as temporary:
        # 不转发 SDK 原始输出，避免错误响应中包含凭据或签名请求信息。
        print("OSS 开始上传默认壁纸", flush=True)
        subprocess.run([
            ossutil, "cp", str(archive), uri, "--force",
            "--parallel", "4", "--part-size", "4Mi", "--bigfile-threshold", "1Mi",
            "--checkpoint-dir", temporary, "--content-type", "application/zip",
            "--cache-control", "private, max-age=0",
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print("OSS 上传完成，开始完整回读校验", flush=True)
        copy = Path(temporary) / "verified.zip"
        subprocess.run([ossutil, "cp", uri, str(copy)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        with copy.open("rb") as downloaded:
            if copy.stat().st_size != archive.stat().st_size or hashlib.file_digest(downloaded, "sha256").hexdigest() != sha:
                raise ValueError("OSS 回读校验失败，未生成清单")
        print("OSS 完整回读校验通过", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--title", required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--provider", choices=("dual", "oss"), default="oss",
                        help="dual 同时发布旧 OSS 和私有 R2；默认 oss 保持旧命令行为")
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
    # 新旧下载器共用对象摘要；两处均校验成功才允许发布新清单。
    publish_oss(archive, key, sha, args.ossutil)
    if args.provider == "dual":
        publish_r2(archive, key, sha)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps({"title": args.title, "object": key, "sha256": sha, "size": size},
                      ensure_ascii=False, indent=2) + "\n"
    pending = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n", delete=False,
                                         dir=args.manifest.parent, prefix=".wallpaper-manifest-") as output:
            pending = Path(output.name)
            output.write(text)
        pending.replace(args.manifest)
    finally:
        if pending is not None:
            pending.unlink(missing_ok=True)
    print(f"壁纸上传并校验完成：{size} 字节；SHA-256 {sha}")
    print(f"服务端清单：{args.manifest.resolve()}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try:
        main()
    except Exception as error:
        print(f"壁纸发布失败（{type(error).__name__}），清单未更新", file=sys.stderr)
        raise SystemExit(1) from None
