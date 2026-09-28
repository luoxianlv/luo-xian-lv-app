# 视频壁纸播放中断修复

发布状态：用户发现问题后已暂停；没有提交、推送、标签或工作流分发，OSS 旧版本未删除。

## 原因与证据

默认视频可以短时播放，但随后触发 `DEMUXER_ERROR_BITSTREAM_CONVERSION_FAILED`，界面统一误提示“此场景暂不兼容”。原文件完整解码通过，安装后的逐文件 SHA-256 与 ZIP 一致。

旧 `WallpaperResources` 收到 Range 后先跳过起点，再把这段流交给 WebView；WebView 的输入流加载器也会按请求 Range 跳过一次，造成非零分段二次偏移。错误字节进入视频解封装器后中止播放。真实 WebView 回归中，旧代码请求 `15701137–15705232` 返回读取失败，证明问题在读取链路，不是这张壁纸的定制效果。

参考 [Chromium 输入流加载器](https://chromium.googlesource.com/chromium/src/+/4c509b812ed32980debbc54195ad5bb07f9ff670/android_webview/browser/network_service/android_stream_reader_url_loader.cc)；修复结果以设备回归为准。仅验证原生 `WebResourceResponse.data` 内容不能覆盖 WebView 的第二次定位，因此新增测试经过真实 WebView fetch。

## 实现

- 流从文件起点交给 WebView，不提前 skip；限制读到请求终点，skip/read/available 共用剩余字节数，保留路径校验与 Range 响应头。
- 手机视频通道采用单解码器原生循环，避免在片尾再创建 A/B 备用播放器；保留原始视频、帧率、声音与画质。
- 保留实际媒体错误信息。播放中断、加载失败、渲染进程退出和首帧超时分别记录，避免把所有错误都判成场景不兼容。
- 复用现有读取器和原生视频分支，不增加依赖，不按壁纸 ID 特调。

## 回归入口

- `WallpaperRangeChecks`：通过真实 WebView 对首段、中段和尾段逐字节比较；旧读取逻辑的中段请求失败。
- `WallpaperSoundInstrumentation -e checkLoops true`：静音、有声且口琴演奏后、后台恢复后三种完整循环；检查只有一个带源视频、无媒体错误、播放进度持续推进。
- `node tools/test-wallpaper-video.mjs`：执行实际引擎挂载函数，检查单解码器、原生循环、后台禁止自行播放、首帧回调。
- 原有声音偏好、文件安装、音量切换、后台停音与设置同步检查一并保留。模拟器未启用宿主声音，听感仍需真机确认。

最终验证：上述三种模式各完成一轮约 29.95 秒原始视频循环，分段字节、播放器数量、音量和设置同步均通过。Debug 单元测试 86 项通过、2 项可选跳过，精简 Debug 构建通过。首次并行构建时有声测试因模拟器播放较慢超时；补记进度后单独复测，未放宽错误或停滞检查，三轮全部通过。

新测试包：`artifacts/luoxianlv-1.0.7-debug-wallpaper-loop-fix-20260928.apk`（工作区根目录），43,693,305 字节，SHA-256 `aa6b79d7a324f146d1f183eed597c0c8585c63286480b01218117436a57036e4`。旧 wallpaper-sound 测试包包含此问题，应使用新包覆盖安装；没有发布 Release 或修改 OSS。
