"""在上传服务器前检查两个下载渠道是否指向同一份正式包。"""

import base64
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
    if "deliveryV1" in manifest:
        envelope = manifest["deliveryV1"]
        if set(envelope) != {"schema", "manifest", "signature", "trust", "trustSignature"} or envelope["schema"] != 1:
            raise ValueError("增量签名封套结构无效")
        for name in ("manifest", "signature", "trust", "trustSignature"):
            raw = base64.b64decode(envelope[name], validate=True)
            if not raw or len(raw) > 1024 * 1024 or base64.b64encode(raw).decode("ascii") != envelope[name]:
                raise ValueError("增量签名封套编码无效")
        signed = json.loads(base64.b64decode(envelope["manifest"], validate=True))
        target = signed["target"]
        cert = manifest.get("apkCertificateSha256", "")
        if (signed.get("type") != "apk-update" or signed.get("applicationId") != "app.luoxianlv" or
                signed.get("environment") != "production" or signed.get("variant") != "release-universal" or
                not re.fullmatch(r"[0-9a-f]{64}", cert) or
                (target.get("versionCode"), target.get("versionName"), target.get("sha256"), target.get("size"), target.get("certificateSha256")) !=
                (code, version, sha, size, cert)):
            raise ValueError("增量说明目标与全包发布记录不一致")
        # 真正的根授权验签由 Go 发布验证器及服务端执行；这里仅检查部署资料一致性。


if __name__ == "__main__":
    validate_manifest(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")))
    print("更新清单结构与双渠道摘要校验通过")
