"""只从正式发布原 APK 准备普通更新差分；不上传，也不修改正式原包。"""

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

from release_package import validate_package


SIGNING_FIELDS = (
    "UPDATE_ROOT_PUBLIC_JSON", "UPDATE_TRUST_JSON", "UPDATE_TRUST_SIGNATURE_JSON",
    "UPDATE_CONTENT_KEY_BASE64", "UPDATE_CONTENT_KEY_PASSWORD",
)


def enabled():
    if os.environ.get("UPDATE_INCREMENTAL_PREPARE", "false") != "true":
        return False
    populated = [bool(os.environ.get(field, "").strip()) for field in SIGNING_FIELDS]
    if not any(populated):
        print("普通更新签名配置未提供；继续准备完整 APK。")
        return False
    if not all(populated):
        raise ValueError("普通更新签名配置不完整")
    return True


def tool_args():
    return [
        "--hdiffz", os.environ["HDIFFZ"], "--hpatchz", os.environ["HPATCHZ"],
        "--tools-lock", os.environ["HDIFF_LOCK"],
        "--aapt", os.environ["ANDROID_AAPT"], "--apksigner", os.environ["ANDROID_APKSIGNER"],
        "--root-public", "dist/incremental/root.public.json",
        "--application-id", "app.luoxianlv", "--environment", "production",
    ]


def inspect_apk(path):
    return json.loads(subprocess.check_output([
        os.environ["LXUPDATE_BIN"], "inspect", "--target", str(path),
        "--aapt", os.environ["ANDROID_AAPT"], "--apksigner", os.environ["ANDROID_APKSIGNER"],
    ], encoding="utf-8"))


def match_package(actual, package, sha, size):
    if (actual["applicationId"] != "app.luoxianlv" or actual["versionCode"] != package["versionCode"] or
            actual["versionName"] != package["versionName"] or actual["sha256"] != sha or actual["size"] != size):
        raise ValueError("正式 APK 实际版本与 GitHub 发布元数据不一致")


def histories(directory, target_code, release_tag):
    repo = os.environ["GITHUB_REPOSITORY"]
    # 限定最新 100 条 Release，正式 versionCode 再从已验证的 package.json 读取。
    records = json.loads(subprocess.check_output(
        ["gh", "api", f"repos/{repo}/releases?per_page=100"], encoding="utf-8",
    ))
    candidates = []
    for release in records:
        tag = release.get("tag_name", "")
        if release.get("draft") or release.get("prerelease") or tag == release_tag or not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
            continue
        # 仅选择具有完整原包、package 与校验文件证据的发布。
        names = {item["name"] for item in release.get("assets", [])}
        asset = f"luoxianlv-{tag}-release.apk"
        if not {asset, asset + ".sha256", "package.json", "release-notes.json"}.issubset(names):
            continue
        path = directory / "history" / tag
        path.mkdir(parents=True, exist_ok=False)
        subprocess.run(["gh", "release", "download", tag, "--repo", repo, "--dir", str(path),
                        "--pattern", "package.json"], check=True)
        package_raw = (path / "package.json").read_bytes()
        package_asset = next(item for item in release["assets"] if item["name"] == "package.json")
        if (package_asset.get("digest") != "sha256:" + hashlib.sha256(package_raw).hexdigest() or
                package_asset.get("size") != len(package_raw)):
            raise ValueError("正式历史 package.json 与 GitHub 资产证据不一致")
        package = json.loads(package_raw)
        code = package.get("versionCode")
        if type(code) is not int or code < 1:
            raise ValueError("正式历史发布的版本号无效")
        if code < target_code:
            candidates.append((code, path, release))
    candidates.sort(key=lambda item: item[0], reverse=True)
    selected = candidates[:3]
    if len({code for code, _, _ in selected}) != len(selected):
        raise ValueError("正式历史发布含重复 versionCode")
    result = []
    for _, path, release in selected:
        tag = release["tag_name"]
        subprocess.run(["gh", "release", "download", tag, "--repo", repo, "--dir", str(path),
                        "--pattern", f"luoxianlv-{tag}-release.apk*", "--pattern", "release-notes.json"], check=True)
        package, _, _, sha = validate_package(path, tag, release)
        apk = path / package["asset"]
        match_package(inspect_apk(apk), package, sha, apk.stat().st_size)
        result.append(str(apk))
    return result


def prepare():
    if not enabled():
        return
    directory = Path("dist")
    incremental = directory / "incremental"
    # 独立目录不得混入过去运行的补丁与说明。
    incremental.mkdir(mode=0o700, exist_ok=False)
    for field, filename in (("UPDATE_ROOT_PUBLIC_JSON", "root.public.json"),
                            ("UPDATE_TRUST_JSON", "trust.json"),
                            ("UPDATE_TRUST_SIGNATURE_JSON", "trust.sig.json")):
        raw = os.environ[field].encode("utf-8")
        json.loads(raw)
        (incremental / filename).write_bytes(raw)
    key = incremental / "content.encrypted.json"
    key.write_bytes(base64.b64decode(os.environ["UPDATE_CONTENT_KEY_BASE64"], validate=True))
    key.chmod(0o600)
    release = json.loads((directory / "github-release.json").read_text(encoding="utf-8"))
    package, _, _, sha = validate_package(directory, os.environ["RELEASE_TAG"], release)
    apk = directory / package["asset"]
    match_package(inspect_apk(apk), package, sha, apk.stat().st_size)
    base_paths = histories(incremental, package["versionCode"], os.environ["RELEASE_TAG"])
    (incremental / "history.json").write_text(json.dumps(base_paths), encoding="utf-8")
    args = [os.environ["LXUPDATE_BIN"], "build", *tool_args(), "--target", str(directory / package["asset"]),
            "--out", str(incremental), "--key", str(key), "--password-env", "UPDATE_CONTENT_KEY_PASSWORD",
            "--trust", str(incremental / "trust.json"), "--trust-signature", str(incremental / "trust.sig.json")]
    for path in base_paths:
        args.extend(["--history", path])
    try:
        subprocess.run(args, check=True)
    finally:
        key.unlink(missing_ok=True)
    print("普通更新差分与签名说明已准备；等待官方存储完整回读。")


if __name__ == "__main__":
    prepare()
