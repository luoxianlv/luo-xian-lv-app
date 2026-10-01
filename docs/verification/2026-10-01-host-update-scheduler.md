# 普通宿主使用真实 UpdateSchedule

本次将普通入口的发现调度接入既有 `hot-core/UpdateSchedule`，删除 `HostUpdates` 重复的固定五分钟轮询和五秒起步独立退避。正常查询为 60 秒、±20% 抖动（48–72 秒），实际宿主一秒使用状态探测会带来不足一秒的唤醒误差；失败按 60/120/240/480/960/1800 秒基数退避并加抖动，最大30分钟。生命周期或网络恢复只合并触发，普通失败退避及已解析的 `Retry-After` 不会被绕过。当前 HTTP 层本身已将秒数形式的 Retry-After 限制到30分钟，本次沿用该规则。

## 真实状态和生命周期

- 允许检查的使用来源是现有 `PageSwapHost.inUse` 页面，或 `NativeAccessibilityService.playbackInUse` 的真实演奏；仅存在无障碍连接、空闲浮窗或前台服务通知不会另建更新保活。
- `Bootstrap.playbackPreparing` 只读既有 `PlaybackBridge.current().query("state")` 的 `preparing/loadingSong/waitingToPlay` 基础值；正常 `playing=true` 可继续发现。`PracticeBridge.active && !ready` 也让演练场采样、键盘和首帧准备优先。没有新增 SDK/default 契约，没有将 `process.canReplace=false` 误当成准备状态。
- 查询缺少明确状态、返回空/null 或抛错时暂缓更新，调度器不向主线程抛出业务查询异常，也不主动改变播放连接或健康状态。连接自己的故障恢复仍由原宿主门禁处理。
- `ConnectivityManager.registerDefaultNetworkCallback(..., main)` 合并网络变化；没有可用 INTERNET 网络时不启动发现或候选许可。非 test 环境另要求 VALIDATED，test 可使用本机源站而不要求公网验证。停止调度时撤销回调，迟到回调不能复活任务。
- 仅在上述已有真实使用期间，每秒轻量探测准备状态；纯后台无使用时不设置 pulse 或网络轮询。准备/离线/生命周期退出将协作取消在途工作，断点由原下载器保留。已有单线程 worker 使用 Android 后台优先级；取消不增加失败次数，本地空间/流量等待按正常间隔重试。

## 候选和门禁

下载后的候选可立即作为同轮后续工作请求许可，不额外等待60秒；后续请求仍串行，失败退避、Retry-After 和取消后的最小间隔不能被该路径绕过。等待激活安全点时仍可按正常间隔发现更新，不能因正常演奏的 `canReplace=false` 长期停掉查询。`pending` 对 worker 可见，缓存清理额外保护它指向的完整候选；准备结果替换后旧候选才放开保护。

候选的五分钟内存有效期保留为 `PREPARED_TTL`，它不是轮询周期。`nextCheck` 仅由 schedule 推导供既有仪器诊断读取，不参与调度判断。许可重新取得、兼容校验、激活安全点、整组观察及冷启动60秒累计健康门禁均沿用原实现；启动时的短超时缓存候选许可路径也沿用原规则。

## 已执行和仍待验证

执行 `tools/test-host-update-scheduler.ps1`：17项 JUnit 全通过。脚本使用已存在的 Android SDK37、宿主/core/contract 编译输出和 JUnit，不运行 Gradle、不读配置凭据、不发送 HTTP。每轮重新编译本次真实 `UpdateSchedule/HostUpdates/Bootstrap` 源码；平台网络、时钟、Handler 和 worker 为可控 JVM 替身，宿主调度组的生命周期输入也为替身，第二组状态查询则执行真实 Bootstrap。

覆盖包括正常抖动、失败指数退避、重回前台/网络恢复不绕过退避、同轮许可串行、取消与本地等待、真实 HostUpdates.tick 的后台无请求/无计时、离线不请求、正常演奏可检查、准备时取消、等待安全点继续发现、停止撤销回调、真实 retry 回调保留包装后的 Retry-After，以及 Bootstrap 查询异常/未知状态和演练场准备优先。另使用实际 SDK37 离线 javac 一起编译三份生产源码通过，`javap` 无需新增契约。

本轮未操作 Gradle、设备、现有 API 或渠道。根代理在本轮共享源码安全点已成功构建 Debug 和 testAPK；该构建早于最后的 pending 保护和演练场准备优先增量，最终 APK 与真实网络回调、48–72秒设备查询间隔、后台停轮询、正常演奏和准备抢占还需根代理统一构建后验收。JVM 替身通过不能代替这些设备结果，也不证明生产 OSS、Release 统计或发布链路。

