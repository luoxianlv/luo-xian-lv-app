# 模块拆分回归验证（2026-09-28）

基线：远程 main 的 `0056523`（无障碍截图坐标与打断后 300ms 保护）。本次主要为包/文件拆分、平台入口改名、具名屏幕状态与 Kotlin 格式统一。

## 静态与构建检查

- 将基线文件使用同版 ktfmt 规范化后对照：HarmonicaSampler、HarmonicaVoice、ScoreParser、NoteEvent、PlaybackTimeline、PracticeActivity、PracticeKeyboard、PracticeSession、PracticeGeometry、PlaybackCoordinates、PlaybackInterruptionGuard 的实现一致，只有导入和排版差异。
- 壁纸渲染/预览/资源读取/解包实现与基线一致；项目存储仅包路径与格式调整。
- Manifest、applicationId、资源、音源、默认壁纸和 WebWallGL 资产没有改动；系统导入 Activity 名称保留。
- 格式检查、git diff --check 通过。Debug 和 Release APK 均构建通过，包含 Release R8/资源收缩与 lintVital；这不代表全量 lint 无历史问题。
- Debug DEX 检查未发现友盟/EFS 类。

## 单元回归

Debug 和 Release 各 85 项：83 通过，2 项依赖可选私人素材的测试跳过；另通过环境变量启用了历史归档测试，两套各分析 52 张截图。覆盖谱面解析、时间线、口琴声音、缺失按键/圆环识别、显示稳定性、截图坐标、300ms 保护、ZIP 边界、账号/平台响应解析等。

## Android 模拟器回归

- PracticeInstrumentation：六种模式的识别、同步切换、实际触摸、退出、竖屏 GIF 选择页往返、背景提前加载、开场中断。
- TimeWallpaperInstrumentation：原始默认壁纸、8/12/18/22 点切换、固定时间动态画面、原始 ZIP 导入、立即退出。
- WallpaperImportInstrumentation：系统 VIEW/SEND、octet-stream、带授权 content URI、图片/视频/离线网页渲染、网页隔离、非法包回滚和原始目录 ZIP。
- PlaybackButtonInstrumentation：暂停 DOWN/UP 竞态、取消、旧暂停图标、300ms 窗口内按下/窗口外抬起、无障碍 click、拖动进度回调。

固定时间动画检查首次未在 1.2 秒间隔内观察到像素变化。原测试依赖固定 sleep，已改为最多等待 10 秒，并同时要求媒体时钟前进与像素变化；实际渲染代码未调整。新检查通过后才继续推送。

## 覆盖边界

在上述源码对照、构建和回归覆盖范围内未发现功能回归。没有使用真实账号执行生产环境登录/下载，也未遍历所有品牌真机及第三方壁纸。历史根 app 删除在根工作区独立提交；该工作区没有远程，不混入 APP 仓库提交。
