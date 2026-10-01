# 真实用户曲谱保留夹具

`app-host/src/androidTest/java/app/luoxianlv/host/NativeUserScoreFixture.java` 是独立纯 Java Debug 仪器 helper。调用必须来自 `app.luoxianlv.debug`、`FLAG_DEBUGGABLE`、`test`、`http://127.0.0.1:18472` 和 instrumentation 工作线程。业务 Context 与 ClassLoader 均取自本次当前 `Bootstrap.source()`；每次 `snapshot/verify` 重新取得当前来源并构造生产 Repository，过程中来源变化即失败。

## 生产路径与存储边界

源码路径是 `LibraryViewModel.saveAs` → `SongRepository.add` → `write` → `Kv.of(context, "song_library")` → `FastKV.adapt(PlatformApplication.of(context), name)`。本地简谱真实存在内部 `files/fastkv/song_library` 对应的 FastKV 存储中。外部 `AppStorage` 目前没有曲谱文件仓库；helper 返回 `externalScoreFile=false`、`storage=internal-fastkv`，没有另外创建一个 `.txt` 冒充曲库文件。

MIDI 文件导入由 `ImportViewModel.import` → `SongRepository.addMidi` 走远端编译，因本子任务禁止 API，夹具采用生产简谱另存入口。调用完整的实际 JVM 方法，不调用 Kotlin `$default`，不引入宿主 Kotlin 依赖：

```text
SongRepository(Context)
  (Landroid/content/Context;)V
SongRepository.add(String, String, int, String, String, String)
  (Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Lapp/luoxianlv/data/Song;
SongRepository.songs() / Song.getEvents()
  ()Ljava/util/List;
ScoreParser.parse(String)
  (Ljava/lang/String;)Ljava/util/List;
ScoreParser.tempo(String, int)
  (Ljava/lang/String;I)I
Kv.of(Context, String)
  (Landroid/content/Context;Ljava/lang/String;)Landroid/content/SharedPreferences;
```

为了保证只读函数不触发 FastKV 偏好迁移或 Repository 初始化迁移，当前业务首页必须已经打开真实 `song_library` 适配器，且其 `speed_migrated_v2=true`。helper 只读取已存在的缓存映射并调用实际 `Kv.of`；缺少条件即报错，不写迁移标志。构造 Repository 前后再次比较完整曲库偏好逻辑值。

## 调用接口

同包 `NativeAppInstrumentation` 接线由主任务负责，本文件没有修改该入口：

```java
JSONObject prepared = NativeUserScoreFixture.prepare(runner, home);
String songId = prepared.getString("songId");
JSONObject before = NativeUserScoreFixture.snapshot(runner, home, songId);
// 主任务完成真实热更或故障恢复，并等待当前首页 / Source 就绪。
JSONObject after = NativeUserScoreFixture.verify(runner, home, before);
```

`prepare` 生成唯一标题 `原生热更验收谱 · <UUID>`，通过生产 `add` 新增并保留曲目。谱面是 `tempo=96 unit=1` 和 `1:1 2:0.5 3:0.5 0:0.5 [4:1] (#5:0.5) 6:1 i:1`：8 个事件、7 个可演奏音符、3750 ms，包括自然音、休止、升降调和半音。实际 `Song.events` 必须与当前生产 `ScoreParser.parse` 完全一致，并逐个核对音高、时长、模式、休止与半音字段。

生产保存暂时改变选曲，helper 在 `finally` 中只恢复原 `selected` 键的存在状态和值。速度、悬浮窗、同步库、隐藏清单与其他 `song_library` 偏好均按类型和值计算摘要，恢复后须与操作前完全相同。原有所有可见用户曲目按 id 排序比较全部 11 个业务字段：`id/title/score/bpm/source/builtIn/contentVersion/synced/remoteId/coreVersion/needsFix`。原始本地记录含未知字段、重复 id、坏 JSON、负内容版本、纯空白但非空的可选元数据，或无法由生产列表完整表示时，helper 在调用 `add` 前拒绝新增，避免生产重写歌单丢失旧信息；空白判断与 Kotlin `isNotBlank` 的字符语义一致。

