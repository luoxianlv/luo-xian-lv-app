# 演练场背景来源与兼容范围

## WebWallGL

- 上游：https://github.com/oneincase/webwallgl
- 固定版本：npm `webwallgl@1.4.2`，MIT；完整许可随 APK 放在 `assets/wallpaperengine/LICENSE-WebWallGL.txt`。
- npm 包 SHA-512 已核对：`gXYrv9HATvBsnYdNYunfQ8EeJ3bHDWYskvEkUTZlzHSGXBr5Fhw9TYtj//zJvQ+7fEMnjVC+RN4i38wHuR8iFg==`。
- 使用发行包内可读的 `webwallgl.mjs`；相对上游修改见同目录 `webwallgl-1.4.2.patch`，声音通道增量见 `webwallgl-audio-controls.patch`。
- 修改一：调用独立 `compat.mjs`，修正旧 Cutout Vignette 中 vec3 与 vec2 相减的 HLSL 隐式截断；仅匹配该表达式及声明，不修改导入文件。
- 修改二：混合模式着色器的 31 层 if/else 改为等价 switch，避开 Android ANGLE 编译器的 `memory exhausted`。各模式计算式保持原样。

支持原始项目文件夹和 ZIP：scene、video、image/gif、离线 web。ZIP 根目录或外层目录中的 project.json 均可识别；选择唯一的最浅层项目描述，忽略 __MACOSX，不把依赖目录内的 project.json 当成第二份项目。Windows 路径分隔符、资源名大小写、UTF-8 / GB18030 中文 ZIP 文件名均有兼容处理。

ZIP 压缩文件与展开内容分别限制 1 GiB、最多 16,384 项、路径最多 32 层；scene.pkg 仍限制 256 MiB。拒绝路径越界、重复文件和 CRC 错误。失败或取消保留原选择。完整原文件复制进应用专属外部目录，不转码或改写项目。

系统导入入口名为“导入落弦律壁纸”：处理 ACTION_VIEW 和 ACTION_SEND，接收带临时读取授权的 content URI；支持常用 ZIP MIME 和 application/octet-stream，以及无 MIME 的 .zip/.ZIP 文件路径。QQ 文件下载完成后选择“用其他应用打开”即可交给此入口，无需全文件访问权限。应用内 ZIP 菜单与系统入口共用同一导入器。

原始 scene 以本地 WebGL 渲染。WebView 不暴露 JavaScript 原生桥，禁止外网、文件 URL、内容 URI及外部导航；只提供固定渲染资源和当前项目内的只读资源，支持视频 Range 请求。网页项目经过上游兼容 shim，以 scripts-only、opaque-origin iframe 运行，不能访问宿主页面；依赖网络、持久化存储或桌面原生插件的网页不保证正常运行。壁纸音轨默认关闭，可在演练场设置中开启；不同项目的声音支持取决于引擎兼容性。退出销毁 WebView；失败或超时回退预览。默认场景 30 fps、长边最高 1280 像素；直接视频播放保留原始分辨率与帧率。

上游额外补丁：强制严格 webSandbox，并关闭同源网页直接挂载捷径，确保网页使用原有资源重写和 Wallpaper Engine 属性 shim，同时与宿主隔离。

验证：Workshop 2981249186 黑洞 scene、3113554287 时变 scene；后者原始整个目录 ZIP 导入通过。合成图片、视频、网页 ZIP 经系统 VIEW/SEND content URI 完整导入并渲染通过；网页覆盖外部 JS/CSS、大小写差异、fetch 和宿主隔离，视频覆盖连续画面变化；非法 ZIP 验证保留旧选择。没有在真实 QQ 上测试选择器。Windows application、加密 ZIP、mpkg 不支持；复杂 scene 的效果和性能仍取决于渲染器及手机能力，不能据少量样本承诺兼容全部或量化兼容率。

## 默认插画

### 当前默认视频项目（2026-09-28）

