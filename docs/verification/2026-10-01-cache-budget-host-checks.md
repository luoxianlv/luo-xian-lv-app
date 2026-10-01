# 普通宿主缓存恢复与整候选计费预算：测试交付

本次复用 `NativeDownloadChecks`、`downloadRunId` runner入口、`tools/test-native-download.ps1` 与既有本机download-gateway，绑定普通HostUpdates的自然检查、实际下载、激活及健康窗口。不调用explicit check/activate，不注入时钟、schedule、预算或metered布尔。生产、SDK与其他runner未改；子任务未执行Gradle、设备、API、云、签名或push。

已有JVM OnlineClientTest是真HTTP准备链：验证同hash坏对象仅补取该对象、已有snapshot修复与内部验证。既有Android repairOnly只覆盖ContentStore复制修复，NativeTransferChecks仅覆盖200KB续传；单独DownloadBudget/ObjectDownloader JVM覆盖20MiB合计、持久预留、切计费重算。这些不能替代普通APP自然候选和系统计费状态的新设备证据。

## 三个入口

| mode | 真实输入与通过条件 | 成功后的选择 |
| --- | --- | --- |
| cache-repair | 两个新资源对象8192字节。验签完整fixture后只准备未运行目标metadata；将一个对象原子移至本run备份模拟缺失，另一个备份后等长翻一字节。慢响应已打开时确认坏字节仍在，防止collector删除使腐坏测试退化成普通缺失；自然补取准确8192字节、同清单hash、两对象重新验证、实际健康窗口≥60000ms。结束重验三份原始/坏字节备份及原清单hash。 | 新资源目标自然STABLE |
| budget-limit | 两个新资源ZIP各低于20MiB，合计20971521字节；系统CM实际计费。普通检查自然结束且未打开对象连接、未读取正文、未预留流量、未写对象/partial，原Source/选择/隔离保持，版本下限只允许上升。 | driver以本发布CAS回滚原Source；不重复做60秒健康 |
| budget-retry | 两个新资源ZIP合计20971520字节；实际计费网络首次准入。慢对象16384字节前缀后沿既有实际Practice路径取消；持久预留≥1MiB、原前缀保留。关闭自己Practice恢复首页后，下一普通检查重新按整个剩余候选判断，必须Deferred且零新对象连接。driver切实际非计费网络，helper观察CM边沿和新操作，在捕获后释放原响应；两次实际读取和准确等于20971520、原账本不退还，目标自然健康≥60000ms。 | 新资源目标自然STABLE |

重试取消的实际触发仍可能包含Activity生命周期变化，沿用旧报告 `purePriorityCauseProven=false`；不扩称只由播放准备优先级导致。计数取真实UpdateCancellation正文读取，预算预留与代理body返回不冒充客户端读取/TCP送达。超限case不生成虚假的空代理指标；它以设备实际对象连接零计数为证据，不声称全部服务端请求计数。

## 公开fixture与保留边界

`tools/prepare-native-cache-budget-fixture.ps1`已在CLI忽略目录 `.local/native-cache-budget-latest-20261001` 准备三组公开原始ZIP/recipe，复用本轮C的runtime c62c2034…及business b15106ac…。theme包含严格palette.json；shaders复制当前正式两个AGSL。高熵 `budget-padding.bin` 用STORED方式填至准确压缩对象字节数，三组hash不同。padding仅用于真实对象传输和预算，不是20MiB场景渲染压力证据。工具不读取私钥/password/token，不签名/上传/发布，目录存在即保留并拒绝覆盖。

根已用本机测试签名夹完成验签与登记，尚未据此记为设备通过：

| mode | signed snapshot | 两新对象合计 |
| --- | --- | --- |
| cache-repair | 278d2dd5f9b7c8a56fc114b6eb1ec5a97f85a90bd3f31a3b6b748da52da1f639 | 8192 |
| budget-limit | 42089ced161ad2f830636a3ffe2775956a731834c6d98937d17d04043dc1d8aa | 20971521 |
| budget-retry | c509d765ab8417f28d6220eb7720dc0eb95dc5e53e87349e5217f14673ac8ac7 | 20971520 |

三个 `plan-<mode>.json` 已绑定上述target；sourceIdentity留空，由本run实际Source与API有效发布核对，fixture仍严格要求runtime/business等于该实际Source。允许上一资源case成功后顺序继续，不要求删除缓存重置C。driver将本候选完整签名包送到自己的run目录，helper按当前宿主根/信任/挂载规则验签，且两个新对象必须完全对应新增资源、慢对象按签名清单首先下载。

