import hashlib
import json
import os
from pathlib import Path
import sys
import time
import urllib.request

import oss2


def upload_and_verify(bucket, download_bucket, apk, object_key, sha):
    bucket.put_object_from_file(
        object_key, str(apk),
        headers={"Content-Type": "application/vnd.android.package-archive", "Cache-Control": "private, max-age=0"},
    )
    # APK downloads must use the bound domain, not the default OSS endpoint.
    url = download_bucket.sign_url("GET", object_key, 120)
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(url, timeout=60) as response:
        if response.headers.get_content_type() != "application/vnd.android.package-archive":
            raise ValueError("Unexpected OSS content type")
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    if digest.hexdigest() != sha or size != apk.stat().st_size:
        raise ValueError("OSS download does not match the signed APK")


def main():
    package = json.loads(Path("dist/package.json").read_text())
    release_notes = json.loads(Path("dist/release-notes.json").read_text())
    apk = Path("dist") / package["asset"]
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    size = apk.stat().st_size
    endpoint = os.environ["OSS_ENDPOINT"]
    if not endpoint.startswith("https://"):
        endpoint = "https://" + endpoint
    bucket = oss2.Bucket(
        oss2.Auth(os.environ["OSS_ACCESS_KEY_ID"], os.environ["OSS_ACCESS_KEY_SECRET"]),
        endpoint, os.environ["OSS_BUCKET"], connect_timeout=60,
    )
    download_bucket = oss2.Bucket(
        bucket.auth, "https://oss-luoxianlv.admilk.cn", os.environ["OSS_BUCKET"],
        is_cname=True, connect_timeout=60,
    )
    # APP 与官网统一使用 APK，按内容摘要隔离每次构建。
    object_key = f"luoxianlv/release/{package['versionName']}/{sha}/app-release.apk"
    for attempt in range(3):
        try:
            upload_and_verify(bucket, download_bucket, apk, object_key, sha)
            break
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)
    manifest = {
        "enabled": True, "channel": "stable",
        "latestVersionCode": package["versionCode"], "latestVersionName": package["versionName"],
        "apkUrl": "", "apkSha256": sha, "apkSize": size,
        "releaseNotes": release_notes,
        "mandatory": True, "minSupportedVersionCode": package["versionCode"],
        "channels": {"oss": {"object": object_key, "sha256": sha, "size": size}},
    }
    Path("dist/stable.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"OSS upload and public download verified: {sha} ({size} bytes)")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"OSS publication failed ({type(error).__name__}); stable manifest was not deployed.", file=sys.stderr)
        sys.exit(1)
