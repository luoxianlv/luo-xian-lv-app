# 落弦律 Android 客户端

[![CI](https://github.com/luoxianlv/luo-xian-lv-app/actions/workflows/ci.yml/badge.svg)](https://github.com/luoxianlv/luo-xian-lv-app/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/luoxianlv/luo-xian-lv-app?display_name=tag)](https://github.com/luoxianlv/luo-xian-lv-app/releases)
[![AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

[落弦律口琴谱社区](https://www.luoxianlv.cn)的 Android 客户端。浏览和管理谱面，通过无障碍服务在口琴界面自动演奏，也可在内置演练场练习。

## 下载与使用

支持 Android 8.0 及以上版本，安装包见 [Releases](https://github.com/luoxianlv/luo-xian-lv-app/releases)。

1. 安装后阅读应用内使用声明，按提示开启无障碍服务。
2. 从发现页下载谱面，或在曲库导入 `.mid` / `.midi` 文件。
3. 打开目标应用的口琴界面，在悬浮窗选择曲目并播放。

谱面下载、账号同步和 MIDI 导入需要联网；已保存的本地曲目可离线使用。MIDI 文件上限为 4 MB。

悬浮窗无法持续显示时，可在设置中的「后台运行保护」检查通知、电池优化和自启动设置。

## 功能

- **谱库与同步**：浏览、搜索公开谱面，登录后同步社区收藏，管理本地曲库。
- **自动演奏**：识别琴键布局，通过悬浮窗播放、暂停和切歌。
- **谱面导入**：导入 MIDI 文件，使用服务端编译生成演奏谱面。
- **演练场**：内置口琴键盘与音源，支持音区和半音切换。
- **壁纸与外观**：选择、导入壁纸，调整主题和首页显示。
- **账号与更新**：邮箱登录、第三方登录，以及应用内版本检查。

## 开发

推荐使用 JDK 21，安装 Android SDK Platform 37。Gradle 和 Kotlin 版本由仓库管理，详细配置见[开发指南](docs/development.md)。

```bash
git clone https://github.com/luoxianlv/luo-xian-lv-app.git
cd luo-xian-lv-app
bash ./gradlew :app-host:assembleDebug
```

Windows PowerShell：

```powershell
./gradlew.bat :app-host:assembleDebug
```

安装包位于 `app-host/build/outputs/apk/debug/app-host-debug.apk`。该构建会将运行时与业务基线打包到宿主中，调试包可与正式包共存。

运行单元测试与格式检查：

```bash
bash ./gradlew :app:testDebugUnitTest :hot-core:testDebugUnitTest :buildSrc:test
python tools/format_kotlin.py --check
```

## 源码入口

| 目录 | 内容 |
| --- | --- |
| [app/src/main/java/app/luoxianlv](app/src/main/java/app/luoxianlv) | 按功能组织的 Kotlin 代码：曲库、播放、识别、演练场、壁纸等 |
| [app-host](app-host)、[app-runtime](app-runtime)、[app-business](app-business) | 宿主、运行时与业务模块的构建入口 |
| [hot-core](hot-core)、[hot-contract](hot-contract)、[business-ui](business-ui) | 热更加载、跨模块契约与页面基础设施 |
| [tools](tools)、[.github](.github) | 开发工具与 CI、发布自动化 |

目录职责、页面入口和模块边界见[项目结构](docs/project-structure.md)。

## 文档与贡献

- [文档索引](docs/README.md)
- [开发指南](docs/development.md)
- [项目结构](docs/project-structure.md)
- [贡献指南](CONTRIBUTING.md)

问题反馈请提交 [Issue](https://github.com/luoxianlv/luo-xian-lv-app/issues)，代码和文档修改可提交 Pull Request。

## 许可证与使用条款

源码采用 [GNU AGPL-3.0](LICENSE)。使用声明与相关条款见[免责声明与使用条款](docs/use-terms.md)。
