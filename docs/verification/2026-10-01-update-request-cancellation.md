# 自动更新请求让位播放准备：取消链本地验证

## 已修复的问题与范围

旧链路只在下载 `read` 之前查询取消布尔值。播放准备或HOME发生于阻塞读取中时，连接不会关闭；普通对象的15秒读取超时结束后，如果准备已经结束，也可能完全错过取消。另一个问题是宿主按回调执行时的当前优先状态判断 `InterruptedIOException`，迟到取消会误记网络失败和退避。

现在单次自动更新使用独立 `UpdateCancellation`：发现、安装登记、短下载授权、对象读取、激活许可和本轮健康发送共用归属。宿主既有生命周期/网络/准备状态变化取消当前句柄，只有本操作登记的连接受到影响。旧 `BooleanSupplier` 调用仍兼容；没有全局interrupt，没有修改稳定契约或Bootstrap。明确 `Cancelled` 异常保留原取消原因，准备已结束时的迟到回调也不会计网络失败。对象字节成功读取和连接尝试有独立只读计数，预算预留不作为实际流量。

关闭连接交给2个后台收尾线程，等待队列最多8项，单操作最多登记4个连接；队列满时不在主线程运行阻塞close。请求的有限读等待与finally仍负责收尾，不扩大为全应用网络框架。宿主更新worker仍是原单线程。

## 实际负例决定读取策略

第一版直接登记 `HttpURLConnection.disconnect`：慢响应头取消通过，但真实body已阻塞的取消在2.5秒内仍未完成。测试保留阈值，没有把这个版本算作成功。JDK21实际 `HttpURLConnection.setReadTimeout` 字节码只设置自身字段；已建立的socket不会因此更新，不能凭“收到头后修改timeout”假定解决。

最终在连接前设置500ms读取切片，保持原普通15秒等待容忍：

- 对象仍要求准确范围/Content-Length/identity编码，显式拒绝Transfer-Encoding，包括同时声明CL的chunked。body切片超时后关闭该流，按已落盘偏移重新请求精确Range，重新验范围与最终完整SHA；不继续解析超时对象流。预算allowance跨本轮连接保持，单纯等待不反复预留。连续15秒没有成功字节或本轮超过5分钟仍失败。
- 普通API固定长度JSON在总读取等待内可继续同一流，公开真实JDK慢body阳性证明未丢失已读取JSON；不确认固定长度的流在超时后重新发原请求，不拼接不同响应。响应头等待采用有界重发，body/idempotency不变；登记/激活/事件保留原幂等身份，check/grant重取当前结果。HTTP429/503和RetryAfter仍直接交给原退避，不被切片重试吞掉。
- cold `timeouts(750,750)`保留自己的等待预算，不放大到普通15秒。TCP连接、DNS、TLS仍由平台与原connect/read限制约束；本轮不声称这些阶段均满足2.5秒取消，真实HTTPS/OEM还需设备验证。

## 真实可执行检查

`pwsh -NoProfile -File tools/test-update-cancellation.ps1` 在JDK21/Android37本地编译输入执行实际当前核心与HostUpdates。53项通过：20项调度、3项真实Bootstrap优先输入、26项HTTP/在线兼容、4项实际Binding命令检查。26项HTTP包含9项新增取消/阳性/边界测试；没有Gradle、设备或真实API/OSS调用。

新增HTTP检查使用随机127.0.0.1端口的真实ServerSocket：

- 慢对象响应头、慢对象body、慢安装登记头/body，调用cancel不阻塞调用线程，后台操作2.5秒内返回明确取消，服务端实际观察连接关闭。
- 慢body已收到4096字节后取消，`.part`与实际字节计数保持4096；保守预留不退还。新操作从4096精确续传并完成hash，不重置旧计费预算。
- 对象两次真正500ms无进展后依次发0/4096/8192的精确请求，最终12288字节/hash完整；预留仍一次12288字节，实际字节计数12288。
- 普通固定长度API body等待1.2秒后仍完整注册成功；cold750ms等待拒绝而不扩为15秒。
- 取消后迟到登记只关闭一次且token不可复用；同时CL/chunked的对象明确拒绝。

本地允许的公开假凭据/协议向量只用于纯回环测试。原有HTTP范围、跨源凭据、授权、签名地址脱敏与完整在线客户端用例继续通过。JVM真实loopback不替代Android后台/弱网/OEM结果，也不证明应用TCP重传流量；计数是本客户端成功读取的对象字节和尝试的对象连接。

## 短准备与线程边界

旧 `Binding.command` 没有通知宿主，业务 `preparing` 为普通字段，`usage(false)`在当前已非播放时也不触发事件，因此小于1秒轮询的准备可能漏过。本次在真实命令执行之后调用既有 `playbackUsageChanged`，MusicAccessibilityService现有override再到Bootstrap/HostUpdates；当前代际/epoch检查保持，退役命令不能取消新代请求。

4项独立检查执行当前真实Binding方法：同步命令先写业务状态再通知；异步命令仅在主线程实际执行时通知；迟到epoch与禁用binding既不执行业务也不通知。它们使用平台loop及业务状态替身，未在Android执行真实演奏。生产 `PlaybackSession.command/query` 由Binding在同一主线程调用，准备字段在这个通道无需靠volatile传给worker；worker只读宿主复制后的volatile优先状态。

仍保留1秒轮询覆盖业务内部异步状态和直接调用路径。FloatingControls直接调用业务toggle/play，及业务自身回调中启动准备，不一定经过PlaybackPort.command；本轮没有将这些路径冒称为全部即时事件通知。当前运行音频/手势连续性和实际下载途中用户操作由根任务接着设备验收。

【MCP调用简报】本地限定源码、公开协议向量、javap与Java/PowerShell测试；53项通过，真实慢body负例促成修复；无生产凭据、Gradle、设备、真实API/OSS、发布或push。
