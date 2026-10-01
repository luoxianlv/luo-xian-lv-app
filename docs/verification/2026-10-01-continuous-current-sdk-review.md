# 最新 SDK 连续候选工具检查点

本轮只读复审先核对 runner/network driver 与 `2920f51` 文件协议。runner 的 `networkRecovery` 参数只进入 `NativeSchedulerChecks.offlineRecovery`；同 run 的独立目录、同 phase ACK 和实际系统状态共同门禁闭合。root 已按 `c987b7b` 将 driver 改为300秒配置期限、两网络独立还原并重读原值、失败停止残留仪器、完整还原后再保存/打印 driver 成功。ADB命令和设备主线程不是硬实时，不能把此期限称为任意系统故障下绝对 wall-time 保证；本次没有运行网络设备检查。

## 已确认并修复

1. 旧连续 helper 在阶段起步/结束写 `HostUpdates.nextCheck`，该字段现为纯诊断，不能控制实际 UpdateSchedule。暂停只设 blocked，不改变 schedule.usable，第二轮起可能保留48–72秒自然 due。旧 driver 最后一轮只有150秒，而 helper 分别允许曝光100秒、窗口/焦点各15秒、回退40秒、回执30秒和退出5秒，合法流程会被外层提前终止。
2. 旧 Fresh 顺序在 install 之后才移除本Debug无障碍组件，且归档时没有证明目标 PID 已停止；启用服务的安装/重绑定路径会使状态目录可能在启动期间被移动。现先保存原值，移除本目标的完整与简写组件名，保留其他组件，force-stop 并确认 PID 不存在；确认私有目录后将旧 native-update/native-continuous 归档到同目录新 run 后缀，再 install。没有删除旧隔离、预算、故障证据或用户项目。当前 Fresh 需要已有可 run-as 的 Debug 安装，无法确认私有目录就安全失败。
3. `HotProbeFactory` 原有 AppBusinessFactory 委托没有覆盖新增 Java default `bindResources`，默认空方法绕过真实 Gate 绑定。现显式转发到实际 AppBusinessFactory；稳定 SDK 没有修改。本轮仍严格限制 base/candidate 为 runtime+business 两种 artifact、无官方 mount，不把本轮 APK资源/SDK持有检查冒充官方挂载资源压力。

## 共同阶段期限与证据

唯一配置常量是 `NativeContinuousChecks.STAGE_TIMEOUT_MILLIS=330000`，driver 从实际 Java 源读取，并放入计划。实际仪器的 ready/running/passed/complete 状态和阶段回执回传该值；不一致则 driver 在发布之前拒绝，旧 testAPK 不能静默套用新配置。

GO确认后的曝光、新增窗口、真实健康/回退、回执确认和退役观察共用一个330秒阶段总期限，不继续拼接各自独立时限；driver 发出 GO 后用同值等待当前阶段完成，再核对同run/PID/index/snapshot/business与真实阶段回执。至少三轮不同签名快照和不同业务APK、最后一轮受控故障回退仍保留；每个健康阶段要求实际 HealthWindow observedMillis>=60000，没有修改健康时钟、schedule.due或失败退避。正常 scheduler 等待属于阶段真实时间，不写诊断nextCheck来伪称加速或延后。

driver 只有收到本阶段真实健康回执后才将其登记为后续 fallback。最终报告同时绑定本run、PID、阶段数量和共同期限。失败分支不再自行清除宿主真实 blocked 门禁，driver 在收尾停止本次Debug进程后恢复原无障碍设置并重开首页。系统命令本身和框架主线程仍不是硬实时，外层超时只是验收配置，不是生产调度期限。

`ProcessOnce.completedAt("storage-migration")` 对当前 claim 用法记录登记时间，不是异步操作完成时刻。新回执改名 `storageMigrationRegisteredAt` 并标记 `migrationTimestampRepresentsAsyncCompletion=false`，仅检查登记未随热更重复。

## 独立验证

`tools/test-native-continuous-checks.ps1` 已通过：

