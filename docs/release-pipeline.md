# 发布流水线

发布分为四个独立工作流，各阶段失败只重跑本阶段。推送 main 仅执行 CI；只有推送版本标签或手动运行 Release 才发布 GitHub 安装包。官方存储上传和 APP 更新清单部署均由管理员分别触发，不会因普通代码提交自动上线。

| 工作流 | 触发方式 | 产出与权限 |
| --- | --- | --- |
| CI | main 提交、PR | 单元测试、APK 构建、发布脚本回归、工作流语法检查；只读仓库 |
| Release | v* 标签、手动填写 release_tag | 签名构建与发布分为两个 job；仅发布 job 可写 Release |
| Upload Release to official storage（oss.yml） | 手动填写 release_tag | 从公开 Release 下载同一份 APK，上传到指定的 OSS 或私有 R2 并完整回读校验；仅该任务获得存储密钥 |
| Deploy stable update | 手动填写 oss_run_id | 读取成功上传任务的 verified-stable 产物；仅该任务获得服务器 SSH 密钥 |

手动任务选择 main 分支。Release 构建源码来自指定标签，执行脚本来自当前工作流，因此已有标签可以用修复后的发布流程重跑，不需要移动标签。

## 操作顺序

1. 在源码中更新 versionName、versionCode 和 `.github/release-notes/<version>.json`，通过 CI 后推送版本标签。
2. 等待 Release 成功。公开附件包括 APK、SHA-256、package.json、release-notes.json；R8 mapping 仅保存在 Actions 的 release-mapping 产物中，保留 90 天。
3. 运行 Upload Release to official storage，填写同一个版本标签。失败可独立重跑，不重新编译、不重新签名。
4. 上传工作流成功后，从运行摘要复制运行 ID，填写到 Deploy stable update 的 oss_run_id。
5. 验证 `/api/update/stable?versionCode=<旧版本号>` 返回新版本，并抽查下载与安装。

命令行示例：

```sh
gh workflow run release.yml --ref main -f release_tag=v1.0.8
gh workflow run oss.yml --ref main -f release_tag=v1.0.8
# 必须等上传及回读校验成功，再填写该次运行的真实 ID。
gh workflow run deploy-update.yml --ref main -f oss_run_id=<成功的上传运行ID>
```

存储导入要求 Release 附带上述发布资料；旧流水线没有这些附件的历史版本不支持直接导入，任务会报错而不会猜测版本号。取消的旧发版任务不受新工作流影响，应手动选择版本重新执行 Release；不要重跑旧工作流的原始运行，否则仍执行旧脚本。

## 私有 R2 配置与迁移

仓库 Variables 的 `DOWNLOAD_PROVIDER` 选择 `r2`（`cf` 为同义值）或 `oss`；未设置时保留 OSS，其他值直接失败。选择 R2 后不会在缺少 R2 配置或传输失败时静默上传到 OSS。

| 类型 | 名称 | 用途 |
| --- | --- | --- |
| Variable | R2_ENDPOINT | `https://<account-id>.r2.cloudflarestorage.com` 私有 S3 API 入口；不接受公开下载域名 |
| Variable | R2_BUCKET | 本项目目标桶名 |
| Secret | R2_ACCESS_KEY_ID | 限定目标桶的对象读写凭据 |
| Secret | R2_SECRET_ACCESS_KEY | 对应 Secret Access Key；不写入仓库或日志 |

