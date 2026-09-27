# 项目结构与阅读路线

本项目使用一个 Android Gradle 模块 `:app`，在 Kotlin 包内按职责划分。没有为每个页面增加独立构建模块，也没有引入 DI 框架。下面的目录以 `app/src/main/java/app/luoxianlv/` 为根。

## 从哪里开始读

| 想了解什么 | 入口与下一步 |
| --- | --- |
| APP 启动与页面导航 | `MainActivity.kt` → `ui/navigation/AppNavHost.kt` |
| 我的页面 | `ui/home/HomeScreen.kt` → 同目录的侧栏、插画、操作区组件 |
| 自动演奏 | `service/MusicAccessibilityService.kt` → `core/playback/PlaybackTimeline.kt` |
| 截图识别 | `service/recognition/ScreenshotAnalyzer.kt` → `profile/ScreenRecognizer.kt` |
| 悬浮窗 | `service/FloatingControls.kt` → `ui/floating/PlaylistContent.kt` |
| 演练场 | `ui/practice/PracticeActivity.kt` → `PracticeKeyboard.kt`、`audio/` |
| 壁纸导入、预览与播放 | `ui/wallpaper/` → `wallpaper/data/` → `wallpaper/render/` |
| 曲库存储 | `data/SongRepository.kt`；曲目模型在 `data/Song.kt` |
| 登录、谱子列表与下载 | `platform/PlatformClient.kt` → `BackendClient.kt`、`ShushuLogin.kt` |
| APK 更新 | `update/AppUpdateViewModel.kt` → `AppRelease.kt` |

## 包与职责

```text
app/luoxianlv/
├── MainActivity.kt, LuoXianLvApp.kt   应用入口与生命周期
├── audio/                           口琴音源、采样与持续发声
├── core/
│   ├── score/                       简谱解析、音符与调式
│   ├── playback/                    播放时间线
│   └── harmonica/                   服务端 MIDI 编译适配
├── data/                            曲库、账号、配置等本地存储
│   ├── Song.kt                      曲目模型、时长与前导休止兼容
│   ├── HiddenBuiltIns.kt            内置谱隐藏清单的 JSON 编解码
│   └── SongRepository.kt            曲目增删改查与同步落盘
├── platform/                        账号与谱面平台 API
│   ├── PlatformClient.kt             平台业务请求入口
│   ├── BackendClient.kt             HTTP、令牌刷新、错误响应
│   ├── PlatformResponseParser.kt    旧字段、分页与默认值兼容
│   ├── ShushuLogin.kt               OAuth/PKCE 与回调校验
│   ├── LicensedMidiDecoder.kt       内存内解密授权 MIDI
│   ├── AccountResponse.kt           账号响应解析
│   └── PlatformModels.kt            平台响应数据类
├── profile/                         截图中的键盘识别
│   ├── ScreenRecognizer.kt          识别流程与布局合并
│   ├── GlyphDetection.kt            局部亮度、连通域和文字分组
│   ├── NoteRowDetection.kt          音符网格候选与排序
│   ├── ModeRowDetection.kt          调式文字恢复和缺失标签补全
│   ├── BorderRowFit.kt              边框行的平移/缩放拟合
│   ├── KeyboardReference.kt         游戏布局的初始几何参考
│   ├── KeyboardGlyph.kt             字形与标签几何结构
│   ├── ButtonBorderDetector.kt      圆环边框检测
│   └── ButtonStateReader.kt         按钮亮暗状态读取
├── service/                         Android 服务与播放编排
│   ├── MusicAccessibilityService.kt 播放状态、恢复、手势调度
│   ├── recognition/                 截图 buffer 生命周期与工作线程分析
│   ├── FloatingControls.kt          悬浮窗口生命周期、拖动与位置
│   ├── PlaybackButton.kt            按下/抬起的播放意图处理
│   ├── DisplayState.kt              有明确宽高/旋转字段的屏幕快照
│   ├── PlaybackCoordinates.kt       截图坐标与手势像素换算
│   ├── PlaybackInterruptionGuard.kt 打断后短时防误续播
│   └── …                            前台通知、显示稳定检查、保活
├── ui/
│   ├── home/                        页面编排 + SideRail/Battery/Hero/Overview/Settings/Clock
│   ├── floating/                    传统 View 配色、播放面板、进度条和选歌列表
│   ├── practice/                    横屏演奏、开场动画、键盘；保留系统 Activity 入口
│   ├── wallpaper/                   竖屏选择页、预览卡片、导入状态
│   ├── library/, discover/          曲库与发现
│   ├── importer/, settings/         导入与设置
│   ├── navigation/                  页面路由
│   ├── components/                  跨页面共用组件
│   └── theme/                       配色、渐变、字阶、系统栏
├── wallpaper/
│   ├── data/                        ZIP 解包、项目验证、选择持久化
│   └── render/                      GIF 预览、受限资源读取与离线 WebView 渲染
├── update/                          APK 发布、下载、校验、热更新和 MIDI 修复编排
└── debug/                           有界日志、截图和诊断 ZIP 导出
```

## 维护边界

