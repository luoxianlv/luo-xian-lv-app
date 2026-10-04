# 开发指南

本指南用于构建和调试 Android 客户端。模块关系与源码入口见[项目结构](project-structure.md)。

## 环境

| 工具 | 要求 |
| --- | --- |
| JDK | 推荐 JDK 21，与原生构建 CI 一致；源码兼容级别为 Java 17 |
| Android SDK | Platform 37；构建时按 AGP 提示安装所需 Build Tools |
| Gradle | 使用仓库 Wrapper，当前为 9.7.1 |
| Android Gradle Plugin | 9.1.1，版本由仓库配置管理 |
| Kotlin | 2.4.20，随构建插件解析 |
| Python | 格式化脚本需要 Python 3；自动化脚本 CI 使用 Python 3.12 |

在 Android Studio 中设置 SDK，或通过 `ANDROID_HOME`、本地 `local.properties` 的 `sdk.dir` 指定 SDK。首次构建需要下载 Wrapper 和 Maven 依赖。

## 构建与安装

在仓库根目录执行：

```bash
bash ./gradlew :app-host:assembleDebug
adb install -r modules/app-host/build/outputs/apk/debug/app-host-debug.apk
```

Windows PowerShell 使用 `./gradlew.bat :app-host:assembleDebug`。调试包包名为 `app.luoxianlv.debug`，可与正式包共存。

`app-host` 是可安装的宿主，会打包 `app-runtime` 和 `app-business` 的基线产物。页面与演奏代码来自 `app/src/main/java/app/luoxianlv/`；修改业务功能时从这里开始。

## 测试与格式化

```bash
bash ./gradlew :app:testDebugUnitTest :hot-core:testDebugUnitTest :buildSrc:test
python tools/format_kotlin.py --check
```

运行 `python tools/format_kotlin.py` 可应用 Kotlin 格式。单元测试位于 `app/src/test/`、`modules/hot-core/src/test/` 和 `buildSrc/src/test/`。

设备测试与专项回归脚本分别位于 `app/src/androidTest/`、`modules/app-host/src/androidTest/` 和 `tools/`。修改触及 Android 生命周期、手势或跨模块加载时，按对应测试入口验证。

原生 Release 构建与发布操作见[发布指南](release.md)。

## 后端联调

客户端默认连接 `https://www.luoxianlv.cn`，账号、谱库、MIDI 编译和更新检查共用构建属性 `updateBaseUrl`。

```bash
bash ./gradlew :app-host:assembleDebug -PupdateBaseUrl=http://10.0.2.2:8787
```

`10.0.2.2` 是 Android 模拟器访问开发机的地址。该参数只接受 HTTP(S) 源站地址，不包含路径。真机可使用 `adb reverse tcp:8787 tcp:8787`，并将地址设为 `http://127.0.0.1:8787`。

应用资源下载地址由服务端返回。第三方登录还依赖服务端登记的 OAuth 回调配置；仅修改客户端地址不能完成 OAuth 联调。

## 开发约束

无障碍演奏需要在设备上开启服务。MIDI 导入使用后端编译接口，文件为 `.mid` 或 `.midi`，上限 4 MB。

系统组件类名、热更契约和工厂入口具有跨版本兼容要求。调整业务目录时同步维护 imports；改动这些入口前检查宿主和业务模块的调用关系。

签名信息和联调凭据使用本地配置或 CI Secrets。构建产物、设备日志和排查笔记保留在本地。
