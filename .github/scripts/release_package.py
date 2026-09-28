"""下载正式 Release，并核对标签、元数据及 APK 内容摘要。"""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def validate_package(directory, tag, release):
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
        raise ValueError("版本标签格式错误")
    if release["tag_name"] != tag or release["draft"] or release["prerelease"]:
        raise ValueError("只能上传已公开的正式 Release")
    package = json.loads((directory / "package.json").read_text(encoding="utf-8"))
    asset_name = f"luoxianlv-{tag}-release.apk"
    if package["versionName"] != tag[1:] or package["asset"] != asset_name:
        raise ValueError("发布资料与版本标签不一致")
    if type(package["versionCode"]) is not int or package["versionCode"] <= 0:
        raise ValueError("版本号无效")
    apk = directory / asset_name
    with apk.open("rb") as stream:
        sha = hashlib.file_digest(stream, "sha256").hexdigest()
    checksum = (directory / f"{asset_name}.sha256").read_text().split()
    if checksum != [sha, asset_name]:
        raise ValueError("APK 与 SHA-256 文件不一致")
    asset = next(item for item in release["assets"] if item["name"] == asset_name)
    if asset["digest"] != f"sha256:{sha}" or asset["size"] != apk.stat().st_size:
        raise ValueError("APK 与 GitHub 发布记录不一致")
    notes = json.loads((directory / "release-notes.json").read_text(encoding="utf-8"))
    if not isinstance(notes.get("sections"), list) or not notes["sections"]:
        raise ValueError("更新说明缺失")
    for section in notes["sections"]:
        if not isinstance(section.get("title"), str) or not isinstance(section.get("items"), list):
            raise ValueError("更新说明格式错误")
        if not all(isinstance(item, str) for item in section["items"]):
            raise ValueError("更新说明条目必须是文字")
    return package, notes, asset, sha


def main():
    tag = os.environ["RELEASE_TAG"]
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
        raise ValueError("版本标签格式错误")
    repo = os.environ["GITHUB_REPOSITORY"]
    release = json.loads(subprocess.check_output(
        ["gh", "api", f"repos/{repo}/releases/tags/{tag}"], encoding="utf-8",
    ))
    if release["draft"] or release["prerelease"]:
        raise ValueError("只能上传已公开的正式 Release")
    directory = Path("dist")
    directory.mkdir(exist_ok=True)
    subprocess.run([
        "gh", "release", "download", tag, "--repo", repo, "--dir", str(directory),
        "--pattern", f"luoxianlv-{tag}-release.apk*", "--pattern", "package.json",
        "--pattern", "release-notes.json",
    ], check=True)
    (directory / "github-release.json").write_text(json.dumps(release), encoding="utf-8")
    package, _, _, sha = validate_package(directory, tag, release)
    print(f"Release 校验通过：{tag}，版本号 {package['versionCode']}，SHA-256 {sha}")


if __name__ == "__main__":
    main()