R2 使用 boto3 1.43.108、SigV4、`region=auto`、path-style。每片 5 MiB、4 路并发，最多同时持有 4 个分片；每片开始及服务端确认均记录日志，汇总进度以已确认字节数为准。连接超时 15 秒、读取超时 60 秒，每个 SDK 请求最多尝试 3 次，整个上传与回读最多尝试 3 次，工作流上传步骤限时 15 分钟。依据 [Cloudflare boto3 文档](https://developers.cloudflare.com/r2/examples/aws/boto3/)。

上传前 HEAD：已有同摘要、同大小对象不重传，但仍完整回读核对 MIME、字节数和 SHA-256；已有对象的摘要或大小不符时拒绝覆盖。上传失败仅撤销本次 `UploadId`，不删除正式对象、不枚举或撤销其他上传；取消整个 job 可能打断清理，桶应保留未完成分片自动清理策略。R2 重试会重新创建分片任务，不复用 OSS 的本地断点。

R2 APK 对象响应元数据使用 `Cache-Control: public, max-age=2592000, immutable`，允许 ESA 遵循源站策略缓存 30 天；这只是缓存策略，不开放私有桶读取权限。签名必须在读取边缘缓存之前验证，客户端响应仍由 ESA 覆盖为 `no-store`。既有同摘要对象不会被上传脚本重写元数据；迁移时须单独核对并仅对目标键更新缓存元数据，保留内容类型、摘要和字节内容。

验证通过后仍输出原格式的 `channels.oss.object/sha256/size`，对象路径保持 `luoxianlv/release/<version>/<sha256>/app-release.apk`，`verified-stable` 与 `oss.yml` 路径保留，旧客户端和部署来源校验不变。更新 JSON 仍部署到 API，公开下载 URL 由服务端签发，上传脚本只通过私有 S3 API 回读，不签发或打印客户端 URL。

切换前必须先把现行 APK 原字节迁移并校验，再部署服务端签名下载方案，并同步服务器下载提供方与仓库 `DOWNLOAD_PROVIDER`。下载入口必须先验签再读取缓存，R2 的 `r2.dev` 和直接公开源站必须关闭，否则签名和 ESA 限流可被绕过。仅设置上传提供方不能替代上线配置及实际 CF 下载验证。回退需同时恢复服务器和 CI 的提供方，并确保旧桶仍有同一份对象。

本次仅适配 APK 上传；默认壁纸工具仍走旧 OSS。1.0.8、1.0.9 的默认壁纸下载器固定校验旧域名并拒绝跳转，未经新版客户端或兼容入口准备不能把壁纸清单直接改为 CF URL。

## OSS 传输与共用校验

- 大于等于 1 MiB 的文件使用 OSS SDK 分片上传：每片 4 MiB、4 路并发，连接池也为 4。每片每 10% 输出发送进度，并单独记录 OSS 确认与耗时。发送 100% 不代表 OSS 已确认，更不代表下载校验成功。
- SDK 2.19.1 的汇总回调不在每片完成时触发，文件仅有四片且四路并行时可能长期显示 0%，直到全部完成。因此不能凭汇总值判断链路正常；需查看分片日志。2026-09-29 已取消任务中，OSS 实际收到一片 2,186,281 字节，但耗时约 80 秒；分片本身不能解决跨境传输慢的问题。
- 上传前在临时 Ubuntu runner 上启用内核自带 BBR，只影响新 TCP 连接；不安装内核、不修改服务器、不启用付费 OSS 加速。准备失败会终止流程，避免静默退回慢链路。实现参考 [Google BBR 文档](https://github.com/google/bbr/blob/master/Documentation/bbr-quick-start.md)。
- 网络失败最多尝试三次，同一次任务复用断点并跳过已完成分片。新 runner 不继承旧 runner 的本地断点；已完成对象若大小及摘要元数据一致，则跳过上传并重新下载校验。
- 上传完成后通过 APP 下载域名完整读取文件，核对 MIME、字节数及 SHA-256；只有校验成功才生成 verified-stable 产物（保留 30 天）。跨境网络仍可能限制速度，分片不保证带宽提升。
- 上传步骤限时 15 分钟；取消任务可能留下未完成分片。建议管理员按运维策略配置 OSS 未完成分片生命周期规则，本改动不修改桶策略或清理已有对象。
- OSS 账号需要目标前缀的 PutObject、GetObject 和 ListParts 权限；其中 ListParts 用于断点续传。实现依据阿里云[Python SDK 断点续传文档](https://www.alibabacloud.com/help/en/oss/developer-reference/resumable-upload-1)。
- APK 首先与 Release 标签、版本资料、SHA 文件和 GitHub 记录的摘要核对；两条下载渠道使用相同内容摘要。签名证书在构建阶段校验。

## 发布保护

- 构建 job 无 Release 写权限；发布、存储、服务器密钥分别隔离。检出代码不保留 Git 凭据，关键 Action 固定到 commit SHA。
- 使用 Node 24 运行时的 Action，runner 固定 Ubuntu 24.04。Gradle 使用 basic 开源缓存，避免引入默认的商业增强缓存服务。
- APP 更新部署只接受本仓库 main 分支成功的 oss.yml 手动任务产物；禁止使用 PR、失败任务或其他工作流产物。
- 更新清单原子替换，保留 stable.previous.json；拒绝版本号倒退，也拒绝同版本号更换 APK 摘要。重复部署同一包允许通过。
- 存储错误日志只输出异常类型，不打印密钥或签名下载 URL。失败前删除本地旧清单，避免误部署残留文件。
- GitHub 上已经公开的同版本附件不得覆盖，重跑必须与原附件一致。

本地检查：

```sh
python -m pip install oss2==2.19.1 boto3==1.43.108
python -m unittest discover -s .github/tests -v
actionlint
```

## 上传链路实测（2026-09-29）

同一 runner、同一上海入口、相同 16 MiB 随机数据规模，使用正式 Python 分片上传函数和 APP 下载域名，完整校验 MIME、大小和 SHA-256：

| 传输参数 | 最慢分片确认 | 上传及完整回读 |
| --- | --- | --- |
| 默认 CUBIC | 68.7 秒 | 82.03 秒 |
| BBR | 3.5 秒 | 15.48 秒 |

记录：[正式函数对比](https://github.com/luoxianlv/luo-xian-lv-app/actions/runs/36457069632)。另一个 runner 的官方 ossutil 对比中，CUBIC 上传 16 MiB 超过 65 秒超时，BBR 上传 11.38 秒、回读 2.91 秒。网络和 runner 会变化，这些是实测值，不是耗时保证。

手动运行 Check OSS transfer 可重新验证；勾选 compare 会先测旧参数，旧参数失败仅记警告，优化后验证失败则任务失败。测试复用正式上传函数，不生成更新清单，只操作 `luoxianlv/ci-probes/<runId>/` 下精确路径，90 秒硬超时后也清理对象及未完成分片。其凭据另需测试前缀的 ListMultipartUploads、AbortMultipartUpload、DeleteObject 权限。取消整个 job 仍可能中断清理，可按该 runId 精确检查，禁止批量清理正式版本。
