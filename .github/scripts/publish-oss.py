import hashlib
import json
import os
from pathlib import Path
import sys
import time
import tempfile
import threading
import urllib.request

import oss2

from release_package import validate_package


class Progress:
    """分片回调来自多个线程，按 10% 输出进度，避免日志刷屏。"""

    def __init__(self, label):
        self.label = label
        self.last = -1
        self.lock = threading.Lock()

    def __call__(self, consumed, total):
        if not total:
            return
        percent = min(100, consumed * 100 // total)
        with self.lock:
            if percent // 10 > self.last:
                self.last = percent // 10
                print(f"{self.label}：{percent}%（{consumed}/{total} 字节）", flush=True)


class ReportingBucket(oss2.Bucket):
    """保留 SDK 断点续传，同时补充分片内部发送和服务端确认日志。"""

    def upload_part(self, key, upload_id, part_number, data, progress_callback=None, headers=None):
        progress = Progress(f"分片 {part_number} 已发送")

        def report(consumed, total):
            progress(consumed, total)
            if progress_callback is not None:
                progress_callback(consumed, total)

        started = time.monotonic()
        result = super().upload_part(key, upload_id, part_number, data,
                                     progress_callback=report, headers=headers)
        print(f"分片 {part_number} 已获 OSS 确认（{time.monotonic() - started:.1f} 秒）", flush=True)
        return result


def upload_and_verify(bucket, download_bucket, apk, object_key, sha, store):
    size = apk.stat().st_size
    uploaded = False
    try:
        headers = {key.lower(): value for key, value in bucket.head_object(object_key).headers.items()}
        uploaded = int(headers.get("content-length", -1)) == size and headers.get("x-oss-meta-sha256") == sha
    except oss2.exceptions.NoSuchKey:
        pass
    if uploaded:
        print("OSS 已有同摘要文件，跳过上传并重新下载校验", flush=True)
    else:
        oss2.resumable_upload(
            bucket, object_key, str(apk), store=store,
            multipart_threshold=1024 * 1024, part_size=4 * 1024 * 1024, num_threads=4,
            progress_callback=Progress("汇总进度（以各分片发送和确认为准）"),
            headers={
                "Content-Type": "application/vnd.android.package-archive",
                "Cache-Control": "private, max-age=0", "x-oss-meta-sha256": sha,
            },
        )
    # 使用 APP 实际下载域名校验，不把带签名的地址写入日志。
    url = download_bucket.sign_url("GET", object_key, 1800)
    digest = hashlib.sha256()
    downloaded = 0
    progress = Progress("下载校验")
    with urllib.request.urlopen(url, timeout=60) as response:
        if response.headers.get_content_type() != "application/vnd.android.package-archive":
            raise ValueError("OSS 文件类型错误")
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            downloaded += len(chunk)
            if downloaded > size:
                raise ValueError("OSS 文件大小超出发布记录")
            progress(downloaded, size)
    if digest.hexdigest() != sha or downloaded != size:
        raise ValueError("OSS 下载内容与正式签名包不一致")


def main():
    directory = Path("dist")
    manifest_path = directory / "stable.json"
    # 本次验证失败时，绝不遗留可被误部署的上次清单。
    manifest_path.unlink(missing_ok=True)
    release = json.loads((directory / "github-release.json").read_text(encoding="utf-8"))
    package, release_notes, github_asset, sha = validate_package(directory, os.environ["RELEASE_TAG"], release)
    apk = directory / package["asset"]
    size = apk.stat().st_size
    provider = os.environ.get("DOWNLOAD_PROVIDER", "oss").strip().lower() or "oss"
    if provider not in ("oss", "r2", "cf"):
        raise ValueError("DOWNLOAD_PROVIDER 只能为 oss 或 r2")
    object_key = f"luoxianlv/release/{package['versionName']}/{sha}/app-release.apk"
    if provider in ("r2", "cf"):
        from publish_r2 import create_client, upload_and_verify as upload_r2
        client, bucket = create_client()
        try:
            transfer_with_retry(lambda: upload_r2(client, bucket, apk, object_key, sha))
        finally:
            client.close()
    else:
        upload_oss(apk, object_key, sha)
    manifest = {
        "enabled": True, "channel": "stable",
        "latestVersionCode": package["versionCode"], "latestVersionName": package["versionName"],
        "apkUrl": "", "apkSha256": sha, "apkSize": size,
        "releaseNotes": release_notes,
        "mandatory": True, "minSupportedVersionCode": package["versionCode"],
        "channels": {
            "oss": {"object": object_key, "sha256": sha, "size": size},
            "github": {"url": github_asset["browser_download_url"], "assetId": github_asset["id"], "sha256": sha, "size": size},
        },
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"官方存储上传及回读校验通过（{provider}）：{sha} ({size} bytes)")


def transfer_with_retry(transfer):
    for attempt in range(3):
        try:
            transfer()
            return
        except Exception as error:
            print(f"传输第 {attempt + 1} 次失败（{type(error).__name__}）", flush=True)
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)


def upload_oss(apk, object_key, sha):
    oss2.defaults.connection_pool_size = 4
    endpoint = os.environ["OSS_ENDPOINT"]
    if not endpoint.startswith("https://"):
        endpoint = "https://" + endpoint
    bucket = ReportingBucket(
        oss2.Auth(os.environ["OSS_ACCESS_KEY_ID"], os.environ["OSS_ACCESS_KEY_SECRET"]),
        endpoint, os.environ["OSS_BUCKET"], connect_timeout=60,
    )
    download_bucket = oss2.Bucket(
        bucket.auth, "https://oss-luoxianlv.admilk.cn", os.environ["OSS_BUCKET"],
        is_cname=True, connect_timeout=60,
    )
    # APP 与官网统一使用 APK，按内容摘要隔离每次构建。
    # 同一次任务重试复用断点；不同任务不共享上传凭据与断点文件。
    with tempfile.TemporaryDirectory(prefix="oss-upload-") as checkpoint:
        store = oss2.ResumableStore(root=checkpoint)
        transfer_with_retry(lambda: upload_and_verify(bucket, download_bucket, apk, object_key, sha, store))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"官方存储发布失败（{type(error).__name__}）；未生成更新清单。", file=sys.stderr)
        sys.exit(1)
