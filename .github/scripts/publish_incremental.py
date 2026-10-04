"""上传之前重新认证并重建全部补丁；R2 完整回读之后才返回可部署说明。"""

import base64
import json
import os
from pathlib import Path
import subprocess

from prepare_incremental import tool_args
from publish_r2 import upload_and_verify


def verify_and_upload(client, bucket, target_apk, package, sha, retry):
    directory = Path("dist/incremental")
    delivery_path = directory / "delivery.json"
    if not delivery_path.exists():
        return None
    root_expected = os.environ.get("UPDATE_ROOT_PUBLIC_JSON", "")
    if not root_expected or json.loads(root_expected) != json.loads((directory / "root.public.json").read_text(encoding="utf-8")):
        raise ValueError("差分产物的更新根公钥与发布配置不同")
    paths = json.loads((directory / "history.json").read_text(encoding="utf-8"))
    args = [os.environ["LXUPDATE_BIN"], "verify", *tool_args(), "--delivery", str(delivery_path),
            "--target", str(target_apk), "--out", str(directory)]
    for path in paths:
        args.extend(["--history", path])
    subprocess.run(args, check=True)
    delivery = json.loads(delivery_path.read_text(encoding="utf-8"))
    manifest = json.loads(base64.b64decode(delivery["manifest"], validate=True))
    target = manifest["target"]
    if (target["versionCode"] != package["versionCode"] or target["versionName"] != package["versionName"] or
            target["sha256"] != sha or target["size"] != target_apk.stat().st_size):
        raise ValueError("签名目标与正式发布包证据不同")
    for delta in manifest["deltas"]:
        patch = delta["patch"]
        path = directory / (patch["sha256"] + ".hpatch")
        retry(lambda: upload_and_verify(client, bucket, path, patch["object"], patch["sha256"],
                                       content_type="application/octet-stream"))
    return delivery, target["certificateSha256"]