- 16项实际脚本 policy 检查（只用 fake ADB），覆盖完整/简写目标服务剔除、其他含 `$` 的服务保留与单引号 shell 参数、Fresh目标停止/PID确认/归档/install顺序、旧先install后归档负例、额外artifact/官方mount拒绝、nextCheck写入消除及共同期限存在。fake ADB不宣称实际安卓服务或安装行为已验收。
- 独立 javac 编译当前 NativeContinuousChecks 与 HotProbeFactory，通过。
- 使用实际已编译 AppBusinessFactory 和 OfficialRendererGate 调用新工厂的 bindResources，真实 Gate 收到同一资源对象且 required=true；仅在忽略目录删除该 override 的旧逻辑对照明确失败。没有假 delegate，没有创建WebView或渲染成功注入。
- 4项实际 awaitStage JVM边界通过：320秒合法流程不被旧短期限截断、330秒用尽失败、剩余预算不重置、超期即使完成谓词为true也拒绝。只有测试JVM的平台clock/sleep为替身，未改变设备或实际 HealthWindow。

本子任务没有运行 Gradle、设备、API、渠道、OSS、密钥或 push。root当前网络/其他候选的设备结果不包含这批连续工具修复；须统一重建testAPK和四个匹配最新普通基线的probe业务APK、签名fixture后实际运行连续验证，才能新增本轮同PID/60秒健康/回退/持有边界证据。旧连续PID9745报告保持历史范围。

## 首轮失败诊断与输出收尾

根本轮实际run `fc9fc469d2d34adc94bbff27bd705385` 在首阶段投放前失败，PID26329、stages为空；旧helper只写AssertionError类型，driver看到failed立即终止ADB客户端，stdout/stderr均为空，不能据此归因生产更新。本次仅修测试诊断与输出收尾，未修改SDK、生产调度、真实健康门槛或归档。

helper现在用专属CheckFailure区分自身固定断言，安全异常链保留root/cause/suppressed、类型与自身消息；底层网络/反射/文件或其他AssertionError正文统一隐藏，最多24项并截断循环。失败回执附最后阶段/索引、已完成阶段数及安全Source和journal标量，只允许64位hash或实际apk双hash身份，不调用故障业务或新增主线程诊断。先原子写report，再公布failed；交接/播放状态收尾异常也作为失败，完整成功回执与complete信号在自身收尾后才写。runner收到固定失败消息，没有附带底层异常正文。runner在进入helper之前或计划构造失败仍属于既有runner启动范围，不会伪造阶段失败回执。

driver改为明确读取本次ADB子进程的两条异步输出流；三个failed入口统一留最多8秒等待原客户端及输出完成，然后保存同run/PID的failure-report.json再抛错。超过窗口则仍执行既有独立恢复，终止客户端后再尝试保存已完成输出；不能把8秒称为设备主线程或ADB命令的硬实时承诺。成功回执若残留failureType/failureChain/lastStage或缺少自身收尾标记会拒绝；本地完整成功报告仍须原无障碍、reverse与首页恢复并核验后才保存。

根新诊断run前缀`2dd92a26` 实际获得“连续验收播放会话未连接”，最后阶段playback-connect、stages为空，来源仍为内置基线，未投放候选。确认helper的UiAutomation.executeShellCommand按参数执行而非经sh；旧代码给服务列表加的单引号成为值的一部分，无法正确启用目标。现只在此层使用已验证的raw组件值，精确读回完整列表与enabled=1，保留含`$`的其他服务；PS层真正ADB shell的引号保持。

根API36实际首页dumpsys是`topResumedActivity=ActivityRecord{... app.luoxianlv.debug/app.luoxianlv.MainActivity t144}`，另有`ResumedActivity:`行；旧driver只接受带冒号的mResumedActivity/topResumedActivity，因此实际正常首页被误判还原失败。现支持该三种当前Resumed键与对应分隔符，只匹配完整debug包和Main组件，不接受其他App、名称前缀、内类、其他页面、Paused或History。此证据说明测试匹配缺口，不能据此称生产首页异常。

独立检查通过：实际helper/probe javac；36项实际脚本policy（含旧run/PID/外部正文/伪成功拒绝和真实Resumed向量）；10项真实helper安全异常链/身份过滤/命令检查；4项既有共同阶段预算。Java Runtime.exec(String)实际本机子进程验证含`$`服务值拆词不变，并复现旧单引号成为参数内容；此证据仅证明JVM参数路径，不冒充Android设置成功。实际本机非ADB子进程延迟stdout/stderr刷出及两文件保存通过，旧Factory遗漏bindResources的负例继续明确失败。本子任务没有模拟设备成功、改写健康时钟或重跑真实continuous；本轮真实连续结果仍待根重新安全Fresh归档后执行。
