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
| `app/.../platform/RealtimeEvent*.kt` | 普通通知格式、类型化事件及业务订阅生命周期 |

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

## 查询和通知

服务端声明 `queries-v1` 后，个人资料、曲库同步与列表、搜索、互动状态、通知列表优先通过同一 WS 查询。热更另声明 `hot-queries-v1`，候选检查、字节差分说明和对象下载授权查询使用原来的安装身份和严格签名解析。查询通道不可用、繁忙或结果超过上限时回到 HTTP；已确认的权限拒绝、签名错误不会被当作成功。

查询与连接的 API 来源绑定，账号退出和换号仍沿用会话代次检查。查询任务独立于控制循环；单帧 16 KiB、分片 8 KiB、窗口两片，结果最大 5 MiB。断线、取消、乱序和长度变化不能提交部分结果。正文解析和等待均在后台，不能在页面线程等网络。

普通更新仍通过 HTTP 检查、验签和下载。WS 版本通知只把关于页按钮改为“发现新版本”，不会自动下载或打断演奏。编译核心版本随连接状态同步，减少重复查询。曲库、互动、通知和资料变化通过账号授权后的 `business.changed` 事件通知；数据仍通过查询获取。退出业务会释放监听。

下载、上传、登录、写操作、热更激活许可和健康回报保持 HTTP。实时查询进程不加载激活私钥和管理员令牌。关闭 `REALTIME_QUERIES` 或连接失败后，既有 HTTP 与宿主恢复通道继续可用。旧客户端接口、EO/ESA 下载和更新签名格式不变。

## APP 事件总线

每代业务共用 `RealtimeEvents.bus`，只处理连接状态、`catalog.changed` 和 `business.changed`。统一解析为不可变模型，页面不读取协议 JSON。普通通知通过 Java 桥 `observeEvents` 交付到独立的 `realtime-events` 线程；最多 32 个订阅，每个邮箱最多八个键，同类通知保留最新值。页面慢不会阻塞连接线程，也不会因通知积压断开 WS。

`business.changed` 正文固定为 `{"kind":"library","revision":"42"}`，`revision` 是正整数的十进制字符串；账号身份不出现在正文中。

| 入口 | 用途 |
| --- | --- |
| `state: StateFlow<RealtimeState>` | 当前连接、账号作用域和版本快照；晚订阅立即读取 |
| `invalidations: StateFlow<RealtimeInvalidations>` | 按资源记录刷新序号；比较所需字段后后台查询最新数据 |
| `events: Flow<RealtimeEvent>` | 基于有界 SharedFlow 的瞬时提醒；消费前过滤旧账号与旧业务代次 |

需要可靠刷新的功能读取 `invalidations`，不靠瞬时事件计数。换号清空旧资源标记；重连和恢复业务订阅产生刷新序号。通知绑定连接及账号代次，重复事件和旧连接回调被丢弃。热更交接暂停旧总线，回退恢复订阅，最终退役关闭租约。

查询响应保留请求 ID、超时和取消；合奏指令、校时及预约执行继续使用原控制通道。总线回调只更新内存，不执行网络、数据库或 UI 操作。页面按自身生命周期收集 Flow，读取与解析放后台。新增 `observeEvents` 随构建重新生成契约 SDK 指纹与宿主恢复模块，不能作为旧宿主的通用业务热更发布。
