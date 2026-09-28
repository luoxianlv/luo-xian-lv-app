"""在上传服务器前检查两个下载渠道是否指向同一份正式包。"""

import json
from pathlib import Path
import re
import sys


def validate_manifest(manifest):
    version = manifest.get("latestVersionName", "")
    sha = manifest.get("apkSha256", "")
    code = manifest.get("latestVersionCode")
    size = manifest.get("apkSize")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) or not re.fullmatch(r"[0-9a-f]{64}", sha):
        raise ValueError("版本或摘要格式错误")
    if type(code) is not int or code <= 0 or type(size) is not int or size <= 0:
        raise ValueError("版本号或文件大小无效")
    if manifest.get("enabled") is not True or manifest.get("channel") != "stable":
        raise ValueError("不是有效的正式更新清单")
    channels = manifest["channels"]
    for name in ("oss", "github"):
        if channels[name]["sha256"] != sha or channels[name]["size"] != size:
            raise ValueError("下载渠道的内容摘要不一致")
    expected_object = f"luoxianlv/release/{version}/{sha}/app-release.apk"
    expected_url = f"https://github.com/luoxianlv/luo-xian-lv-app/releases/download/v{version}/luoxianlv-v{version}-release.apk"
    if channels["oss"]["object"] != expected_object or channels["github"]["url"] != expected_url:
        raise ValueError("下载地址与版本不一致")


if __name__ == "__main__":
    validate_manifest(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")))
    print("更新清单结构与双渠道摘要校验通过")
