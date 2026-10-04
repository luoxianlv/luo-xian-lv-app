# 使用与故障排查

## 导出播放诊断

播放或识别异常时，打开「设置 → 播放诊断」查看服务、曲目、屏幕尺寸、点击坐标和当前布局。

1. 打开游戏琴键界面，复现一次问题。
2. 回到「播放诊断」，选择「导出诊断 ZIP」。
3. 在问题反馈中附上复现步骤、应用版本和诊断包。包内含游戏截图，分享前可先检查内容。

诊断包包含运行日志、识别截图、`device.json`、`diagnostics.json` 和 `process-exits.json`。Android 11 起可读取最近的系统退出原因；Java 未捕获异常另存于 `logs/play-debug-crash.log`，保留最近两次记录。

日志使用 UTF-8，异常堆栈保留原文。后台写入队列上限为 256；队列繁忙或写入失败时记录丢弃数量。单份运行日志超过 8 MiB 后轮转，截图保留最近 15 张，日志、截图与诊断 ZIP 合计留存上限为 50 MiB。

实现入口：[诊断页](../app/src/main/java/app/luoxianlv/diagnostics/PlaybackDiagnosticsScreen.kt) → [ZIP 导出](../app/src/main/java/app/luoxianlv/diagnostics/DebugExport.kt)、[日志写入](../app/src/main/java/app/luoxianlv/diagnostics/AppLog.kt)。

## 文件存放位置

业务文件位于系统返回的 `Android/data/<包名>/files/`；外部卷不可写时回退到内部目录。实际路径记录在日志启动消息和诊断包的 `说明.txt` 中。正式版与 Debug 版使用各自的应用目录。

| 相对目录 | 内容 |
| --- | --- |
| `wallpapers/<UUID>/` | 已安装的壁纸项目，`.root` 记录项目根路径 |
| `logs/` | 当前与上一份运行日志、崩溃日志 |
| `logs/shots/` | 识别截图 |
| `diagnostics/` | 可分享的诊断 ZIP |
| `updates/` | APK 更新包 |
| `imports/` | 导入和默认壁纸下载的临时文件 |

账号凭据和偏好保存在内部存储。原生热更新的下载断点也位于内部 `noBackupFilesDir/native-update/downloads`，不属于上述业务目录。文件分享只开放更新包和诊断包子目录。

旧壁纸、更新包和日志在后台迁移。迁移先复制并校验，再重命名发布；失败保留原文件，已有目标不覆盖。旧壁纸副本延迟到后续启动清理，已打开的场景仍可使用原路径。

目录与迁移入口：[AppStorage](../app/src/main/java/app/luoxianlv/shared/AppStorage.kt)、[StorageMigration](../app/src/main/java/app/luoxianlv/shared/StorageMigration.kt)。

## 默认动态壁纸

默认壁纸单独下载，应用启动时不自动下载。进入演练场时可选择「下载」「稍后再问」或「不再提示」；跳过后仍可使用静态背景练习。已有默认壁纸或选用了导入项目时不弹出提示。

未安装时，「设置 → 演练场设置 → 下载默认动态壁纸」可随时发起下载，也适用于此前选择了「不再提示」的用户。安装完成后可离线使用，已有默认项目继续沿用。

下载依次获取资料、核对大小和 SHA-256、解压校验，再发布项目目录。资料上限为 64 KiB，ZIP 上限为 256 MiB，解压后上限为 1 GiB；ZIP 同时检查路径、文件数量、剩余空间和 CRC。下载失败或取消会清理临时文件。

- 下载未完成：检查网络和存储空间后重试；请求过于频繁时稍后再试。
- 进度到 100%：应用仍在校验和安装，完成后才进入演练场。
- 项目安装失败：查看诊断日志中的异常类型；临时签名 URL 不写入日志。

实现入口：[DefaultWallpaper](../app/src/main/java/app/luoxianlv/wallpaper/DefaultWallpaper.kt) → [下载地址校验](../app/src/main/java/app/luoxianlv/wallpaper/WallpaperDownloadUrl.kt)、[ZIP 校验](../app/src/main/java/app/luoxianlv/wallpaper/WallpaperArchive.kt)。

维护者可在仓库根目录发布壁纸对象并生成清单：

```sh
python tools/publish_wallpaper.py --help
python tools/publish_wallpaper.py wallpaper.zip --provider dual --title "壁纸名称" --manifest wallpaper-default.json
```

`dual` 上传到 OSS 和 R2，并完整回读核对后生成清单；失败保留原清单。凭据由本地配置或环境变量提供。将清单发布到 API 的 `public/wallpapers/default.json` 后生效，无需重新发布 APK。
