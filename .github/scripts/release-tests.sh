set -euo pipefail
scripts=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$scripts/release-build-tasks.sh"
source "$scripts/gradle-ci-args.sh"
select_release_tasks "${NATIVE_RELEASE_PACKAGE:-false}" false
# 打包由并行的 package Job 负责；本任务只运行真实的单元测试入口。
unset "release_tasks[${#release_tasks[@]}-1]"
configure_ci_gradle
bash ./gradlew "${release_tasks[@]}" "${ci_gradle_args[@]}"