成功的 `prepare` 返回 JSON，并将回执写到自身 `files/native-user-score-fixture-report.json`。仅删除该过期回执与自己创建的 cache 临时回执，新增曲目及全部旧曲目保留；失败时也不删除已保存歌曲。回执写入失败不算通过。

主任务应在首页与业务来源稳定、没有并发曲库编辑时串行调用。恢复选曲前会再次确认原 Source，且只接受原选择或本次新增 id；出现其他选曲写入时拒绝覆盖并报错。该保护不是 CAS 或并发事务，`prepare` 仍要求没有并发曲库编辑的准备窗口。

`snapshot/verify` 不写回执和偏好。它们使用真实 `songs` 字段、生产列表与解析，返回目标曲目和事件摘要、全部可见用户曲目摘要、全部本地持久记录摘要、原选曲键状态及曲库设置摘要。`verify` 比较这些逻辑值和实际存储路径，允许 Source 身份因真实更新发生变化。

## 磁盘证据与验收范围

helper 从实际 FastKV 实例只读 `path/name`，要求路径是目标应用的正常内部 `files/fastkv`、名称是 `song_library`。只读扫描 `song_library.kva/.kvb/.kvc` 与该库的大值目录，记录每个实际文件的相对路径、字节数与 SHA-256，并要求至少一个实际文件含当前完整 `songs` JSON 的 UTF-8 字节。扫描拒绝符号链接和路径越界；不会调用 FastKV `force/close` 或自行写入库文件。

FastKV 保存自身新记录会改变整个库文件，所以跨更新比较逻辑记录与当前持久字节证据，不要求库文件整体 SHA-256 不变。`storeFiles` 的摘要是每次实际读取结果，不作为不可变文件声明。记录 `coldProcessReloadVerified=false` 和 `audioPlaybackVerified=false`，只证明正常本地保存、生产列表读取、可演奏事件解析以及当前记录落盘；跨冷进程恢复、实际播放与热更故障回退仍需主任务分别运行。

helper 没有调用激活入口、写更新许可、更新日志、健康结果或 SDK 来源，生产模块与 SDK 未修改。其他独立偏好存储不由本 helper 打开、读取或修改；“设置保持”回执严格指 `song_library` 的完整设置集合。

## 本轮本地验证

2026-10-01 使用已有 Android 37 `android.jar`、已有宿主与 hot-core/hot-contract 产物，以独立 `javac --release 17 -encoding UTF-8` 编译新增 helper 通过。首次检查发现 Android `JSONObject` 没有 `keySet()`；改用实际 `keys()` 接口后通过。

使用已有 Debug Kotlin 产物执行 `javap -s` 确认上述所有反射描述符，另以独立 Java 探针加载实际生产 `ScoreParser` 和 `Song`，读取夹具同一 `SCORE` 常量并实际解析通过：`events=8 playableNotes=7 durationMs=3750 tempo=96`。忽略目录 `.local/user-score-fixture-checks/` 保存本地探针和独立编译类。

同行只读复审后完善了新增前可选元数据检查与恢复前选曲冲突保护，未发现其他阻断。独立安全探针与实际 Kotlin `isBlank` 对比 8 种字符串（含 NBSP、figure space 和 narrow NBSP）通过；真实本地 UTF-8 字节扫描跨 32768 字节读取边界通过，缺少目标 JSON 的文件被正确拒绝。该探针使用既有 JVM org.json 库和自己的临时文件，仅检验 helper 的比较逻辑，不冒充设备 FastKV 验收。

本子任务没有运行 Gradle、操作设备、调用 API、渠道、OSS、读取密钥或 push。此时尚无设备上的真实保存/跨 Source 回退成功证据；主任务必须构建 AndroidTest 并实际接线运行，才能把 helper 返回的成功 JSON 计入目标验收。

【MCP调用简报】
服务: 无外部 MCP；Codex 本地工具
触发: 生产曲库路径核对、测试夹具落地与独立编译检查
参数: 限定新增 helper/本专属文档；只读已有 Kotlin/Android 产物；javac Java 17 UTF-8
结果: 真实路径与描述符确认；helper 独立编译和生产解析探针通过
状态: 成功；设备验收由主任务接线执行
