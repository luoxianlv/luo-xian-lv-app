# 真实离线及自然恢复调度 helper

新增入口由根代理接入 runner，本子任务不修改 runner：

```java
JSONObject result = NativeSchedulerChecks.offlineRecovery(this, main, networkRecoveryRunId);
```

`networkRecoveryRunId` 必须是全新32位小写hex，建议由外部 driver 生成 GUID 的 N 格式。既有 `run`、`offline` 入口行为保留；新模式只允许 `app.luoxianlv.debug`、Debug flag、instrumentation 工作线程、automatic/test/`http://127.0.0.1:18472`。需要原首页、正常在线空闲周期、没有候选/观察/失败退避、播放或演练场准备。Source 改变或安全门禁停止即失败。

## 固定文件协议

每轮全新目录 `files/native-host-network-checks/<runId>/`；旧目录从不删除、覆盖或复用。helper 写自己的 `control.json`、最终 `report.json` 和原子替换临时文件；root 外部 ADB 只写同目录 `ack.json`。helper 不改变系统设置、时钟、在线字段、许可、健康，不调用显式 check/activate。

control 的字段：`schema=1`、`runId`、`phase`、`requestedElapsedMs`、`deadlineElapsedMs`、`sourceIdentity`、`systemNetworkChangedByHelper=false`。阶段为 `disconnect`、`reconnect`、`complete`、`failed`。最后两项没有 ACK，也不要求系统操作；只有 instrument 实际通过和同 run 的成功 report 才算验收。elapsed 值来自真实设备单调时钟，不是 host 时钟。

root 先保存原模拟器网络设置，在收到 `disconnect` 后于外部断网，再以 UTF-8 无 BOM 原子写入：

```json
{"runId":"<same 32 lowercase hex>","phase":"disconnect"}
```

收到 `reconnect` 后外部恢复可用网络，并写同字段、`phase="reconnect"` 的 ACK。helper 只接受同 run/同 phase；旧 ACK 不满足下一阶段，缺失 ACK 不推断完成。ACK 最多4096字节，拒绝未知字段、重复字段、坏 JSON、BOM、非普通文件或符号链接。失败/超时也由 root 的 driver finally 恢复原网络设置；helper 永远不自行切网。

## 真实观察

开始仅取得已完成检查后的普通活动快照，不用它声称另一次60秒间隔测量。实际宿主 networkCallback 必须已注册；新建一个本测试自己的只读 ConnectivityManager callback，同主线程读取真实 activeNetwork、INTERNET/VALIDATED 能力和系统 callback 次数/时间，不替换宿主 callback。

disconnect ACK 与真实系统无 INTERNET、宿主 online=false、在途已退出必须同时满足。随后真实离线至少75秒，并越过实际 due 至少3秒；全程 lastStart 不变、没有 inFlight/busy/候选/观察事务，原首页保持活动。剩余时间不足完整观察时失败，不缩短75秒或提前请求联网。

reconnect 阶段从发出请求起连续采样：先观察到的 lastStart 变化确定唯一一次恢复检查；任何第二个变化即失败，不能在等待 ACK 时漏算检查。必须同时收到恢复 ACK、实际 INTERNET、新的真实系统 capability callback（对应当前 activeNetwork）、宿主 online=true，并自然完成成功检查。随后完整观察5秒，要求 lastStart 不再变化、无重复或并行任务，最终恢复原首页并注销本测试探针。

这是“真实系统 callback 与自然恢复检查共同观察”的证据；HostUpdates 仍有一秒 connected 状态探测，回执明确 `exclusiveCallbackCauseProven=false`、`hostOneSecondProbeExists=true`，不声称排除了轮询或唯一由 callback 分支触发。没有观察实际对象 body、断点、HTTP Range、下载取消或失败回补，`downloadCancellationVerified=false`。

主动观察最多285秒，为最终窗口恢复预留15秒；所有阶段等待均受这一共同期限限制。总计超过300秒不能返回成功。设备主线程/系统若卡死，外部 driver 仍须以300秒硬超时结束仪器并恢复网络，不能把普通 `runOnMainSync` 卡死包装成成功的有界设备检查。

成功回执为 `files/native-host-network-checks/<runId>/report.json`，含初始/离线/超due/恢复/5秒合并/最终宿主快照、对应系统网络和 callback 数据、真实离线等待/越过due值、同Source身份、原首页/探针收尾，以及不注入状态/显式检查/激活/健康的声明。失败不生成本 run 的成功 report；旧 run 目录存在则拒绝开始，不读取旧回执冒充本次通过。

## 独立验证与尚未验收

`tools/test-native-network-handshake.ps1` 使用既有 Android37、host/core/contract 编译输出独立 javac 编译两份实际 helper，通过；没有运行 Gradle。9项实际 Session/Deadline JVM检查中8项通过、1项 Windows 符号链接权限 assumption skip、0失败，覆盖精确 run/phase、无ACK不假设、全新目录与未知文件保留、严格JSON/大小、报表硬链接别名安全替换、完整离线期限、各阶段裁剪和5分钟成功上限。JVM没有模拟 Android callback 或调度恢复成功。

本子任务没有运行设备、API、网络设置、OSS、签名或 push。根需统一构建 AndroidTest 并用专属外部 driver 实际运行；这份准备文档及独立编译结果不替代离线75秒、恢复 callback、自然单次检查和5秒窗口的设备回执。
