# 项目结构说明

本文介绍 `app/src/main` 下全部文件的职责，帮助新接触代码的人快速定位模块。
阅读前建议先过一遍根目录 [README](../README.md) 了解产品形态。

## 架构总览

单 Activity 的 Jetpack Compose 应用，分层自顶向下：

```text
UI 层（Compose Screen + 共享组件）
   │  每页一个 ViewModel，Activity 作用域共享
   ▼
数据/服务层（曲库、账号会话、无障碍演奏、悬浮窗、应用更新）
   │
   ▼
核心层（谱面解析、播放时间线、服务端 MIDI 编译）
```

- **Compose 单 Activity**：`MainActivity` 只挂 UI 树；四个顶级 Tab（我的-曲库-发现-设置）
  用 `HorizontalPager` 承载，子页面（搜索/平台/导入/关于/账号/诊断）是覆盖层。
- **ViewModel 挂 Activity 作用域**：切 Tab 不丢页面状态；跨页共享一份数据
  （如发现/搜索/平台三页共用 `DiscoverViewModel`）。
- **渲染边界**：列表一律「整列一张卡」的连排语言，行与行直接相邻，无内部分隔线；
  容器半透明压在渐变底上，边界只靠卡片轮廓和留白。

## 文件树

```text
app/src/main/
├── AndroidManifest.xml            # 组件声明：无障碍服务、前台服务、FileProvider
│
├── java/app/luoxianlv/
│   ├── MainActivity.kt            # Compose 单 Activity 入口：挂 UI 树 + 生命周期级服务/热更新对齐
│   ├── PlayerUi.kt                # 悬浮窗等传统 View 的构建辅助（dp/text/column 工厂方法 + 深浅两套悬浮窗配色）
│   │
│   ├── core/                      # 纯逻辑核心，不依赖 Android UI
│   │   ├── harmonica/
│   │   │   └── RustMidiCompiler.kt   # 服务端 MIDI 编译：MIDI 字节发 /api/compile-midi，换回事件时间线转 NoteEvent
│   │   ├── playback/
│   │   │   └── PlaybackTimeline.kt   # 播放时间线：按毫秒查当前应触发的事件下标
│   │   └── score/
│   │       ├── NoteEvent.kt          # 音符事件模型（音高/拍数/休止）+ 演奏模式枚举
│   │       └── ScoreParser.kt        # 文本谱解析：简谱文本 → NoteEvent 列表，含节拍解析
│   │
│   ├── data/                      # 持久化与曲库
│   │   ├── AppearanceStore.kt        # 全局外观：主题模式（跟随系统/浅色/深色）+ 飘雪 + 首页自定义
│   │   ├── ConfigStore.kt            # 琴键布局 KeyLayout（8 键 X/Y + 4 调式按钮坐标）的读写
│   │   ├── DisclaimerStore.kt        # 免责协议同意状态：存协议文本 SHA-256，文本更新即需重新同意
│   │   ├── Kv.kt                     # 键值存储统一入口：FastKV 底层，对外仍返回 SharedPreferences 接口
│   │   ├── SessionStore.kt           # 账号会话（token/昵称）存取
│   │   ├── SongLibrary.kt            # 曲库存储 + 内置示例曲装载
│   │   └── SongRepository.kt         # 曲目增删改查；isMidi 归类规则集中在此，界面不拼字符串
│   │
│   ├── debug/                     # 诊断导出
│   │   ├── DebugExport.kt            # 打包日志/截图/布局/设备信息成 ZIP，经 FileProvider 分享
│   │   ├── DiagnosticRetention.kt    # 诊断文件保留期清理（绝不碰下载的更新 APK）
│   │   └── PlaybackDebugLog.kt       # 播放调试日志：手势/识别/屏幕状态写入 filesDir，有界队列
│   │
│   ├── profile/
│   │   └── ScreenRecognizer.kt       # 截图里定位游戏琴键：8 个音符圆盘 + 4 个调式按钮，并识别当前调式
│   │
│   ├── service/                   # 后台与无障碍
│   │   ├── DisplayStability.kt       # 屏幕尺寸/旋转稳定门：过滤 transient 的显示变化
│   │   ├── FloatingControls.kt       # 悬浮窗（无障碍 overlay）：收起气泡 + 展开胶囊两种形态，可拖动/seek
│   │   ├── KeepAlive.kt              # 国产 ROM 保活：电池白名单/自启动/通知权限状态检测与系统页跳转
│   │   ├── MusicAccessibilityService.kt  # 核心演奏服务：截屏识别 → 按时序注入点击手势，对外暴露诊断状态
│   │   ├── PlaybackCoordinates.kt    # 布局与点按坐标的合法性校验
│   │   └── PlaybackForegroundService.kt  # 播放前台服务：常驻通知保证演奏过程不被回收
│   │
│   ├── ui/                        # Compose UI 层
│   │   ├── AppEvents.kt              # 跨页轻量事件总线（如曲库变更通知），解决离屏页面销毁无法靠重组刷新的问题
│   │   ├── ServiceSync.kt            # 曲目同步给无障碍服务的统一入口（曲库/导入/下载三处共用）
│   │   │
│   │   ├── components/               # 共享组件层：新页面优先复用，勿在页内重复造
│   │   │   ├── ActionPill.kt             # 胶囊主动作按钮（本应用统一的按钮样式）
│   │   │   ├── AppUpdateDialog.kt        # 应用更新对话框（版本信息/渠道/下载/安装）
│   │   │   ├── DisclaimerScreen.kt       # 首次启动免责协议全屏门：同意前不渲染正常 App
│   │   │   ├── FloatingNavBar.kt         # 底部浮空导航胶囊，位置跟随 Pager 手势进度
│   │   │   ├── ImageDecoding.kt          # 图片解码：按最长边降采样，防止大图 OOM
│   │   │   ├── PageTitle.kt              # 顶级页面大标题统一排版
│   │   │   ├── PermissionDialogs.kt      # 首次启动引导：引导开启无障碍服务
│   │   │   ├── PlaybackBar.kt            # 「悬浮窗开关」列表行（图标+标题+状态+SmallSwitch）
│   │   │   ├── Preferences.kt            # QQ 式偏好组件：SettingsCard/分组小灰字/图标行/开关行 + 图标色板
│   │   │   ├── RemoteScoreRow.kt         # 平台谱子行：标题+作者+BPM+QQ 蓝下载图标（发现/搜索/平台共用）
│   │   │   ├── ScreenFeedback.kt         # 一次性提示统一消费（Snackbar/错误弹窗）
│   │   │   ├── SmallSwitch.kt            # 紧凑型开关 40×22dp，勾选色为 QQ 蓝
│   │   │   ├── Snowfall.kt               # 飘雪动效（约 30fps 更新）
│   │   │   └── SongRow.kt                # 曲库曲目行：连排列表行，选中态用整行底色表达
│   │   │
│   │   ├── discover/                 # 发现（浏览+下载公开谱子）
│   │   │   ├── DiscoverScreen.kt         # 发现页：搜索入口 + 公开谱子分批列表
│   │   │   ├── DiscoverViewModel.kt      # 三页共用 VM：加载/搜索/下载 + 5 分钟 TTL 生命周期缓存 + 分批渲染窗口
│   │   │   ├── PlatformScreen.kt         # 平台谱库子页
│   │   │   ├── RemoteScoreList.kt        # 三页共用的谱子列表：整列一张卡，滑近底部自动追加下一批
│   │   │   └── SearchScreen.kt           # 搜索子页
│   │   ├── home/
│   │   │   └── HomeScreen.kt             # 「我的」首页：插画 + 问候 + 悬浮窗开关，左侧竖栏导航
│   │   ├── importer/                 # 谱面导入
│   │   │   ├── ImportScreen.kt           # 导入子页：SAF 打开文档 → MIDI 导入
│   │   │   ├── ImportViewModel.kt        # 导入状态机，成功后发全局曲库变更事件
│   │   │   └── MidiImport.kt             # MIDI 字节预处理（送编译/转谱）
│   │   ├── library/                  # 曲库
│   │   │   ├── LibraryScreen.kt          # 曲库页：筛选分段按钮 + 连排曲目列表
│   │   │   └── LibraryViewModel.kt       # 曲库状态与筛选（MIDI/简谱归类走数据层规则）
│   │   ├── navigation/
│   │   │   ├── AppNavHost.kt             # 导航装配：Pager 顶级 Tab + 覆盖层子页
│   │   │   └── Routes.kt                 # 路由常量
│   │   ├── settings/                 # 设置
│   │   │   ├── AboutScreen.kt            # 关于页：版本信息 + 手动检查更新
│   │   │   ├── AccountScreen.kt          # 登录/注册子页
│   │   │   ├── CalibrationDialog.kt      # 音高校准对话框：琴键/调式按钮坐标百分比输入
│   │   │   ├── PlaybackDiagnosticsScreen.kt  # 播放诊断：服务状态/屏幕手势/按键位置/导出 ZIP
│   │   │   ├── SettingsScreen.kt         # 设置主页：QQ 式分组（组标题小灰字 + 一张白卡一组）
│   │   │   └── SettingsViewModel.kt      # 设置状态：会话/保活/外观/自动更新
│   │   └── theme/                    # 视觉体系（改动前先读各文件头注释）
│   │       ├── Backdrop.kt               # 深浅两套渐变底与「压在底上」的字色（BackdropPalette + CompositionLocal）
│   │       ├── Color.kt                  # 品牌色板：浅色 + 深色两套（主蓝/QQ 蓝强调色/文字/危险色等）
│   │       ├── Containers.kt             # 容器不透明度规则：浅色 ON_BACKDROP_SURFACE_ALPHA / 深色 _DARK
│   │       ├── Theme.kt                  # Material 3 主题：light 走 S+ 动态取色，dark 用固定品牌色板；浮层角色刻意不透明；同步系统栏图标明暗
│   │       └── Type.kt                   # 字阶（沿用 M3 默认，品牌字体待定）
│   │
│   └── update/                      # 应用更新与平台 API
│       ├── AccountResponse.kt            # 账号接口响应模型
│       ├── AppRelease.kt                 # 版本清单与下载渠道（官方 OSS / GitHub 代理），含签名校验
│       ├── AppUpdateViewModel.kt         # Activity 作用域更新状态，前台检查/关于页/对话框共享
│       ├── HotUpdateCoordinator.kt       # 内容热更新：后台静默执行，不暴露手动入口
│       ├── UpdateAutoCheck.kt            # 「自动检查更新」开关与检查节流记录
│       └── UpdateManager.kt              # 平台 API 客户端：谱子列表/搜索/下载/账号/编译等
│
├── assets/
│   ├── disclaimer.txt                    # 免责协议正文（哈希存在 DisclaimerStore）
│   ├── hero_home.png                     # 「我的」页插画（当前为临时占位，发布前须替换）
│   └── builtin-scores/                   # 内置示例谱（简谱文本）
│       ├── night-sky.txt
│       ├── phantom-listening.txt
│       ├── rain-love.txt
│       └── spring-shadow.txt
│
└── res/
    ├── drawable/                         # 矢量图标：通知栏与悬浮窗用的音符/播放/暂停/文件夹
    │   ├── ic_folder_music.xml
    │   ├── ic_music_note.xml
    │   ├── ic_pause.xml
    │   └── ic_play.xml
    ├── values/
    │   ├── colors.xml                    # 遗留品牌色板 + 启动窗口/系统栏底色（Compose 色板见 ui/theme/Color.kt）
    │   ├── strings.xml                   # 应用名等少量字符串
    │   └── styles.xml                    # 启动主题
    ├── values-night/
    │   ├── colors.xml                    # 深色下启动窗口与系统栏底色
    │   └── styles.xml                    # 深色下整套 AppTheme（含系统栏图标反色）
    └── xml/
        ├── accessibility_service_config.xml  # 无障碍服务能力声明
        ├── network_security_config.xml       # 网络安全配置
        └── update_paths.xml                  # FileProvider 路径（更新 APK / 诊断 ZIP 分享）
```

