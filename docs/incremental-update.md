# 增量更新

网页和旧客户端下载完整 APK。支持增量的客户端优先下载差分，在本机重建完整安装包后交给系统安装；没有合适补丁或合并失败时使用同一目标的完整包。取消只暂停当前任务，不触发整包下载。

首个支持增量的版本仍须完整升级一次。跨版本升级使用旧版本直达目标的补丁，不串联多个版本。目标换版导致旧下载来源失效时，用户可重新检查更新并确认新目标。

## 客户端

`modules/update-core` 负责下载、任务记录、HDiffPatch/Zstd 合并与文件校验。引擎在隔离进程运行，只接收文件描述符；基线来自 PackageManager 的实际宿主 APK，不使用业务热更版本。支持现有四种 ABI，最低 Android 8.0。

更新顺序为：签名说明 → 宿主基线 → 补丁下载 → 隔离合并 → 完整 APK 校验 → 系统安装。最终检查大小、SHA-256、包名、版本和安装证书。已安装 APK 始终只读，输出验证通过后才提交。

普通更新与业务热更使用独立事务和缓存。演奏优先；普通更新开始后等待在途热更退出，并暂停新的热更处理。热更的健康确认与回退机制保持独立。

APK 增量失败可改用完整包。系统安装后的故障需以更高版本修复，不能套用业务热更回退。

## 发布

客户端声明 `deltaCapability=hdiff-w26-zstd-v1`。服务端只对支持能力、命中安装标识灰度的请求提供 `deliveryV1`；现有全包字段和网页入口保持兼容。

说明使用独立内容签名域 `LXUPDATE-MANIFEST-V1`，绑定宿主身份、目标版本、安装证书、基线及补丁摘要。无效签名直接拒绝，短期下载链接不能替代内容签名。

官方存储上传流程最多为最近三个正式原 APK 生成补丁，仅收录至少节省 30% 且不少于 64 KiB 的结果。目标 APK 完成签名后才生成差分，不重新打包已发布的原包。全部补丁须重建并与目标逐字节一致，上传和完整回读通过后才生成可部署清单。

发布工作流增加以下配置：

| 类型 | 名称 | 用途 |
|---|---|---|
| Variable | `UPDATE_INCREMENTAL_PREPARE` | 是否准备增量，默认 `false` |
| Variable | `UPDATE_TOOLS_REF` | 私库工具的固定 40 位提交 SHA |
| Variable | `UPDATE_ROOT_PUBLIC_JSON` | 与宿主信任配置一致的根公钥 |
| Variable | `UPDATE_TRUST_JSON`、`UPDATE_TRUST_SIGNATURE_JSON` | 普通更新内容密钥的根授权及签名 |
| Secret | `UPDATE_CONTENT_KEY_BASE64`、`UPDATE_CONTENT_KEY_PASSWORD` | 加密内容签名私钥及口令 |
| Secret | `HOT_UPDATE_REPO_TOKEN` | 工具私库的只读权限 |

服务端使用 `UPDATE_ROOT_PUBLIC_FILE`、`UPDATE_TRUST_VERSION_MIN`、`UPDATE_DELTA_ENABLED`、`UPDATE_DELTA_ROLLOUT_PERCENT` 和 `UPDATE_DELTA_ROLLOUT_SEED`。增量准备和投放均默认关闭，可单独关闭增量而继续全包更新。补丁仍经私有 R2／EO 下载入口，热更对象继续走其原有渠道。

构建会从实际 SDK 重建宿主指纹和内置恢复基线。新增宿主接口后应完成完整宿主升级，不向已发布的旧 APK 注入新的系统组件。

发布顺序和安装验收见[发布指南](release.md)。
