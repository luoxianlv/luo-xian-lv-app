# 前台服务短暂冷启动：仪器观测修复

根代理提供的真实 foreground consumer 场景：PID20726 的策略创建后固定诊断在04:48:59.011记录 startId1，26ms后记录已停止；Source J、STABLE、revision35保持，无崩溃。旧仪器90秒后以 SERVICE_NOT_READY失败，根未把它记作FGS设备通过。本子任务没有读取设备或重跑该场景，以下结论基于根提供的现场和当前源码。

`NativeServiceColdInstrumentation.observe` 原先只反射当前 `PlaybackForegroundService.instance/lastStartId/policy`，并且在 Source ready 后才采样。`Preparation.evaluate` 在同一主线程回调中创建策略、发出进入诊断、评估策略并立即 stop；onDestroy 清空 instance/policy。runOnMainSync不能插入同一回调，10ms轮询不保证捕获26ms瞬态，延长总等待不会使已销毁的实例重现。

生产策略是 `floatingEnabled && PlaybackConnection.isEnabled(context)`，并不是“只有开始实际演奏才保持前台”。consumer=foreground只发起系统FGS启动，没有开启无障碍或播放，策略不要求运行时正常自停。服务先实际调用startForeground，再登记startId；固定“已进入前台”发生在策略构造返回后、shouldRun之前，“已停止”发生在stopSelfResult成功及stopForeground执行后。异常catch也会停止，所以仅enter或仅stop均不能作为正常完成证据。此次修复不改变Service、BusinessPolicy、SDK或其他runner，不人为延长前台或注入started。

新增纯 `NativeForegroundLogEvents` 与只读日志补充：实际启动前读取本PID固定tag“播放服务”的cursor，并在主线程调用startForegroundService之前记录epoch毫秒。读取命令固定为 `logcat -d -v epoch --pid <self> -s 播放服务:I`，最多256KiB；只解析INFO进入/停止和任意级别的固定业务不可用事件。原日志和异常正文不写回执、不输出；cursor仅保留规范化时间/id/事件/停止请求字段，本次最多64个固定事件。

正常idle自停须同startId的enter(stopRequested=false)→stop，严格晚于启动毫秒且不在旧cursor中。旧窗口、同启动毫秒、不同PID/tag/priority、不同或已被新enter取代的id、反序、缺stop、主动停止请求均不能被配对为正常闲置。窗口内任一固定“播放业务不可用，停止本次前台服务”永久否决该证据；真实Source等待和随后的空闲观察继续检查，不能在首次配对后忽略迟到异常。

旧live快照路径保留。事件补充没有把当前instance字段改成true；成功回执分别记录liveStartSampled/livePolicySampled与normalIdleStoppedViaEvents，事件方式只是证明该次短暂兑现系统前台承诺、策略创建及正常闲置自停。Source、factory加载器、journal/stable/pending/runtime、noActivity、无实际播放、健康时间零增量及版本/信任下限检查均保留。明确sustainedForegroundVerified=false、actualPlaybackVerified=false、rawLogsReported=false，不扩大为持续前台或播放验收。

`tools/test-native-foreground-log.ps1` 已独立javac编译实际仪器/parser通过，9项JVM解析测试全部通过：固定epoch格式26ms配对，旧cursor/旧窗口/同毫秒，错PID/tag/priority，错/替代id，反序/缺事件，主动停止，启动与配对后业务异常，分段读取及重复记录，非法id/输入上限/未登记游标。测试没有模拟实际服务started、政策返回、健康或设备成功。

本子任务未运行Gradle、设备、API、网络、密钥、生产或push。根需仅重建testAPK、实际重跑独立foreground冷启动后，才能把事件方式和现场回执计入FGS设备证据；当前只冻结了最小观测修复。
