#!/usr/bin/env bash
# 固定源码、依赖及编译参数由独立 Go 工具仓库维护。
set -euo pipefail
source_directory="${LXUPDATE_SOURCE_DIR:-incremental-tools}"
exec bash "$source_directory/tools/build-hdiff-tools.sh" "$@"
