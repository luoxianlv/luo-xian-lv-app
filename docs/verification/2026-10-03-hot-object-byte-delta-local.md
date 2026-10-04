# 热更对象字节差分与普通更新演奏优先检查

本轮仅修改本地源码和行为测试，未启动模拟器、运行 Gradle、上传对象、发布候选或操作生产配置。

`UpdateClient.prepare` 保留原有目标对象复用、完整快照校验、对象库提交和激活许可流程。可选 `HotObjectDeltaResolver` 只负责选取对象传输方式：当前安装包声明的基线，或版本精确匹配并通过既有 `CompiledContract` 宿主 SDK 标记检查的当前稳定快照；不扫描任意历史对象。稳定快照租约贯穿合并与对象库提交，防止 GC 删除正在使用的基线。

独立 `byteDeltaV1` 使用 `LXHOT-TRANSPORT-V1`，绑定 application/environment/snapshotId/targetVersionCode/hostFingerprint、准确双方大小与摘要以及补丁。客户端支持同一目标的不同准确基线，拒绝重复基线目标及补丁大小冲突。来源必须指向同源、准确快照和补丁的接口；HEAD 取得当前短期地址，跨域下载移除安装 Bearer，保留精确 Range。真实读取的补丁字节复用现有 `ObjectDownloader` 预算、断点及实收计数；重建复用 `update-core` 的 `PatchMerger` 和 `ArtifactVerifier`。

有签名说明但无效时直接拒绝候选。基线不适用、接口未提供提示、补丁损坏或隔离合并失败时沿用同一目标完整对象；取消不触发回退。空间需求联合覆盖原完整对象回退、内部复制/资源展开、完整重建输出和补丁，避免同卷重复使用空闲容量。普通 APK 与热更暂存使用独立目录和事务锁。

宿主构造接口：

```java
var local = BundledBaseline.objects(application);
var resolver = new HotObjectDeltaResolver(
    api, root, store, trust, journal, local,
    new app.luoxianlv.update.IsolatedPatchMerger(application),
    new File(nativeUpdateRoot, "byte-delta"));
var client = new UpdateClient(
    api, root, store, trust, journal, controller, quarantine,
    downloads, budget, mounts, elapsed, space, local, resolver);
```

旧构造方法默认没有 resolver，兼容现有完整对象下载。resolver 在安装登记前声明增量能力。

普通 `ApkUpdateBridge` 保留进入任务前的演奏等待。下载、校验和合并期间，后台每 200 ms 最多排队一次主线程空闲状态探测；演奏开始则取消当前任务。后台执行器真正退出及关闭探测后才释放 `busy`，随后抛出 `SharedUpdate.DeferredException`，让业务保留断点并显示可继续的暂停状态。用户主动取消使用独立标记，不归类为演奏延后。关闭后的迟到探测不会取消完成任务。取消钩子的异步收尾由 `update-core.Cancellation` 处理。

执行 `tools/test-hot-byte-deltas.ps1`，独立 javac 17 编译实际相关源码与既有 Android/JUnit 输入，并运行 50 项 JUnit，全部通过。其中新增 8 项热更差分检查、5 项主线程探测/演奏优先检查，另含 37 项既有下载、授权、取消、整组空间和在线准备回归。最后输出位于 `.local/hot-byte-delta-59a247bd96d9473aad41c2b5c432c151`。

新增热更测试用临时 P-256 密钥、真实回环 HTTP/307/Range 和完整文件摘要校验，证明 21 MiB 目标仅下载 64 KiB 补丁能够通过计费网络准入；同时验证坏补丁/合并失败完整对象回退、取消不回退、篡改/错误宿主版本或指纹拒绝、稳定基线 SDK/版本筛选、合并期间 GC 租约和无提示兼容。测试合并器由依赖注入写入预期完整字节，不代表 Android 隔离进程、真实系统安装或健康确认已验收。

工具调用简报：本地限定源码与文档修改、离线 javac/JUnit、回环假服务器及 `git diff --check`；无 Gradle、设备、生产服务或外部凭据操作。
