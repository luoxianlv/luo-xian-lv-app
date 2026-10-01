# 原生热更候选 CI

`原生热更候选构建` 工作流独立于现有 APK Release 和 OSS 流程，仅支持手动选择 Debug/Release。它测试构建工具和热更核心，再读取实际三层 APK，导出接口、资源、依赖、优化状态、编译 SDK 和内容哈希，保存产物 14 天。

工作流只有仓库读取权限，不读取签名私钥、管理员令牌、生产 OSS 凭据或热更源站配置，不签名热更包、不发布渠道、不触发 APK Release。Release 候选中的宿主 APK 尚未接入正式安装签名，不能把 artifact 当作可发给用户的正式安装包。

Release 候选启用 `nativeOptimize=true`，导出后调用 `verify-native-release.ps1 -ExpectOptimized -CompileOnly` 核验实际三层 SDK、资源、映射及内置组合。Debug 保留调试构建。报告标记 `compiled-artifact-only`，不代替设备、灰度、安全激活和回退验收。下载产物后仍需通过 CLI 核验、冻结、签名、上传和发布的独立步骤。

常规 `CI` 在 PR 和 main 推送时也运行同一优化 Release 核验，独立使用 JDK 21；既有 JDK 17 默认 Debug/Release 构建继续保留。只归档公开核验报告，不读取生产配置或签名凭据。`CompileOnly` 成功仅证明编译产物一致，报告仍为 `releaseReady=false`，正式发包须配置生产公开信任根、证书并通过正式签名核验。
