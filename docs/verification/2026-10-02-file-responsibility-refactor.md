# 文件职责拆分回归（2026-10-02）

基线 `780c8b0`，实施分支 `codex/refactor-app-responsibilities`。三名子代理分块实施并交叉审查，主代理处理悬浮窗、集成和统一验证。验证完成后按用户要求将职责拆分与资源修复压成一个提交合入 main，不创建 PR。本轮没有修改线上 API 或发版。

## 变更范围

PageSwapHost 的页面容器/会话格式、HostUpdates 的回执/缓存维护、PlaybackSession 的截图队列/快照协议、FloatingControls 的选歌窗/点击标记/窗口参数分别独立。职责与目录见 [索引](../app-file-responsibilities.md)。主类行数分别由 1221/816/1113/578 减至 1167/775/1070/463；拆分以职责边界为依据，不以缩短行数作为行为正确的证明。

没有改 `hot-contract`、系统入口、Manifest、资源资产、签名策略、单版本约束或存储目录。保留主事务与反射测量字段。截图任务计数、队列关闭、过期帧处理和缓冲释放逐段对照；快照默认值、内容代际、输入隔离与回执确认顺序保持。交叉审查发现的两处求值时机前移已恢复。

## 已完成验证

- Debug：buildSrc 19、hot-core 159、app 108 项单元检查，共 281 项通过、5 项既有跳过，0 失败。app-business 测试任务没有独立测试源码，不另计数量。
- 既有 `test-host-stable-recovery-handoff.ps1`：更新调度 20、播放优先 3、双线程稳定恢复 4，共 27 项通过；新 helper 进入全部真实源码编译输入，没有削弱断言。
- 三层 Debug 与 Java 宿主测试 APK 构建通过。
- 三层优化 Release/R8 构建及实际 SDK、资源、映射和内置组合核验通过。使用 `-CompileOnly`；未确认正式签名，报告 `releaseReady=false`，不能作为正式发包许可。
- API 36 x86_64 模拟器：首页/设置/曲库、独立选歌窗口打开与关闭、无障碍 Material 面板、整组准备/提交、部分失败与最新状态回退、后台窗口交接、演练场真实入口/转屏/退出通过。资源顺序修复后的两次整组回归均通过。
- 修改的 Kotlin 文件经固定 ktfmt 格式检查；所有变更文件严格 UTF-8 无 BOM，`git diff --check` 通过。

原始构建日志在 `.local/refactor-responsibilities-*-20261002.txt`。最终设备记录为 `.local/native-host-53220357f6eb4d20b46115e2fc0bb081.txt`、`.local/native-host-33710ded473049e494b77ee51aa42c9e.txt`。离线检查位于 `.local/host-scheduler-a3c92b6d0d96486598821d618106362c/`。

## 回归中发现的资源生命周期问题

首次整组检查在演练场转屏时发生 APP 原生 SIGSEGV，栈首为 `AssetManager2::RebuildFilterList`，沿 `ResourcesManager` 更新配置路径进入；失败记录 `.local/native-host-9bd723f733bc47fc9ce864cb7b74e9c9.txt` 与完整 crash 日志均保留。未修改代码复跑曾通过，不能据此认为间歇问题已修复。

审查本机 Android 36 官方 SDK 源码发现原 `ModuleResources` 先 `removeLoaders`、后 `clearProviders`：前者注销更新回调，后者可能无法触发 ResourcesManager 清除旧 ResourcesImpl 缓存，而 provider.close 立即销毁 ApkAssets。此代码与拆分前一致；日志支持该失效缓存风险，但不能从栈中断言具体损坏对象。

另作最小修复：保存原 providers → 保持 owner 回调时清 providers、刷新框架缓存 → 移除 loader → 清空 owners → 关闭 providers。同步、旧系统分支、数量与接口保持；没有延迟 GC、永久引用或关闭系统共享 AssetManager。该项是明确的关闭顺序修复，与纯职责拆分分开提交。随后重新编译并完成上述两次真实整组回归及最终 R8 核验。

本轮没有重复在线投放热更，也没有遍历 OEM 真机。后续继续拆分时优先选择清晰独立职责；页面交接和播放状态机仍保持集中，避免跨文件散落事务状态。若后续再次出现原生资源异常，应沿保留日志继续分析，不能把两次模拟器通过当成所有系统环境已验证。