## 关键约定

- **连排列表语言**：列表内部零分隔线，整列一张卡；选中态染整行底色（见 `SongRow`）。
- **半透明容器**：只作用于容器角色；对话框/菜单用的 `surfaceContainer*` 刻意不透明（见 `Theme.kt` 头注释）。
- **深浅色**：主题只从 `MainActivity` 传入一个 `darkTheme: Boolean`（由 `AppearanceStore.themeMode` 叠系统设置算出），
  切换不重建 Activity。渐变底、压在底上的字色、首页卡片渐变、分段按钮选中块这四样不属于 Material 色板，
  统一从 `Backdrop.kt` 的 `BackdropPalette` 取；**新增这类颜色就加字段，不要在各页写死**。
  压在渐变上、又没有容器兜着的 `Text`/`Icon` 必须显式给色 —— 不指定会吃到 `LocalContentColor` 的默认黑，
  浅色下看着正常，深色下直接消失（曾经发生在首页标题与设置图标上）。
  悬浮窗是独立系统窗口，拿不到 `MaterialTheme`，配色走 `PlayerUi.palette(context)`，
  主题变更时由 `SettingsViewModel` → `MusicAccessibilityService.refreshFloatingTheme()` 通知重绘。
- **状态共享**：跨页刷新走 `AppEvents` 事件；服务同步走 `ServiceSync`；不要在页面里直接调
  `MusicAccessibilityService.instance`。
- **请求节流**：发现页数据有 5 分钟 TTL 缓存（`DiscoverViewModel`）；更新检查有独立节流（`UpdateAutoCheck`）。
