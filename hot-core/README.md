# 原生热更更新核心

当前处于本地 `Dev` 实施阶段。此模块承担签名包验证、内部对象保存和激活恢复状态，不负责自行发布、检查线上版本或立即执行代码。现有 APP 还没有迁移到三层加载结构。

更新与恢复基础使用 Java，仅依赖 Android/JDK 能力，避免它本身依赖待更新的 Kotlin/Compose。业务模块仍按正式设计使用原生 Kotlin/Compose。

已完成：

- 严格 UTF-8 JSON，拒绝重复键、字段别名、浮点控制值、孤立 Unicode 代理项和超限内容。
- P-256/SHA-256，验原始 JSON 字节和用途域；根授权、用途范围、有效期、撤销与信任版本下限。
- ZIP 中央目录与本地头边界、链接/加密/重复路径检查；流式对象 SHA-256 验证。
- 只读内部对象、准确基线恢复、候选与当前版本分离、损坏对象拒绝、原子提交。
- 执行前激活日志、首帧进入试运行、60 秒有效前台观察、故障回退、按快照隔离、旧回调和陈旧写入拒绝。

`ContentStore` 和 `ActivationJournal` 必须由后台更新控制器调用，不能直接放到主线程。准备包不会激活；`begin` 必须在验证本次服务端许可及安全点后才调用。系统进程退出记录的采集由后续宿主桥接负责；不能仅凭日志未结束就上报内容崩溃。

当前待完成：激活许可、防重放、信任列表持久化、资源 ZIP 安全挂载、下载与磁盘清理、实际加载器与生命周期、APP 页面和服务迁移。这里没有内置生产根，也没有把公开测试根接入正式 APP。

验证：

```powershell
.\gradlew.bat :hot-core:testDebugUnitTest :hot-core:assembleDebugAndroidTest
adb install -r hot-core/build/outputs/apk/androidTest/debug/hot-core-debug-androidTest.apk
adb shell am instrument -w app.luoxianlv.hot.test/app.luoxianlv.hot.HotCoreInstrumentation
```

测试 APK 使用独立包名 `app.luoxianlv.hot.test`，不修改用户主 APP 数据。公开向量来自 Go 仓库 `testdata/protocol-v1`，其中产物仅是非可执行测试字节，不是实际 DEX。

完整设计以相邻 `luo-xian-lv-hot-update/docs/design/` 为准。通过这些测试不等于整体系统完成或所有设备已验证。
