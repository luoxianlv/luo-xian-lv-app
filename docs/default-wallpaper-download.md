# 默认壁纸按需下载

APK 不再携带 `default-wallpaper.zip`，也不在启动时联网下载。首次进入演练场先在竖屏询问：下载、稍后再问、不再提示。跳过后使用静态背景，仍可练习。已有默认壁纸或正在使用导入项目时不打扰用户。

「设置 → 演练场设置 → 下载默认动态壁纸」始终允许未安装的用户主动下载，包括选过“不再提示”的用户；安装后隐藏按钮。旧版解压好的默认项目沿用原 ID，无须重复下载。

## 文件与校验

- 文件位于系统返回的应用专属外部目录，通常为 `/storage/emulated/0/Android/data/<包名>/files/`；外部存储不可用时沿用统一内部回退策略。
- `imports/default-wallpaper.download` 为临时 ZIP；解压到 `wallpapers/.install-<ID>`，完整校验后重命名到固定默认项目目录，随后删除 ZIP。不会覆盖用户导入的其他项目。
- 服务端资料大小限 64 KiB；ZIP 限 256 MiB；下载检查 HTTPS 域名、实际字节数与 SHA-256。正式下载入口为 `oss-eo.luoxianlv.cn`，兼容 `oss-luoxianlv.admilk.cn`；不接受其他主机、显式端口、URL 身份字段或片段，也不跟随重定向。解压复用导入模块的路径、数量、空间和 CRC 校验，类型不区分大小写。
- 失败或取消会清理临时文件；用户可以重试。网络请求超时 15 秒，日志只记录异常类型，不保存临时签名 URL。

## 独立上传壁纸

新版使用 EdgeOne 加速私有 R2，旧版继续使用 OSS。两者共用清单，因此发布新壁纸需同时上传相同 ZIP；保留已配置的 ossutil，R2 使用环境变量 `R2_ENDPOINT/R2_BUCKET/R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY`，不把凭据写入仓库：

```powershell
python tools/publish_wallpaper.py --help
python tools/publish_wallpaper.py wallpaper.zip --provider dual --title "壁纸名称" --manifest wallpaper-default.json
```

脚本校验 ZIP，使用内容摘要作为对象路径；`dual` 模式先向 OSS、再向 R2 分片上传并完整回读，两处均校验成功才生成 `wallpaper-default.json`，失败保留原清单。R2 复用 APK 上传模块，仅将文件类型设为 `application/zip`。不传 `--provider` 时仍只上传 OSS，保持旧命令行为。将清单原子部署到 API 的 `public/wallpapers/default.json` 后生效，不需要重发 APK。清单字段为 `title/object/sha256/size`，不能放公开永久下载 URL。

API `/api/wallpapers/default` 签发短时效下载链接并禁止缓存；每个 IP 每窗口限 6 次，默认窗口 60 秒。限流只约束签发接口，不限制已签发 URL 的并发下载。反向代理地址必须在后端 `TRUSTED_PROXY_IPS` 中精确配置；直连来源不能伪造转发头改变额度。

新版资料请求显式带 `?delivery=edgeone`，由服务端签发 EdgeOne 临时链接；不带此参数的旧版请求保留原 OSS 链接。已安装的项目继续沿用，不重复下载。安装包更新与壁纸走 EdgeOne，热更配置及包下发继续走 ESA；客户端不持有任何下载签名密钥。

## 正式分发迁移验收（2026-10-03）

- 新版默认壁纸资料请求带 `delivery=edgeone`；旧版不带参数，继续获得 OSS 链接。新版保留新旧入口白名单，版本头仅附加到配置的 API 同源。
- Android 下载地址、更新解析与版本头边界测试 14 项通过；发布脚本回归 47 项通过，包含双存储完整回读、失败保留原清单及原子替换。
- Debug APK 构建、改动 Kotlin 格式、差异检查通过；本次 11 个变动文件均为 UTF-8 无 BOM。
- 本节记录代码侧验收；正式 EO/ESA 线上完整链路尚在验收，最终结果以部署报告为准。不修改版本号，也不触发发版。

## 历史验证（v1.0.8）

- 默认 ZIP 为 31,619,134 字节，OSS 完整回读 SHA-256 与原 ZIP 一致；APK 已移除这部分资源。
- Android 单元测试 91 项：89 通过，2 项可选测试跳过。
- 模拟器验证真实 OSS 下载、校验解压、动态壁纸首帧、三项提示、跳过后进入与不重复下载。
- 底部提示统一使用单调实际时间：约 4 秒自动消失，恢复前台检查过期，切页消费旧事件，新提示替换旧提示。模拟器覆盖自动消失、后台返回、页面移除及连续提示。
- 后端 37 项测试通过；生产本地验证前 6 次成功、第 7 次返回 429 和 Retry-After，伪造 X-Real-IP 不改变受信任的 CDN 来源额度。

以下为当时的版本处理记录，不代表当前公开版本：验证使用 v1.0.8（versionCode 15）。当时误发的 v1.0.9 Release 与标签已撤回，未发布的 v1.0.8 标签也已移除，等待确认后从实际源码重新打标签；公开版保留 v1.0.7。内部版本号高于误发包的 14，便于已安装该包的手机更新，后续 v1.0.9 使用 versionCode 16 或更高。