新mode遇到任一已有专用对象或partial即拒绝，保留前次故障字节，不重新seed或删整cache。失败后若要重做，应另生成新hash fixture，保留原run、备份、签名清单和现有隔离。测试不操作用户项目、当前/恢复对象或revoked pending记录。目标metadata保留依赖普通collector的近期两份/512MiB策略；压力或并发使坏对象被删时检查真实失败，不以保护坏snapshot lease或清历史来绕过。

## 根运行方式

根先统一 `:app-host:assembleDebugAndroidTest`，安装匹配当前普通宿主的testAPK，使用原已核验的lxhot/download-gateway与本机测试AuthDirectory：

```powershell
pwsh -NoProfile -File tools/test-native-download.ps1 `
  -Fixture '<CLI>/.local/native-cache-budget-latest-20261001' `
  -Candidate cache-repair.lxhp -Mode cache-repair `
  -AuthDirectory '<已授权本机测试AuthDirectory>' `
  -Lxhot '<已核验lxhot.exe>' -Gateway '<已核验download-gateway.exe>'
```

后两次分别用budget-limit.lxhp/budget-limit、budget-retry.lxhp/budget-retry。各run生成新GUID，设备回执保存在 `files/native-download-checks/<runId>/report.json`，最终driver报告为Fixture/download-run-<runId>/report.json，失败安全链与双输出保留；共同300秒、285秒主动观察，ADB和设备线程不是硬实时。

driver保持只允许emulator-5554、Debug/test/API127.0.0.1:18472及CLI/.local路径。已有device tcp:18472 reverse或18474监听会拒绝接管；根需明确移除自己当前直连后运行。原发布归属/CAS恢复、代理停止、反向端口撤销和首页恢复仍沿原实现核验，不覆盖别人发布。budget-retry取消后保留目标发布与慢代理，仅ACK取消；unmeter阶段设置false且ACK unmetered，resumed_captured之后才释放，避免把一次空检查伪称重试准入。

根已只读确认AVD的AndroidWifi两个策略行均none、当前WIFI NOT_METERED；实际help支持 `set metered-network ID [undefined|true|false]`。预算driver只接管精确AndroidWifi、一致原值；真实mutation须退出0并读回true/false，finally独立恢复none为undefined并确认none。helper另观察实际ConnectivityManager布尔，不拿策略命令0当系统计费证据。策略行缺失/矛盾或系统未产生所需实际布尔会失败；不改系统网络在线状态，不用模拟谓词代替普通HostUpdates。

## 当前验证范围

`tools/test-native-cache-budget-checks.ps1`通过实际helper独立javac、14项JUnit（0 skip）、84项真实driver纯policy/mock/本地输出flush检查。新检查覆盖准确20MiB/20MiB+1整候选、两个对象及完整包约束、持久预留重新准入、合法unmetered ACK/旧run拒绝、none重复行/undefined恢复及矛盾策略拒绝。既有取消/delta报告和CLI发布归属负例仍通过。mock不冒充Android下载、计费边沿、首帧或设备成功。

根实际cache-repair已通过：run `1f2bdde9a59a47e69976a42b2be8d322`、PID1939，普通宿主读取8192字节、2次对象连接，补缺与等长损坏对象都重新验证；有效健康60911ms，故障备份/清单原始hash保留，首页/窗口、reverse和代理完整收尾。公开回执为CLI `docs/verification/native-cache-repair-device.json`。这是普通HostUpdates资源候选证据，不是独立核心模拟或删除cache后重装。

随后budget-limit在instrument启动前因netpolicy setter exit255被driver拒绝，旧run前缀`f098a4d8`仍作为失败保留，未写对象或partial。根真实复现set true exit255但两AndroidWifi行均true，restore undefined exit255且均none。仅这个setter改为直接Invoke-DownloadCommand接受0/255，其后getter仍strict0且必须精确读回目标值；helper实际CM计费条件不变。其他ADB不放宽。解析也拒绝同SSID未知/非法大小写值，不能凭另一条合法行推断一致。

setter修复实际PowerShell解析/纯policy及提取实际setter函数mock检查累计97项通过：255正确true/undefined读回可继续，255错误/矛盾/未知/非目标行、0错误读回和1/254均拒绝。没有操作设备或系统设置，不把mock当预算通过。当前cache-repair设备已PASS；budget-limit与budget-retry仍待根重跑，不能以setter已产生副作用或raw ZIP大小补写为验收成功。生产可复现缺陷本轮尚未发现。