共同的时间、合并和退避状态集中在 UpdateSchedule，HostUpdates 只接生命周期/网络/worker，Bootstrap 只提供现有状态的只读判断；复用已有基础值通道而不扩展 SDK，保持职责明确并避免两套策略漂移。

## 真实设备观察 helper（准备完成，仍待实际执行）

新增 `app-host/src/androidTest/java/app/luoxianlv/host/NativeSchedulerChecks.java`；根代理负责 NativeAppInstrumentation 接线。最短默认调用是 `NativeSchedulerChecks.run(this, main)`，或第三参数 `false`。建议 runner 单独分支参数 `nativeSchedulerOnly=true`，但具体参数是否存在以根代理接线为准；helper 本身没有修改 runner。

门禁要求实际 Debug 包 `app.luoxianlv.debug`、工作线程、已安装配置 automatic/test/127.0.0.1:18472。它反射只读当前 Bootstrap.updates 与真实 UpdateSchedule，绑定同一业务来源；不写 clock/due，不直接调用 tick/request/check/activate，不修改许可、健康或更新状态。

默认依次执行：

1. 在在线、前台、无准备/候选/观察事务、没有失败退避的普通入口，等下一轮自然完成。以相邻20ms采样的单调时间为真实完成时刻上下界，要求观察窗口不超过500ms；报告 due 减完成上下界与48–72秒范围相交，容许观察上下界的500ms误差。先前已完成的旧轮询不能冒充新完整周期。
2. 使用保留真实无障碍服务的 UiAutomation 发送系统 HOME。要求 actual active/usable=false、worker和schedule不在途、无pulse；不接管任何正在演奏的用户服务。真实等待至少75秒，并超过原due至少3秒；全程lastStart不变。API29及以上用公开Handler.hasCallbacks（反射避免API26硬链接）；API26用公开Handler.dump只判断pulse回调类，不保存或输出原始队列文本，并且必须先通过前台有pulse的阳性对照。
3. 用 production MainActivity intent 重新置前原窗口，要求原Activity仍存活且恢复焦点与前台生命周期。观察自然合并的一次成功检查，随后至少5秒lastStart不变、无并行任务。已经在前台的窗口不会为清理再次发送Intent。

默认通常需要130–170秒，冷启动观察/首轮网络耗时可能更久，建议外部仪器超时至少300秒，追加演练场观察时至少450秒。它不会加速等待，若前台出现真实失败、候选变化、业务来源切换或原首页被系统销毁，则失败，不生成新的成功JSON。

可选第三参数 `true` 追加实际 PracticeActivity 开启，观察真实PracticeBridge.active&&!ready时Host.priorityWork=true，再等真实ready与窗口焦点且priorityWork恢复。关闭stage并恢复原Main后才成功。没有Bridge状态注入，没有手势/音频命令；只证明实际准备阶段被识别，不宣称已经验证活跃对象下载抢占。首次全屏提示只允许已实际观测的配对英文标题Viewing full screen和Got it/GOT IT，标题/按钮同包且包名为android或com.android.systemui；应用授权/更新对话框不会被点击。

另有 `NativeSchedulerChecks.offline(this, main)`：只能在根代理已通过外部ADB断网、普通前台空闲周期没有长退避之后调用。helper本身不改系统网络，观察actual online=false并真实等待至少75秒越过due，要求lastStart不变。外部网络恢复亦由根代理控制，不由helper伪造回调。

默认成功返回JSONObject，保存应用files/native-host-scheduler-report.json：normal/background/resumed各阶段包含真实elapsedMs/lastStartMs/nextDueMs、inFlight/busy/active/online/pulse等状态，另有sourceIdentity、homeRestored/stageClosed及未注入声明。离线回执为files/native-host-scheduler-offline-report.json。只在整段观察与清理成功后写入JSON；异常抛AssertionError并尝试关闭自己启动的stage、恢复原Main。门禁通过后开始会清除该模式旧JSON；门禁失败时不修改应用状态，调用方必须依据本次仪器结果判断，不能读取旧JSON冒充本次成功。

helper已用实际Android SDK37与当前宿主/core/contract编译输出独立javac编译通过。尚未运行它的Gradle或设备检查；API26 pulse格式、HOME生命周期、真实周期及可选stage/断网结果需要根代理实际执行，源码和编译成功均不替代设备结论。
