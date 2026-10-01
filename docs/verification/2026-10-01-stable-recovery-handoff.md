# 稳定业务故障与未曝光候选的恢复顺序

## 问题与最小修复

更新线程的 `client.authorize` 已通过 `ActivationController.begin` 落盘 `PREPARING`，随后加载完成并向主线程转交 `activate`。在主线程消费该消息前，`Bootstrap.activation` 尚为空。当前稳定业务此时抛错，会进入 `Bootstrap.componentFailed` 的稳定故障分支。

原实现另建 `native-recovery` executor，与候选的取消事务竞速。稳定快照身份虽然仍相同，`stableContentFailed` 的 `requireStable` 却会因阶段仍为 `PREPARING` 拒绝；恢复按钮因而一直不可用。保留被停用清理误删的 `activate` 消息，只解决候选资源和许可丢失，不能单独解决这个落盘顺序。

现在 `Bootstrap` 使用 `HostUpdates.afterStopped(persistRecovery, rejectRecovery)` 的双向屏障：

1. 故障首先停用页面、播放及自动调度，拒绝新激活。
2. 同一更新 worker 排在授权/加载任务之后，向主线程投递屏障。
3. 主线程 FIFO 先消费已转交的候选：实际 `activate` 取消路径关闭 `Prepared`，再向同一 worker 排入 `controller.abort`。
4. 主线程屏障把稳定故障恢复排回 worker；FIFO 保证许可取消与 `journal.fail` 先落盘，再执行 `stableContentFailed`。
5. 候选资源关闭或取消落盘失败时，屏障调用拒绝处理，记录中文错误且不开放恢复入口。

没有更新控制器时仍使用独立后台恢复 executor，并在完成后关闭。存在控制器时复用其 worker，不关闭共享 executor；主线程不等锁、不睡眠，也不使用延迟重试掩盖竞态。稳定故障和取消候选分别归因，取消不会将尚未曝光候选隔离。

## 独立可执行验证

运行 `pwsh -NoProfile -File tools/test-host-stable-recovery-handoff.ps1`。脚本先复用既有调度测试的离线平台替身和编译入口，再编译当前真实 `Bootstrap.java`、`HostUpdates.java`、`ActivationController.java` 和 `ActivationJournal.java`。当前环境 JDK21、Android37本地编译输入；不运行 Gradle、Android设备、API或HTTP。

新增4项全部通过；复用的19项调度与3项真实Bootstrap优先级检查也通过：

- 已授权候选消息在故障之前入队。
- 真实更新工作线程已落盘许可，在故障之后才完成主线程转交。
- 真实 `Prepared.closeCallbacks` 的内容租约关闭抛错，许可可以取消，但不能执行稳定故障恢复或开放入口。
- `activation.bin` 实际字节被本测试改坏，真实 `controller.abort` 因旧状态不匹配拒绝，不能开放入口。

前两项使用公开签名许可向量运行真实 `controller.begin`，并确定性交替主线程与真实工作线程。逐步断言：授权后 `PREPARING`；主线程候选资源退役且租约关闭一次；worker 首先取消候选并恢复原稳定快照；随后才隔离真正故障的稳定快照，保存APK恢复选择；重新打开 `ActivationJournal` 确认持久结果，最后才允许按钮。没有镜像实现生产的屏障或稳定恢复代码。

测试替代 Android Handler/Looper 和已加载完成的 `Prepared` 输入；`Prepared.closeCallbacks`、资源范围退役、模块资源关闭、实际内容租约、真实许可/激活日志和Bootstrap故障分支均执行。未执行真实Dex加载、首帧、业务页或Android生命周期，因此不称为设备恢复通过。测试使用 `Unsafe` 仅装配内部已有对象状态，JDK对此产生的3条内部API警告保留。

本轮只修改Bootstrap的稳定故障执行策略、独立测试和本文；HostUpdates屏障/调度停用由相邻owner交付。根任务随后统一构建、设备回归。

【MCP调用简报】本地限定源码与公开测试向量读取、Java/PowerShell离线编译和Git；4项新增与22项复用检查通过；无生产凭据、Gradle、设备、API、网络发布或push。
