# Renderer 与宿主调度收尾审查

2026-10-01 按当前 APP Dev 源限定审查 `OfficialRendererGate/RendererValidationBudget`、`HostUpdates/UpdateSchedule`，以及页面 wrapper、`PageSwapHost/GroupActivation` 和 Bootstrap 的实际调用。未操作 Gradle、设备、API、渠道、OSS、签名密钥或 push。根代理已报告 F 的 HOME 75 秒调度与实际训练场加载通过；这份代码审查不把该结果扩大为后续错误资源候选 G 或本次宿主收尾修复的设备证据。

## 确认并修复的阻断

旧 `HostUpdates.stopScheduling` 对整只主线程 Handler 调用 `removeCallbacksAndMessages(null)`。确定性顺序如下：授权 worker 已完成 `state.loader.prepare` 并入队 `activate(candidate,ticket,prepared)`；此前已排队的旧业务故障先在主线程执行，`Bootstrap.stopBusiness` 调用 stopScheduling；尚未执行的候选转交被删除。此时没有 owner 调用 `Prepared.closeCallbacks` 或撤销许可。`NativeLoader.prepareVerified` 已登记快照租约，`ContentLeases` 是无 GC 自动释放的静态计数，日志也停在 PREPARING。

修复在 `app-host/src/main/java/app/luoxianlv/host/HostUpdates.java:277` 只移除调度 pulse/coldPulse，保留授权转交和后台结果。停用标志先设置，留下来的 `activate` 在 561 行的 cancelled 分支关闭本代资源、回到单 worker 执行 `controller.abort(ticket,false)`，再完成调度取消；不据此隔离候选，也不恢复网络轮询。

仅保留回调还不足以排序稳定业务故障的恢复：Bootstrap 原有独立 native-recovery executor 可能在主线程尚未排入候选 abort 时就读取 PREPARING。`HostUpdates.afterStopped`（294行）增加现有 worker→main→worker 的双向屏障。授权 worker 先产生转交；同一主线程 FIFO 先取消 Prepared，再把 abort 排到 worker；屏障最后把恢复任务排在 abort 之后。只把恢复直接放入 worker 仍可能抢在主线程取消回调前，不具有该保证。Bootstrap 接线由另一专属 owner 修改，本提交不包含 Bootstrap。

取消 Prepared 的 close 或 permit abort 失败会登记 `stoppedCleanupFailure`，最后一道 worker 屏障明确调用拒绝回调，不执行恢复成功任务。该屏障只允许主线程在 blocked 且 inactive 时调用，不新建线程或 shutdown 共用 worker。它不伪造日志 STABLE，也不宣称释放失败已被忽略。

## 暂停、回调和释放结论

- `OfficialCheckedPage` 使用实际 Activity Decor；Gate 判定 attached/windowVisibility/isShown 与 Activity 停止状态，候选容器 alpha=0 不影响预算，多窗口失焦但仍可显示时继续计时。后台或 detach 暂停累计 12 秒，返回前台续算，反复切换不能重置已用时间。服务仅 JS-only 时仍有 12 秒上界；所有 visual 等待窗口都隐藏时共享视觉检查暂停，不能把暂停当成 ready。
- JS ready 与真实 `postVisualStateCallback` 都保留。窗口迁移/隐藏改变 epoch，迟到回调失效；接受 visual 成功前再次结算预算。固定 image/SVG 项目的 availability 门禁不等于首帧像素检查或全部用户场景兼容性。
- 最后订阅关闭、成功、错误和退役均清除 job 回调/临时窗口观察/Activity listener，并移除、destroy WebView、释放本代 lease。错误通知在主线程进入页面或服务的既有恢复路径；只读未发现另一条确定性丢通知或卡死路径。没有据此证明所有 OEM WebView/provider 异常都已设备覆盖。
- `UpdateSchedule` 是唯一发现期限状态；主线程 tick 与单 worker 保持串行，前台或真实演奏来源才允许发现，准备状态/断网/生命周期退出协作取消。失败退避、Retry-After 与已下载候选的同轮后续许可不互相绕过，blocked 后保留下来的收尾不再产生新发现。
- 页面外层仍有独立 30 秒 `candidate_frame_timeout`（`PageSwapHost.java:773`）。隐藏整组候选超过该时间可被非内容故障取消，即使 Renderer 的可见预算暂停；该取消保留稳定选择且不隔离内容。不能把 Renderer 暂停表述为所有页面准备期限无限等待，或要求长后台后仍使用旧短期许可曝光。

## 本次验证

`tools/test-host-update-scheduler.ps1` 重新独立编译真实 UpdateSchedule/HostUpdates/Bootstrap：19 项调度及宿主测试、3 项实际 Bootstrap 状态查询，共 22 项通过。Android Handler/时钟/网络与调度输入为 JVM 替身；候选与 ticket 的测试初态以反射构造，没有实际 HTTP/签名授权/DEX 加载。收尾执行真实 `NativeLoader.Prepared.closeCallbacks`、ResourceScope/ModuleResources 的关闭以及真实 `ActivationController.abort/ActivationJournal.fail`，使用随机测试日志目录，不触及用户目录。

新增五项覆盖：两个真正线程以 latch 固定 stop 前/后候选转交入队；停止前屏障拒绝；本代 lease close 异常；实际 activation.bin 已登记字节失配使 abort 拒绝。前两种交替均确认资源/租约释放一次、abort 排在恢复前、日志回 STABLE、不隔离、无后续调度；两种故障均仅走拒绝、恢复成功次数为零。原代码负对照在忽略目录里重新编译，仅将 stop 改回清空 Handler，新交错测试明确失败：`only handoff survives stop expected:<1> but was:<0>`。生产源未为该反例来回改动。

原有实际 Renderer 预算类 26 项独立测试与 Kotlin 编译证据仍保留。本轮未重跑 Gradle或设备；根代理需在联合冻结 Bootstrap/HostUpdates 后统一构建，并分别记录收尾修复与 G 实际错误回退结果。复用现有队列和门禁体现 KISS/DRY，没有新增 SDK 或并行恢复框架。
