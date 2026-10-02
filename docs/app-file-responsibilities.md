# APP 文件职责索引

本轮从 `780c8b0` 新建本地 `codex/refactor-app-responsibilities` 分支。目标是小范围拆分职责，不重新设计业务流程；核心状态机仍在原主类，辅助类按现有模块归属放置，没有新建抽象框架。

## 主类与协作类

| 主类 | 保留职责 | 提取职责与位置 |
|---|---|---|
| `hot-core/.../PageSwapHost.java` | 页面准备、首帧、交接、安全点与回退 | 同包 `PageContainer` 管输入/焦点/无障碍隔离；`PageSessionState` 管会话格式与内容代际 |
| `app-host/.../HostUpdates.java` | 自动检查、授权、激活、取消、调度与退避 | 同包 `HostUpdateReports` 管持久健康回执；`HostUpdateContent` 管闲置缓存回收与保护集合 |
| `app/.../business/playback/PlaybackSession.kt` | 播放进度、暂停、显示状态、识别结果应用与交接 | 同包 `PlaybackScreenCapture` 管截图队列/任务计数/缓冲释放；`PlaybackSnapshot` 管快照基础值协议 |
| `app/.../service/FloatingControls.kt` | 浮窗显示意图、面板、拖动停靠与播放器协调 | `ui/floating/FloatingPlaylistWindow` 管选歌窗/焦点/键盘；`FloatingTouchMarker` 管临时点击标记；`FloatingWindowLayout` 提供三种窗口共用的基础参数 |

这些辅助类型只在模块内部使用。保持系统入口、Manifest、资源、SDK 接口、签名、单版本限制和原有存储位置；后台任务与 UI 仍沿原线程模型运行。

## 阅读与后续修改

先从主类看业务时序，再进入对应协作类修改具体职责。涉及截图回调或后台任务时，继续保留请求代际、取消、缓冲释放和线程退出门禁；涉及交接快照时保留字段名、schema、默认值及读取时机。不要在窗口类中解析曲目，也不要让回执或缓存类改变激活政策。

本轮将曲目编码先于进度读取、选歌内容构建先于屏幕尺寸读取的原顺序保持下来。公开接口和测试测量字段保持；仅为离线编译脚本补充新 helper 输入，并扩展现有设备检查覆盖选歌打开/关闭。

复用真实共用的窗口参数和状态协议体现 DRY；主协调类与辅助职责分开体现 SRP。未引入只为拆文件而存在的接口、事件总线或依赖注入框架，遵循 KISS/YAGNI。

验证和单独的资源生命周期修复见 [本轮回归记录](verification/2026-10-02-file-responsibility-refactor.md)。
