# 发布

安装包发布、对象存储上传和更新清单部署分别执行。推送 `main` 只触发 CI；原生业务热更使用[独立流程](hot-update.md)。

## 工作流

| 工作流 | 触发 | 产出 |
| --- | --- | --- |
| [CI](../.github/workflows/ci.yml) | `main` 推送、PR | 测试、APK 构建、脚本检查与优化三层核验 |
| [原生热更候选构建](../.github/workflows/hot-candidate.yml) | 手动选择 Debug／Release | APK、SDK、构建报告；保留 14 天 |
| [原生签名包准备](../.github/workflows/native-release-prepare.yml) | 在 `main` 手动填写版本 | 正式证书签名的验收包与核验资料；保留 30 天 |
| [Release](../.github/workflows/release.yml) | `v*` 标签，或在 `main` 手动填写已有标签 | 签名安装包及公开 GitHub Release |
| [Upload Release to official storage](../.github/workflows/oss.yml) | 在 `main` 手动填写已发布标签 | 上传、完整回读校验及 `verified-stable`；保留 30 天 |
| [Deploy stable update](../.github/workflows/deploy-update.yml) | 在 `main` 手动填写成功上传的运行 ID | 原子更新 APP 下载清单 |

常规 CI 的构建任务使用 JDK 17，三层核验使用 JDK 21。候选工作流只生成编译产物；正式签名准备也不会创建标签、发布 Release 或更新下载清单。

## 签名与配置

在仓库 Actions 配置以下值，实际地址与凭据由维护者提供：

| 类型 | 名称 | 用途 |
| --- | --- | --- |
| Secret | `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` | Android 安装签名 |
| Variable | `ANDROID_SIGNING_CERT_SHA256` | 预期安装证书的 SHA-256 |
| Variable | `UPDATE_BASE_URL` | 客户端 API 源站 |
| Variable | `NATIVE_HOT_CONFIG_JSON` | 原生宿主公开配置：正式包名、`production`、HTTPS 源站和根公钥 |
| Variable | `NATIVE_RELEASE_DEFAULT` | 标签发布是否默认选原生宿主 |
| Variable | `DOWNLOAD_PROVIDER` | `oss`（默认）或 `r2`；`cf` 等同 `r2` |
| Variable | `OSS_BUCKET`、`OSS_ENDPOINT`／`R2_BUCKET`、`R2_ENDPOINT` | 所选对象存储的桶与 API 入口 |
| Secret | `OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`／`R2_ACCESS_KEY_ID`、`R2_SECRET_ACCESS_KEY` | 所选桶的上传及回读凭据 |
| Secret | `SERVER_HOST`、`SERVER_PORT`、`SERVER_SSH_KEY`、`SERVER_KNOWN_HOSTS` | 更新清单部署目标及连接凭据 |

`NATIVE_HOT_CONFIG_JSON` 只含公开配置，不放管理令牌或私钥。原生打包会检查 `app.luoxianlv / production / HTTPS`，并使用正式证书核验；不接受 Android Debug 证书。

手动 Release 的 `native_package` 默认开启。标签触发时，仅当 `NATIVE_RELEASE_DEFAULT=true` 且标签源码包含 `app-host` 才走原生路线；重建不支持该架构的旧标签时关闭此参数。工作流脚本与标签源码分别检出，已有标签不必移动。

## 发布顺序

1. 更新 `versionName`、递增 `versionCode`，同步 `.github/release-notes/<version>.json`，通过 CI。
2. 用签名准备工作流生成验收包，检查覆盖安装、页面、播放与恢复；准备包的实际版本和源码应与待发布版本一致。
3. 创建 `v<versionName>` 标签并运行 Release。打包脚本检查实际 APK 版本、标签和安装证书。
4. 对同一标签运行官方存储上传。任务下载已公开 Release 的原始 APK，核对版本资料、大小及 GitHub／SHA 文件摘要，不重新编译或签名。
5. 上传与完整回读成功后，用该次运行 ID 部署 `verified-stable`。检查 `/api/update/stable?versionCode=<旧安装版本>`、下载摘要和覆盖安装。

手动调用示例，先替换版本标签和成功运行 ID：

```sh
gh workflow run release.yml --ref main -f release_tag=vX.Y.Z -f native_package=true
gh workflow run oss.yml --ref main -f release_tag=vX.Y.Z
gh workflow run deploy-update.yml --ref main -f oss_run_id=<成功上传运行ID>
```

每一步需等待上一阶段成功。失败可重跑对应阶段；已公开的同版本 APK、SHA 文件和版本资料必须保持字节一致。

## 核验与产物

公开附件为 APK、`.sha256`、`package.json` 和 `release-notes.json`。Release 的 `release-mapping` Actions 产物保存 R8 mapping；原生路线还保存三层 SDK、报告和恢复 APK，保留 90 天。

```sh
bash ./gradlew :app-host:exportReleaseNativeBuildReport -PnativeOptimize=true
pwsh -File tools/verify-native-release.ps1 -ExpectOptimized -CompileOnly
```

`CompileOnly` 核对实际 SDK、DEX、资源、mapping 与内置基线，结果仍为 `releaseReady=false`。正式打包另执行预期证书核验；设备和更新链路验收需单独完成。

上传提供方不匹配、回读失败或已有 R2 对象摘要冲突时任务失败，不自动切换存储。R2 的 API 入口用于私有 S3 上传；客户端下载入口由服务端配置。切换提供方前须复制并校验既有对象，再同步服务端与 CI 配置。

部署只接受本仓库 `main` 上成功的 `oss.yml` 手动运行。它保留 `stable.previous.json`，拒绝版本号倒退和同版本更换 APK。完整安装包修复应使用更高 `versionCode`；业务热更回退见[热更机制](hot-update.md)。
