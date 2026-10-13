# 实时连接与合奏

实时功能默认关闭。开发构建使用 `-PrealtimeEnabled=true`，本机联调另指定 `-PupdateBaseUrl=http://127.0.0.1:18474`。生产必须使用 HTTPS/WSS，并在服务端独立开放。演练场不参与合奏。

## 代码入口

| 位置 | 负责的内容 |
| --- | --- |
| `modules/app-host/.../host/realtime/` | 安装凭据、HTTP 授权与生命周期 |
| `modules/app-runtime/.../realtime/` | 唯一 WS、重连、校时和预约执行 |
| `modules/hot-contract/.../SharedRealtime.java` | 业务监听、命令与取消租约 |
| `app/.../ensemble/EnsembleSession.kt` | 房间状态、选谱、准备、确认与本机停止 |
| `app/.../playback/PlaybackSession.kt` | 输入预热、绝对时间线和触点释放 |
| `app/.../platform/` | Ktor CIO 请求、登录刷新、下载和业务退役 |

房间中的每个成员先下载并验证同一谱包，再准备本机输入。主控发起未来的开始时间；参与者确认后服务端提交计划。客户端只执行当前连接、演奏代次及准备令牌对应的计划。取消先释放本机触点，网络确认随后完成。中途断线需要重新准备，不重放旧开始指令。

新导入 MIDI 保留原始文件，可按共用移调和时间原点编译多轨。没有原始 MIDI 时全员使用同一份时间线，不猜测声部。谱包走 HTTP，WS 只传控制消息。

## 网络与热更边界

Ktor 使用 CIO。按域名委托 Android `X509TrustManagerExtensions` 验证系统证书策略，CIO 保留域名校验；不放宽 `network_security_config`。域名客户端和并发请求均限制为八个，业务退役后取消并关闭。跨域重定向不带账号头，POST 不自动重放。接口依据见 [Android 官方文档](https://developer.android.com/reference/android/net/http/X509TrustManagerExtensions)。

宿主、热更下载与恢复继续使用纯 Java。响应头在原请求内等待，不因读取超时重新发送 POST。取消在后台关闭连接。

`hot.changed` 只触发已有检查，不能激活代码。热更订阅同步后使用十分钟兜底，订阅失效恢复约一分钟轮询，并遵守退避与 Retry-After。业务热更更换监听租约，保留宿主 WS；共享运行时更换仍需下次进程启动。合奏准备或演奏期间暂停普通热更处理。

新增契约需要新宿主及匹配的 SDK 指纹、恢复基线。不能将这个业务包发给旧宿主。

房间事件是完整快照；先于加入回执的事件只暂存，退房后的旧广播不会重新入房。异步请求绑定房间代次，迟到结果不能覆盖新房间。业务交接暂停旧监听，回退再恢复，旧业务关闭不清理新业务的输入状态。

服务维护会通知客户端冷却，期间不重复申请票据；断线后仍须重新准备演奏。

## 验证

`RealtimeInstrumentation` 验证真实运行时 WS、授权刷新、重连及监听释放；`BusinessHttpInstrumentation` 验证 Ktor，显式 `-e tls true` 只读公开 HTTPS 健康接口。`HostHttpInstrumentation` 验证系统 HTTP 慢响应头和取消。

显式本机协调器夹具配合 `-e coordinator true` 验证 Android 与 Rust 的选谱及开始流程。夹具使用测试账号、受控时钟和模拟输入，不证明游戏内同步精度。热更沿用 `tools/test-native-automatic.ps1` 和 `tools/test-native-host.ps1` 的签名、健康及故障回退验证。
