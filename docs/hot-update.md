# 原生热更

正式安装包由薄宿主加载共享运行时和业务 APK。首次使用需安装带 `app-host` 的完整 APK；后续更新由宿主校验并在安全点生效。完整安装包发布见[发布指南](release.md)。

## 更新范围

| 变更 | 生效方式 |
| --- | --- |
| 业务 Kotlin、Compose 页面、播放逻辑及已支持的资源挂载 | 等待安全点，整组切换 |
| 共享运行时或其 ABI | 下一进程启动 |
| Manifest、系统权限、宿主或稳定契约 | 重新安装完整 APK |

`app-host` 与 `hot-core` 保持纯 Java；共享接口位于 `hot-contract`。`app-runtime` 提供 Kotlin、Compose 等依赖，`app-business` 编译功能目录中的 Kotlin 和 `business-ui`。运行边界见[架构说明](project-structure.md)。

## 版本与兼容性

新热更包只针对一个整数 `targetVersionCode`，取自系统实际安装版本。它与展示版本 `versionName`、接口能力 `hostContract` 分开校验；候选、下载许可、激活和冷启动均检查适用版本。发布与恢复目标必须属于同一安装版本。

历史未指定目标版本的签名清单仍可读取，当前 CLI 不再生成这种通用包。APK 升级后不加载仅适用于旧版本的缓存代码。

业务通过 `compileOnly` 使用真实的宿主与运行时 SDK。宿主和业务内的 `assets/hot/host-contract.sha256` 必须等于实际 `host-contract-sdk.jar` 的 SHA-256；只改 `hostContract` 或接口指纹不能放宽这个检查。

`AppBusinessFactory`、系统组件名和契约类型保持稳定。资源包编号分别为宿主 `0x80`、运行时 `0x7f`、业务 `0x81`。优化 Release 保留跨层 SDK 声明，业务 APK 不混淆，三层资源不单独裁剪。

## 构建与核验

在 APP 独立仓库执行，Windows 使用 `gradlew.bat`：

```sh
bash ./gradlew :buildSrc:test :hot-core:testDebugUnitTest :app-host:exportReleaseNativeBuildReport -PnativeOptimize=true
pwsh -File tools/verify-native-release.ps1 -ExpectOptimized -CompileOnly
```

导出的 APK、SDK、依赖和报告应作为同一组保存。独立 `lxhot` 工具的 `build`／`doctor` 可冻结并重新核验这组产物；编译核验不包含设备健康观察或正式发布。

原生候选 CI 只生成产物，不读取生产签名、管理或存储凭据。正式证书签名候选由[签名准备工作流](../.github/workflows/native-release-prepare.yml)单独生成。

## 签名与发布

安装证书和热更内容签名是两条独立链。宿主保存根公钥；根授权限制内容键、激活键的用途、应用、环境、期限及撤销状态。内容包、对象哈希和短期激活许可均需验证。

生产范围是 `app.luoxianlv / production`；Debug 使用 `app.luoxianlv.debug / test`。远端源站必须 HTTPS，仅 Debug 的本机回环测试允许 HTTP。安装配置可经 `-PhotUpdateConfig=<公开配置文件>` 打包；生产工作流使用 `NATIVE_HOT_CONFIG_JSON`。

`lxhot` 的常用操作如下，替换尖括号参数后执行；完整打包参数以对应 CLI 的帮助为准：

```sh
lxhot init --application-id app.luoxianlv --environment production --runtime-abi <实际ABI> --target-version-code <安装版本号>
lxhot verify candidate.lxhp --root <根公钥文件> --host-contract <契约版本> --app-version-code <安装版本号>
lxhot upload candidate.lxhp --root <根公钥文件> --server <HTTPS源站> --token-file <管理令牌文件>
lxhot publish <快照ID> --fallback <恢复快照ID> --mode staged --expect-revision <当前修订号> --server <HTTPS源站> --token-file <管理令牌文件>
```

先构建、签名并核验完整候选和恢复基线，再上传。`upload` 完整回读对象并登记候选；`publish` 才改变投放。恢复基线应来自最终已验证安装包的实际内置组合。增量省略的对象必须已存在于准确基线或服务端对象库中。

`staged` 从测试设备开始；扩大灰度使用 `rollout`，直接投放须明确 `--mode direct --scope all-compatible`。发布操作检查修订号，不按经过时间自动扩大范围。管理令牌从文件读取，根私钥及内容私钥不进入 APK 或仓库。

## 激活与恢复

下载只完成准备。宿主在执行前重验许可与兼容性，等待页面事务、手势和业务任务允许交接，再显示候选首帧。旧回调和未结束的任务由代际与租约约束。

试运行累计至少 60 秒有效前台或演奏时间；后台等待不计入。创建、资源验证或试运行失败时，宿主隔离问题快照并恢复可用组合。运行时变化不能在同一进程替换。

服务端可暂停、撤销或执行 `rollback --to <快照ID>`，恢复目标仍需有效授权和兼容性。APK 内置 runtime／business 基线及其摘要由安装包签名保护，供本地恢复使用；谱子、设置和壁纸项目保留在用户存储中。

上线前应对实际签名包验证下载、安全点切换、冷启动及回退。候选 CI、离线验签和 SDK 核验分别覆盖自身阶段，不能替代这些设备检查。