- 用户指定 Workshop `3451807308`，界面简称“世界很温柔 · 上杉绘梨衣”。原始 `project.json`、`preview.jpg`、MP4 原样打包到 `src/main/assets/default-wallpaper.zip`，Debug 与 Release 共用。视频为 2800×1968、60 fps、29.952 秒，含 AAC 音轨；未转码。
- ZIP 使用 DEFLATE 最高压缩级别，原文件合计 31,881,236 字节，压缩包 31,619,134 字节。视频本身已压缩，ZIP 进一步缩减有限。包 SHA-256：`42c62c318667f659d8dbde39d5b1bcae6e0f5b5812b31bb19e516b93dbe2a115`。
- 首次启动在 IO 线程解压到 `/storage/emulated/0/Android/data/<applicationId>/files/wallpapers/34518073-0800-4000-8000-000000000001/`。校验后原子发布，`.root` 标记安装完成；中断的临时目录会清理，重启不重复安装、不覆盖已有选择。
- 直接流式读取 APK 资源，不保存第二份 ZIP。APK 内资源是签名安装包的一部分，不能解压后单独删除；实际渲染与预览均读取外部目录。旧伊蕾娜项目仅保留兼容代码，当前安装包不包含它。
- “选择壁纸”右侧声音按钮与“设置 → 演练场设置 → 壁纸声音”共享偏好，默认关闭。预热、开场、后台、退出均静音；恢复可见演练场后按偏好恢复。
- 视频启用通用 `videoAudioControls` 选项，使用保留音轨的单解码器原生循环，避免静音切换重挂载及片尾 A/B 双路解码。原始项目不做单独适配。口琴使用独立 AudioTrack 混音，不按键申请或释放焦点，避免打断或压低壁纸声音；依据 [Android 音频焦点文档](https://developer.android.com/media/optimize/audio-focus)。
- 修复 WebView Range 二次偏移引起的播放中断；读取器保留完整起点，仅限制终点，由 WebView 定位。原因与回归见 `../wallpaper-stream-fix-20260928.md`。
- 自动验证：`tools/test-wallpaper-audio.mjs`；`WallpaperSoundInstrumentation` 覆盖逐文件 SHA-256、重复安装、偏好保留、默认静音、真实媒体音量、播放器复用、口琴与壁纸同播、后台恢复及设置同步。模拟器未启用宿主音频，验证的是播放状态与输出参数，听感仍需真机确认。

### 本地时变背景 Debug 版（2026-09-27）

- 用户指定 Workshop 3113554287：`【可随时间变化】窗旁の伊蕾娜（优化版本）`，原画あずーる azure，动态制作 -夜莺Night（来自原 project.json）。
- 原始 project.json、84,230,588 字节 scene.pkg 和 preview.gif 放在 `src/debug/assets/default-wallpaper/`，只进入此 Debug 构建。APK 内与本机来源逐字节一致；不转码，不改写项目脚本。Release 不包含这份本地素材。
- 此版首次运行重置旧背景选择为内置项目一次；后续导入选择保留。“默认背景”可随时恢复内置项目。
- 跟随手机本地时间，或在背景菜单选择固定 HH:mm；统一 `SceneDate` 注入两个脚本沙箱，场景时段和钟表文字共享来源，原生 Date 和动画时钟不受影响。明确传入日期参数及 Date.UTC/parse 保持标准行为。
- 原作者时段：06:00–09:59 晨景、10:00–16:59 日景、17:00–18:59 黄昏，其余夜景。边界由原项目脚本决定。
- `scene-video.mjs` 为场景内嵌视频使用单缓冲原生循环；暂停隐藏图层时释放 src/解码器，保留播放位置，重新显示时恢复。避免多路 4K 视频和双缓冲同时占用手机解码器。GPU 纹理仍限制长边 1280，但源视频解码仍为原始 4K。
- 加载期间显示原项目预览，第一段视频纹理就绪后淡入。超时回退预览；低性能设备首次解码可能较慢。
- 上游补丁新增两个局部接入点：沙箱 Date 注入、内嵌视频控制器替换；完整差异仍收录在同目录 patch 中。
- 验证命令：`node tools/test-wallpaper-clock.mjs`；Android test 构建使用 `'-PpracticeTestRunner=app.luoxianlv.TimeWallpaperInstrumentation'`，安装测试 APK 后运行该 instrumentation。覆盖默认加载、8/12/18/22 点切换、固定时间下动态帧变化、原始 ZIP 导入、立即退出。

### 无内置项目时的插画回退

- 作者：jensenartofficial，Pixabay。
- 页面：https://pixabay.com/illustrations/sunset-anime-minimal-nature-sky-7628294/
- 图片：https://cdn.pixabay.com/photo/2022/12/01/04/35/sunset-7628294_1280.jpg
- 保留为应用内背景，叠加演奏控件；不提供素材单独下载或壁纸商店分发。

### 预览和首次加载

- 壁纸库固定竖屏，列出内置背景与成功导入的项目，优先使用 project.json 的 preview 字段，兼容 preview.gif/jpg/png/webp。
- 预览后台解码，文件最多 16 MiB、输出长边最多 960；Android 9+ 支持动画预览，Android 8 显示首帧。
- 演练场启动立即加载所选原始项目，与音源、开场动画并行；预览先显示，WebGL/视频首帧就绪后淡入。复杂 4K 源文件仍有硬件解码耗时，预览用来缩短可见反馈等待。
