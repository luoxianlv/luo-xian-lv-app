"""核对增量引擎的固定第三方源码原字节，不运行或下载上游代码。"""

import hashlib
import json
from pathlib import Path

vendor = Path(__file__).resolve().parent.parent / "modules/update-core/src/main/cpp/vendor"
lock = json.loads((vendor / "sources.lock.json").read_text(encoding="utf-8"))
for name, expected in lock["files"].items():
    relative = Path(name)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("第三方源码锁含无效路径")
    file = vendor / relative
    if file.is_symlink() or not file.is_file():
        raise ValueError(f"第三方源码文件缺失或类型无效：{name}")
    actual = hashlib.sha256(file.read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError(f"第三方源码摘要不匹配：{name}")
print(f"固定增量引擎源码校验通过：{len(lock['files'])} 个文件。")
