# APP 双渠道更新

两个渠道使用同一份签名 APK。正式升级必须保持 applicationId、签名证书一致，versionCode 每次递增；versionName 是展示版本，不能用于判断升级。当前代码默认 2.2.1 / code 4，构建参数可覆盖版本。

## 客户端

- 前台自动检查，成功检查后 6 小时内不重复请求；失败不弹窗打扰。设置 → 关于 → 检查新版本可随时手动触发。
- 自动与手动共用同一更新弹窗，沿用 Material 动态主题、渐变与圆角。显示更新说明、下载源、进度和失败重试。
- 默认官方 OSS，允许切换 GitHub；下载或校验失败会尝试另一个源。每次尝试重新下载，不支持断点续传。
- 下载后校验 SHA-256、大小（配置时）、包名、versionCode 和签名。通过系统安装器请求安装，首次授权未知来源后返回继续。
- Release 默认服务地址 https://luoxianlv.com；本地 Debug 构建用 `-PupdateBaseUrl=http://10.0.2.2:8787`。明文 HTTP 只允许 Debug 的本机地址。
- 普通版本可稍后再说；`mandatory` 或低于 `minSupportedVersionCode` 时不可关闭更新弹窗。

## 服务端与手动 OSS 发布

`GET /api/update/stable?versionCode=4` 返回版本清单及 updateAvailable；加 `channel=oss` 或 `channel=github` 可将指定渠道映射到旧客户端的 apkUrl。响应 no-store，每次读取磁盘清单，不需重启。`enabled:false` 可撤回更新。

1. 用固定正式 keystore 构建 release APK。两个渠道上传完全相同的文件。
2. 手动上传到 OSS，取得 HTTPS 直链；上传同一 APK 到 GitHub Release，取得下载直链。
3. 在 Windows SDK 环境生成清单（自动读取真实 APK 版本、大小和 SHA-256）：

```powershell
./tools/publish-update.ps1 -Apk ./app-release.apk -VersionName 2.2.1 `
  -OssUrl 'https://your-oss-host/releases/app-2.2.1.apk' `
  -GithubUrl 'https://github.com/luoxianlv/luo-xian-lv-app/releases/download/v2.2.1/app-release.apk' `
  -Notes '新增双渠道更新' -ManifestPath ./public/updates/stable.json
```

4. 将清单放入运行服务 PUBLIC_DIR/updates/stable.json。建议挂载 updates 目录供容器外维护，使用临时文件后原子替换。不要只修改源码目录却未挂载到容器。
5. 请求接口检查版本与两个下载链接。仓库默认清单禁用更新，防止无有效 APK 时误推。

## 私库 Release 模拟

私库下载需要凭据，APP 不能携带 GitHub token。服务端设置 `GITHUB_UPDATE_REPO=luoxianlv/luo-xian-lv-app` 和 `GITHUB_UPDATE_TOKEN`（仅需该仓库内容读取权限）；清单中的 github.url 使用服务端 HTTPS 地址 `/api/update/github`，github.assetId 填真实 Release asset ID。服务端仅为当前清单的 asset ID 获取 GitHub 短期签名跳转，token 不传给 APP。

此接口会把所配置的 APK 分发给访问本服务的用户；只配置用于分发的 APK，不配置其它私有资产。关闭更新时接口也应停用。正式公开仓库可直接使用公开 Release URL，无需 token。

本地测试可以构建 code 4 和 code 5 的 Debug APK（同一 debug key），私库发布标记为 prerelease 的测试包；这不代表正式签名发布完成。Debug 测试包不能覆盖使用其它签名的正式安装。

## 验证命令

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest '-PupdateBaseUrl=http://10.0.2.2:8787'
cargo test --manifest-path luo-xian-lv-server/Cargo.toml
cargo run --manifest-path luo-xian-lv-server/Cargo.toml
```

独立 APP 仓库模块目录为 app；聚合工作区模块目录为 luo-xian-lv-app。独立服务端仓库直接运行 cargo 命令。
