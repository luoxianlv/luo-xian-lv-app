# 原生三层候选与正式打包入口

仅完成本地改造和检查，没有触发 GitHub Actions、读生产签名密钥、上传 OSS 或发布。

`hot-candidate.yml` 的 Release 选择现在启用 `nativeOptimize=true`，导出实际三层产物后执行同一份 `verify-native-release.ps1 -ExpectOptimized -CompileOnly`，归档公开 SDK、映射、APK 和核验 JSON。这条流程只生成候选，未签名不算正式发版。核验器按平台选择 `apksigner` 或 `apksigner.bat`，Windows 和 Linux 不另维护两套 SDK 规则。

现有 Release 手动入口新增 `native_package`，默认 false；标签触发与旧标签继续使用既有打包路径。显式 true 时要求源码具备三层模块、PowerShell 7，以及仓库变量 `NATIVE_HOT_CONFIG_JSON` 的生产公开配置。配置限定 `app.luoxianlv/production/HTTPS`，详细信任和字段仍由已有 `CopyHotConfig` 校验。未知开关、旧源码、缺配置或范围检查失败，在解码签名文件前拒绝，不会悄悄降级为旧 APK。

原生路线复用既有四个签名属性，构建宿主/运行时/业务并启用严格正式产物核验：要求预期公开证书、真实 SDK 成员/层级、映射、分层资源 ID、编译标记与内置基线字节一致；未签名和 Android Debug 证书拒绝。最终下载 APK 来自 `app-host`，同时保存三层 report、host/runtime SDK、runtime/business APK 和核验 JSON。映射归档覆盖该目录；原有版本号、发布说明、SHA256 和分离的后续发布流程沿用。

本地 `actionlint`、Bash 语法和 PowerShell 解析检查通过。隔离签名/服务环境的五个实际 Shell 拒绝案例通过；其中范围失败使用 `jq` 失败替身，证明检查失败后的流程隔离，不能当作真实 jq 成功路径或签名构建验证。完整发布脚本单元测试 24 项通过，依赖安装在忽略的 `.local/ci-tests-venv`，没有改全局 Python。

真实三层优化产物的 Windows 核验已在此前通过，但本轮改造后的 Linux CI、正式配置、正式证书与发布动作尚未执行。用户确认切换并提供生产公开配置后，才能启用正式原生路线；这份入口不是提前发版授权。

复用已有构建与核验器，开关只选择产物路线，避免复制签名/协议逻辑；独立环境中的失败测试覆盖会影响发布的边界。
