# 2026-10-04 友盟卡顿核查与本地修复

## 观察范围与证据边界

本次查看友盟 U-APM 的卡顿详情，并按已发布 **1.1.0** 过滤。控制台覆盖 2026-09-28 至 2026-10-04 七天，显示 **9 组问题、11 次事件、6 位受影响用户、87 位活跃用户、328 次启动**，采样率为 **5%**。这些是当前过滤和采样条件下的控制台计数，不能直接外推为全部用户的真实故障比例。

旧版长谱解析问题主要涉及 1.0.8 及更早版本，已经在 1.0.9 将多 Matcher 的正则处理改为单 Matcher 顺序解析，并把列表时长计算移出主线程。本轮没有把不同版本和不同调用栈都归为旧正则问题。

免费控制台限制了完整 `ANR_INFO` 的查看。仅包含 GPU、Binder 或 `nativePollOnce` 等系统调用的采样，不能据此确定业务根因，也不能宣称已经根治。

## 已确认的调用路径

| 版本与详情 | 证据 | 本轮判断 |
| --- | --- | --- |
| 1.0.9，显示事件触发悬浮窗重建 | 使用该正式 APK 对应 R8 mapping 精确还原：`ob0.f:375` 落在 `FloatingControls.render:225 / addView`；`d41.onDisplayChanged:14` 调用 `reposition:126` | 每次显示变更都重建并重新挂载窗口；这一逻辑在修复前的 main 仍然存在，不能因记录来自旧版就忽略 |
| 1.1.0，[11756115016046](https://apm.umeng.com/platform/6ab2b6f174319160830e642e/error_analysis/pa/detail/11756115016046) | 系统 SeekBar 与 FloatingPanel 构造路径 | 首次面板构造有同步开销；展开、收起和倍速操作应复用已有内容 |
| 1.1.0，[11756519539046](https://apm.umeng.com/platform/6ab2b6f174319160830e642e/error_analysis/pa/detail/11756519539046) | Honor LSA-AN00／Android 14，`HwWebview AutofillProvider → AwContents → WebView → PracticeBackdrop.startRendering` | 确认壁纸首次 WebView 构造占据主线程，需提前准备 provider 并避开交互时段 |
| 1.1.0，[11756243150046](https://apm.umeng.com/platform/6ab2b6f174319160830e642e/error_analysis/pa/detail/11756243150046) | vivo V2121A／Android 13，Compose 初次组合经 `AnimatedVisibility → AppNavHost:264 → Scaffold subcompose / measure` | 这是初次布局采样，不足以证明动画死循环；保留动画，减少导航宿主的额外重组和竞争工作 |
| 1.1.0，[11755938231046](https://apm.umeng.com/platform/6ab2b6f174319160830e642e/error_analysis/pa/detail/11755938231046) | `composeInitial / applyChanges → LayoutNodeSubcompositionsState.subcompose → BoxWithConstraints measure` | 栈中没有明确业务 I/O 帧，不把它冒认为谱面解析或某个动画的确定根因 |
| 1.1.0，[11756003950046](https://apm.umeng.com/platform/6ab2b6f174319160830e642e/error_analysis/pa/detail/11756003950046) | Google WebView 内部 `Display.getSupportedModes → onDisplayChanged` | 属于 Chromium 显示模式通知路径；应用可减少自己的重复显示处理，但不能声称消除了 WebView 内部系统开销 |

1.0.9 映射使用的正式 APK SHA-256 为 `a9113b5ab579cbb52f39ce2af951f4e99eafcbbc5a8fe8fc54e1a40ba9887739`，对应 `pg_map_id` 的已核对前缀为 `b723c1ca…`。映射证据只用于该实际产物，不将它套用到 1.1.0 的混淆名。

## 修复内容与行为保持

- **显示事件与悬浮窗**：只比较会影响窗口的宽高、旋转、密度和字体缩放，忽略无几何变化的亮度、刷新率通知；合并短时连续通知。普通转屏更新现有窗口布局，密度或字体变化才重建内容。展开面板提前在空闲时准备并复用；倍速区就地显隐，钳制面板位置但保留气泡拖动锚点；系统已经移除窗口时才重新创建挂载。选歌窗口也优先原位调整尺寸和位置。
- **壁纸引擎**：接入稳定版 `androidx.webkit:webkit:1.17.1` 的后台 provider 启动接口，不要求后台执行 UI 启动任务，也不把 WebView 实例构造搬到工作线程。调用方最多等待 60 秒；取消或等待超时不提前结束真实启动租约，租约随底层回调或实际失败释放，避免跨业务代际继续执行。恢复时补齐尚未创建的实例；启动、实例创建失败均进入已有失败处理。回调再次检查销毁和暂停状态，暂停时不迟到创建渲染器。[AndroidX WebKit 稳定版记录](https://developer.android.com/jetpack/androidx/releases/webkit)、[WebViewCompat 官方 API](https://developer.android.com/reference/androidx/webkit/WebViewCompat)
- **预热与导航**：预热改为实际绘制结束后等待主队列空闲；页面隐藏、手指按住、Pager 移动和生命周期暂停均取消待执行任务并暂停缓存，保留已经准备的实例。触摸观察不消费事件，页面顺序、转场曲线与外观保持原样。
- **更新状态**：弹窗独立订阅完整更新状态，提示与关于页只订阅自身字段；下载进度最多每 100 ms 发布一次，成功和失败终态立即发布，避免每读取 64 KiB 都使整个导航宿主失效。APK 校验、下载源选择及安装流程保持原样。
- **壁纸预览与选择**：缓存引用锁不再覆盖磁盘读取，清理不会等待正在读取的预览，旧任务也不能重新填回已经清空的缓存。项目选择的目录读取和预览准备移到后台，完成后再更新界面选中状态。

这些改动针对明确的重建、重复通知和主线程竞争，复用现有校验及生命周期边界；没有移除已确认的视觉效果，也没有增加常驻全部页面的策略。

## 本地验证与剩余边界

已完成的验证：

- API 36 悬浮窗最终回归通过：连续 200 次非几何显示事件零重建；实际拖动气泡到靠右但未贴边的位置，三轮展开／收起与倍速显隐复用面板，收起后保持原锚点；横竖屏保留面板和选歌搜索，隐藏取消布局并释放缓存。
- API 36 三层原生默认宿主基本验收通过，覆盖 Host、Runtime、Business 的实际启动路径。
- 进度节流及空闲预热调度的独立 Kotlin／JUnit 回归七项通过，覆盖高速更新、单调时钟回绕、重复组合、迟到回调取消、暂停和销毁。
- 本轮导航、更新及调度文件的定向格式检查、差异检查、UTF-8 无 BOM 检查通过。

| 验证项 | 当前状态 |
| --- | --- |
| 最新 24 项专项单元测试 | 全部通过：几何 5、预览缓存 3、进度节流 3、空闲调度 4、启动执行器 6、启动截止与取消 3；App 总计 137 项，0 失败、2 项可选检查跳过 |
| 核心与构建逻辑 | hot-core 159 项，0 失败、3 项可选检查跳过；buildSrc 19 项，0 失败。app-business 单测任务没有独立测试源，不重复计数 |
| 三层宿主壁纸首帧 | 生产导入离线 web fixture，实际演练场 `renderState=ready`、标题 `wallpaper:ready`、真实可见帧和预览清空均通过；原选择恢复、项目树保持、演练场释放通过。未把它扩展为任意场景、音频或连续动画兼容证据 |
| 壁纸预加载与生命周期 | `PracticeInstrumentation -e entryOnly true` 通过：首页真实渲染预热、同一 Backdrop 交接、重复点击保护、横屏揭幕、后台暂停／返回恢复与竖屏退出 |
| R8 构建与产物核验 | 优化三层产物构建成功；`verify-native-release.ps1 -ExpectOptimized -CompileOnly` 通过 SDK、类归属、资源及实际 R8 映射核验。仅编译产物核验，未验证正式发布证书，也未把它当作已签名发版／设备安装证据 |

新增启动截止测试的第一次执行因 JUnit 方法返回值推断为异常对象而未进入测试体，改为明确 `Unit` 后三项均通过；这是测试定义问题。悬浮窗首次仪器执行也受默认 UiAutomation 抑制无障碍服务影响，改用既有 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` 后完成实际窗口回归，未为通过测试修改服务行为。

所有实际设备回归均在本次自建、只读 API 36 模拟器运行，没有操作已连接的用户手机。另启动 API 26 只读模拟器尝试旧 provider 验收，但该镜像没有已注册的 WebView provider，因此没有把这项记为通过；两个自建模拟器均在检查后关闭。旧系统与厂商 provider 仍需补充有实际 WebView 的设备验收。

首次 WebView 实例构造及厂商 Autofill、Chromium 显示模式处理仍有系统主线程工作。模拟器通过不代表全部真机上的冷启动耗时或 GPU／Binder 卡顿已经消失；仍需在出现问题的 vivo、Honor 设备上复核，并观察下一版对应的友盟采样。

本轮仅进行本地修复和验证，**未发版、未部署**。文档不收录用户标识、IP、凭据或原始日志。