- **编排与细节分开**：Screen 组合状态与组件；独立组件处理显示；存储、网络、解码不藏进 Activity。`WallpaperPickerActivity` 只负责窗口/方向/返回，页面位于 `ui/wallpaper`。
- **保留系统组件身份**：两个壁纸 Activity 仍位于 `ui/practice`，这是为保持 Manifest、系统 ZIP 打开入口及已有显式 Intent 的组件名稳定。不要仅为了目录整齐随意改名。
- **服务状态集中维护**：播放 generation、暂停、恢复和手势状态仍由 `MusicAccessibilityService` 协调，避免拆成多个各自可变的状态源。图像转换、坐标计算、按钮输入策略已经分离。
- **外观复用**：页面使用 `GradientBackdrop`、`ActionPill`、`LuoXianLvTheme` 和应用外观偏好；传统悬浮窗用 `ui/floating/PlayerUi`。颜色与透明度集中在 theme，业务页面不重复定义品牌色。
- **平台与更新分开**：`platform/PlatformClient` 负责平台业务；HTTP、响应兼容解析、登录和授权解密均有独立实现。`update` 管 APK 和更新编排。
- **Kotlin 写法服务阅读**：明确的 `data class`、解构、空安全、方法引用和 KTX 监听器优先；作用域函数不多层嵌套；不要把状态机压成一长串表达式。
- **不引入无用抽象**：当前没有多实现需求的 helper 不加接口；纯图像检测保留循环和原阈值，避免为语法糖改动运算顺序。

## 构建变体与资源

- `src/debug`：无统计实现与本地默认壁纸样本；`src/release`：正式统计实现。统计容量回归在 `src/testRelease`，Debug 禁用统计在 `src/testDebug`。
- `src/main/assets/harmonica`：口琴采样；`assets/wallpaperengine`：上游渲染器与本地补丁，来源见 [壁纸说明](third-party/wallpaper.md)。不对第三方 JS 做全量重排。
- `src/androidTest`：设备集成验证；历史私人截图仍在工作区 `tmp`，不提交到仓库。
- 保持已有 preference 名称、导入目录和外部 Intent 行为，移动 Kotlin 文件不做数据迁移。

## 格式与验证

仓库根目录执行（Windows 使用 `gradlew.bat`）：

```text
python tools/format_kotlin.py
python tools/format_kotlin.py --check
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug
```

格式化器固定 `ktfmt 0.64`，按 SHA-256 验证，缓存于忽略的 `.gradle/formatters`。源码统一 UTF-8 无 BOM、四空格；只格式化自有 Kotlin 文件，依赖包不进 Git。

识别回归可设置 `LX_ARCHIVE_FIXTURES` 指向历史 `.gray` 截图目录。设备测试通过 `-PpracticeTestRunner=app.luoxianlv.PracticeInstrumentation` 或 `WallpaperImportInstrumentation` 选择，再安装应用和测试 APK，运行对应 instrumentation。

## 本次整理范围

拆分首页、曲目模型、平台传输/登录/解密、字形分析、截图分析、悬浮窗选歌、壁纸页面与渲染；全量 Kotlin 格式统一。沿用已验证的算法、播放节奏、300ms 防误续播和截图坐标规则。未新增网络协议、存储格式或 Gradle 子模块。较长的播放服务与识别编排仍按流程保留，后续如增加独立业务，再按明确边界拆分。

## 工作区中的旧目录

工作区根的旧版 `app/` 已删除，旧构建缓存移到工作区 `tmp/legacy-android-build-20260928`。当前唯一维护的 Android 源码是 `luo-xian-lv-app/app/`，包路径为 `app/luoxianlv`；根 settings 的 `:app` 映射不变。根目录旧 Android CI/发布入口随旧副本一并移除，当前构建发布只使用 APP 独立仓库内的流程。历史代码可从 Git 和 `archive/` 查阅，旧 JNI 导出保持历史兼容，不再为已删除的旧客户端改名。

## 第二轮整理

- 屏幕状态统一为 `DisplayState(width, height, rotation)`，比较规则保持不变，诊断字符串仍兼容原有格式；截图像素尺寸仍由 `PlaybackCoordinates.Frame` 单独负责。
- 平台接口入口改名 `PlatformClient`，响应解析迁到 `PlatformResponseParser`，旧字段与分页兼容有独立回归测试。
- 识别主流程只组合文字、网格和圆环证据；音符行、调式行及边框拟合分别维护，未改变阈值和候选排序。
- `FloatingControls` 负责系统窗口，`FloatingPanel` 负责展开面板，`FloatingProgressView` 负责进度显示与触摸；暂停输入策略仍沿用已验证的 300ms 保护。
- 未拆散 generation/播放状态/旋转恢复：它们属于同一个状态机，保持一个修改入口比拆成多个互相回调的可变对象更容易检查。

第二轮验证：Debug/Release 各 83 项单元测试通过、2 项可选测试跳过；各跑过 52 张历史截图。Android instrumentation 覆盖暂停竞态、300ms 窗口及进度拖动；模拟器实际开启无障碍并展开悬浮面板，确认视图构建与服务绑定。格式检查和 Debug APK 构建通过。
