# 项目结构

业务代码位于 `app/src/main/java/app/luoxianlv/`，按功能组织。页面、状态、存储和执行代码就近维护，包名与目录一致。

## 仓库目录

```text
├── app/       业务源码与单 APK 构建
├── modules/   宿主、运行时、业务 APK 与热更模块
├── samples/   热更测试应用
├── docs/      开发、发布与使用文档
├── tools/     开发和验证脚本
├── buildSrc/  Gradle 构建逻辑
├── gradle/    Gradle Wrapper
└── .github/   CI 与发布工作流
```

## 功能目录

```text
app/src/main/java/app/luoxianlv/
├── app/           主页面、导航、进程和任务生命周期
├── home/          我的首页、插画和侧栏
├── library/       曲库、MIDI 导入、谱面解析
├── discover/      发现、搜索和远端谱面列表
├── playback/      自动演奏、悬浮窗、进度与暂停保护
├── recognition/   截图、按键识别、布局配置和校准
├── practice/      演练场、琴键、音源和转场
├── wallpaper/     壁纸选择、导入、下载、存储和渲染
├── platform/      账号、登录、网络请求和响应解析
├── settings/      设置、外观、实验选项和关于页
├── update/        更新检查、下载、校验与安装
├── diagnostics/   日志、诊断导出和排障页面
├── shared/        共用控件与存储基础
└── business/      AppBusinessFactory：热更工厂入口
```

## 阅读入口

以下路径相对于业务源码根目录。

| 功能 | 从这里开始 |
| --- | --- |
| 启动与导航 | `app/MainPage.kt` → `AppNavHost.kt` → `MainNavigationState.kt` |
| 首页 | `home/HomeScreen.kt` → `HomeOverview.kt`、`HomeHero.kt` |
| 曲库与导入 | `library/LibraryViewModel.kt`、`ImportViewModel.kt` → `SongRepository.kt`、`ScoreParser.kt` |
| 自动演奏 | `playback/PlaybackSession.kt` → `PlaybackTimeline.kt`、`FloatingControls.kt` |
| 按键识别 | `recognition/ScreenshotAnalyzer.kt` → `ScreenRecognizer.kt` |
| 演练场 | `practice/PracticePage.kt` → `StageEntry.kt`、`PracticeKeyboard.kt` |
| 壁纸 | `wallpaper/WallpaperPickerScreen.kt` → `WallpaperProjectStore.kt`、`PracticeBackdrop.kt` |
| 平台与更新 | `platform/PlatformClient.kt`、`update/AppUpdateViewModel.kt` |

## 构建模块

| 目录 | 职责 |
| --- | --- |
| `modules/app-host/` | 可安装的纯 Java 宿主和系统组件 |
| `modules/hot-core/` | 签名、下载、加载、激活与恢复 |
| `modules/update-core/` | APK 下载、断点恢复、隔离差分合并与完整校验 |
| `modules/hot-contract/` | 宿主与业务的稳定 Java 接口 |
| `modules/app-runtime/` | Kotlin、Compose 和第三方依赖 |
| `modules/app-business/` | 业务 APK，直接编译功能目录中的 Kotlin |
| `modules/business-ui/` | 页面容器、状态交接、主题和资源基础 |
| `app/` | 单 APK 构建及业务测试入口 |
| `samples/hot-runtime/`、`samples/hot-business/` | 热更引擎测试应用 |

正式安装包由 `app-host` 加载 `app-runtime` 和 `app-business`。Gradle 模块名保持原样，例如 `:app-host:assembleDebug`。业务源码只保留一份。

`app` 中保留少量 Java 系统桥，位于根目录、`service/` 和 `ui/practice/`。它们的类名与正式宿主保持一致，功能实现放在 Kotlin 目录。系统组件、`AppBusinessFactory` 和公开契约的兼容要求见[热更机制](hot-update.md)。

## 维护

- 新代码先放到对应功能目录，不按 `ui / data / render` 重建多套层级。
- 单个调用点的小实现可放在调用文件附近；独立流程和状态机分文件维护。
- `shared/` 只收多个功能实际使用的代码。
- 构建和测试命令见[开发指南](development.md)，发布流程见[发布指南](release.md)。
