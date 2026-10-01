# 无页面pending新运行时：限定矩阵工具

本轮只修改androidTest及独立工具，不修改生产HostUpdates、Bootstrap、SDK、许可或健康逻辑。未运行Gradle、设备、API、签名命令或读取私钥/token。主任务已反馈普通入口真实完整缓存最新组合并建立原样checkpoint；服务矩阵尚未由本子任务执行，以下不是设备成功声明。

## 候选与实际契约

根任务新fixture为最新业务与`nativeRuntimeProbe`同轮SDK绑定：target `9eeafb917d0f3075be49f3b63e88a1d9fa868ddf2a7e282953d0ce66b26b68a4`、runtime `0a8c03ad6f5cd0b0d52109fa0b902e9991670b1674ab08579136701a4d032df2`、business `3e21cd9a01423d1712196509fbc21236d9fd3bb0d99c7691bba941dfa3ff83d3`。源为当前真实稳定C，原runtime `c62c203433e3c03c1bd80acb127469d8ad60eeed49016430a2efdd162663ba0d`。准备沿已有NativeRestartChecks，不构造pending指针，也不从旧业务拼接绕过编译SDK身份。

新增NativeServiceMatrixPlan只有只读预期；NativeServiceColdInstrumentation仅在明确`matrixRunId`时启用矩阵，不影响既有STABLE/TRIAL入口。新增PREPARING只允许该opt-in契约。每case核对真实缓存全对象、实际Source/稳定/pending/runtime、resident runtime与工厂/业务连接、版本/信任下限、无窗口、健康0及隔离保持。

| case | accessibility | foreground | 共同要求 |
| --- | --- | --- | --- |
| valid | Source=target，TRIAL，真实无障碍业务就绪 | Source=target，PREPARING，固定事件证明前台承诺/正常空闲策略 | 新marker和kotlin.Unit均由同一个新runtime父加载器提供；空闲健康0 |
| revoked / expired / offline | Source=原稳定C，STABLE，旧运行时，无障碍连接 | Source=原稳定C，STABLE，旧运行时，前台承诺/策略兑现 | pending目标保留，缓存完整，新marker不存在，不隔离目标、不混装运行时 |

foreground valid不能硬断TRIAL：无页面、无无障碍且空闲策略正常自停时，现有coldFramesReady没有可曝光页面/播放，因此合法保持PREPARING。测试不延长服务、不强行曝光或直接累计健康。Class.forName只使用`initialize=false`观察marker，不初始化测试类；拒绝场景不能从另一个加载器偷偷提供marker。

## 单case外部driver

```powershell
pwsh -NoProfile -File tools/test-native-service-matrix.ps1 `
  -PlanPath '<CLI/.local内公开plan-valid-accessibility.json>' `
  -Checkpoint no_backup/native-service-checkpoint-20261001
```

Plan严格JSON：schema=1、case=valid/revoked/expired/offline、consumer=accessibility/foreground、sourceSnapshot/sourceRuntime/targetSnapshot/targetRuntime四个公开SHA256、minRevision/minTrustVersion非负整数、grantExpirationEpoch（仅expired大于0，其余0）。每run生成新32hex，仪器报告必须带相同run/case/target及全部真实条件；旧run、错误runtime、已有健康、页面、错误下限或状态注入均拒绝。Case是否真实撤回/有权由根任务API/签名证据绑定，`scenarioConditionProvenByInstrument=false`保留；不能单靠case标签声称已经证明根因。

driver限定emulator-5554、API原始18472→18472及CLI本机公开计划路径。它不调用API/CLI、不读授权文件。每case保存原无障碍设置，移除自身完整/简写组件，force-stop并确认无PID；核对run-as绝对私有目录及普通no_backup/checkpoint/native-update目录。当前native-update整体移动到新run归档，随后`cp -a`原样checkpoint恢复为测试分支，任何失败不删除旧目录。谱子、FastKV、壁纸等files不动。报告明确`checkpointRestoredByDriver=true`，不能假称driver没有恢复测试状态或证明持久下限从未分支重放；每次仪器内部仍只读、不注入生产状态。

valid/revoked/expired使用本机reverse；offline移除仅这个固定reverse，证明本机API不可达，不称整台系统无互联网。expired等待真实UTC超过grantExpirationEpoch，再启动冷服务；记录仪器真实系统时间前后，不改时钟。

总限255秒，主动240秒/收尾15秒；单ADB命令5秒、实际仪器最长120秒。进程隐藏且ArgumentList传参，stdout/stderr并发读取。先保存原始仪器与安全device-report，再核对通过；最后停止自己的Debug进程、恢复并复查原设置/reverse，不打开主页，防止valid闲置case被主页实际使用而转健康。环境全部核对后才写最终report.json。其后根任务恢复原渠道/归档测试pending并打开普通主页。旧case archive、失败、用户数据和原checkpoint全部保留。

## 过期签名准备

Manifest只有createdAt，没有候选expiresAt；不能凭改标签模拟过期。最小真实过期case为独立测试内容键grant短notAfter，同时根TrustDocument、激活键与稳定C原内容键维持长有效授权。普通入口须在短grant有效时完整缓存，再跨真实notAfter进行服务冷启动；当前内容授权拒绝新组合，稳定C仍可验证。CLI trust issue统一grant时间时，可由主任务既有本机测试fixture签发单grant短期限，不修改生产格式。缓存旧激活permit或注入时钟不属于本方案；若另测激活permit过期，需真实签发后延迟且另记边界。

## 离线验证与限制

`tools/test-native-service-matrix-checks.ps1`实际PowerShell解析/23项策略检查通过：两入口真实阶段、三种拒绝保持原来源、旧run/健康推进/页面/状态注入/隔离/runtime/时间/PID负例、真实过期时刻、重复JSON、无删除/API/凭据及隐藏进程边界。未执行ADB。

实际NativeServiceColdInstrumentation、新Plan和既有固定日志解析器独立javac通过。5项JVM使用真实计划与真实独立URL父加载器验证：valid入口阶段、拒绝组合、非法身份、marker共享父/不初始化/拒绝加载及缺marker失败。JVM替身不证明Android服务、网络授权、checkpoint复制或任何case已经设备通过；根任务须统一构建testAPK，再逐case保存API/签名准备与设备/driver回执。没有为验收增加生产协议或修改安全门禁。

【MCP调用简报】本地限定源码/公开文档与独立javac/JVM、PowerShell解析及策略23项；无Gradle、设备/API/签名、凭据或push。
