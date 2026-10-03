"""仅允许部署本仓库主分支中成功完成官方存储校验的运行产物。"""

import json
import os
from pathlib import Path
import sys


def validate_run(run, repository):
    expected = {
        "path": ".github/workflows/oss.yml",
        "event": "workflow_dispatch",
        "head_branch": "main",
        "status": "completed",
        "conclusion": "success",
    }
    if run["repository"]["full_name"] != repository or any(
        run.get(key) != value for key, value in expected.items()
    ):
        raise ValueError("更新清单必须来自本仓库 main 分支成功的官方存储工作流")


if __name__ == "__main__":
    validate_run(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")), os.environ["GITHUB_REPOSITORY"])
    print("官方存储运行来源与结果校验通过")
