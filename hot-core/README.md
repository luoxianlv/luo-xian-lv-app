# 热更宿主核心

`hot-core` 为 `app-host` 提供下载、验签、加载、激活和恢复能力。使用 Java，仅依赖 Android/JDK；业务 Kotlin 和 Compose 由独立 APK 提供。

源码位于 `src/main/java/app/luoxianlv/hot/`：

| 流程 | 入口 |
| --- | --- |
| 配置与身份 | `HostUpdateConfig`、`InstallationIdentity` |
| 签名与清单 | `HotSignatures`、`SignedSnapshot` |
| 调度与下载 | `UpdateClient`、`UpdateSchedule`、`ObjectDownloader` |
| 内容存储 | `ContentStore`、`ContentLeases` |
| 激活与交接 | `ActivationController`、`GroupHandover` |
| 加载与恢复 | `NativeLoader`、`BundledBaseline`、`OutcomeRecovery` |

共享接口位于 `hot-contract`。业务代码位于 [app 功能目录](../app/src/main/java/app/luoxianlv)，模块关系见[项目结构](../docs/project-structure.md)，版本约束与发布流程见[热更机制](../docs/hot-update.md)。

在仓库根目录运行核心测试：

```sh
bash ./gradlew :hot-core:testDebugUnitTest
```

设备仪器入口为 `app.luoxianlv.hot.test/app.luoxianlv.hot.HotCoreInstrumentation`；真实宿主回归使用 `app.luoxianlv.debug.test/app.luoxianlv.host.NativeAppInstrumentation`。构建与安装命令见[开发指南](../docs/development.md)。
