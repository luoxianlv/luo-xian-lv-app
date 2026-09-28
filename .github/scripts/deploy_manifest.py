"""服务器端原子替换更新清单，拒绝降级和同版本换包。"""

import json
from pathlib import Path
import shutil
import sys


def deploy(incoming):
    current = incoming.parent / "stable.json"
    try:
        new = json.loads(incoming.read_text(encoding="utf-8"))
        if current.exists():
            old = json.loads(current.read_text(encoding="utf-8"))
            if old.get("latestVersionCode", 0) > new["latestVersionCode"]:
                raise ValueError("拒绝部署更旧的版本")
            if old.get("latestVersionCode") == new["latestVersionCode"] and old.get("apkSha256") != new["apkSha256"]:
                raise ValueError("拒绝用不同安装包替换同一个版本")
            shutil.copy2(current, current.with_suffix(".previous.json"))
        incoming.chmod(0o644)
        incoming.replace(current)
        print("更新清单已原子替换")
    finally:
        incoming.unlink(missing_ok=True)


if __name__ == "__main__":
    deploy(Path(sys.argv[1]))
