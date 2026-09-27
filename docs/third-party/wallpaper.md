# 演练场背景来源与兼容范围

## WebWallGL

- 上游：https://github.com/oneincase/webwallgl
- 固定版本：npm `webwallgl@1.4.2`，MIT；完整许可随 APK 放在 `assets/wallpaperengine/LICENSE-WebWallGL.txt`。
- npm 包 SHA-512 已核对：`gXYrv9HATvBsnYdNYunfQ8EeJ3bHDWYskvEkUTZlzHSGXBr5Fhw9TYtj//zJvQ+7fEMnjVC+RN4i38wHuR8iFg==`。
- 使用发行包内可读的 `webwallgl.mjs`；相对上游修改见同目录 `webwallgl-1.4.2.patch`。
- 修改一：调用独立 `compat.mjs`，修正旧 Cutout Vignette 中 vec3 与 vec2 相减的 HLSL 隐式截断；仅匹配该表达式及声明，不修改导入文件。
- 修改二：混合模式着色器的 31 层 if/else 改为等价 switch，避开 Android ANGLE 编译器的 `memory exhausted`。各模式计算式保持原样。

支持原始项目文件夹和 ZIP：scene、video、image/gif、离线 web。ZIP 根目录或外层目录中的 project.json 均可识别；选择唯一的最浅层项目描述，忽略 __MACOSX，不把依赖目录内的 project.json 当成第二份项目。Windows 路径分隔符、资源名大小写、UTF-8 / GB18030 中文 ZIP 文件名均有兼容处理。

ZIP 压缩文件与展开内容分别限制 1 GiB、最多 16,384 项、路径最多 32 层；scene.pkg 仍限制 256 MiB。拒绝路径越界、重复文件和 CRC 错误。失败或取消保留原选择。完整原文件复制进私有目录，不转码或改写项目。

系统导入入口名为“导入落弦律壁纸”：处理 ACTION_VIEW 和 ACTION_SEND，接收带临时读取授权的 content URI；支持常用 ZIP MIME 和 application/octet-stream，以及无 MIME 的 .zip/.ZIP 文件路径。QQ 文件下载完成后选择“用其他应用打开”即可交给此入口，无需全文件访问权限。应用内 ZIP 菜单与系统入口共用同一导入器。

原始 scene 以本地 WebGL 渲染。WebView 不暴露 JavaScript 原生桥，禁止外网、文件 URL、内容 URI及外部导航；只提供固定渲染资源和当前项目内的只读资源，支持视频 Range 请求。网页项目经过上游兼容 shim，以 scripts-only、opaque-origin iframe 运行，不能访问宿主页面；依赖网络、持久化存储或桌面原生插件的网页不保证正常运行。壁纸音轨静音。退出销毁 WebView；失败或超时回退预览。默认 30 fps、长边最高 1280 像素。

上游额外补丁：强制严格 webSandbox，并关闭同源网页直接挂载捷径，确保网页使用原有资源重写和 Wallpaper Engine 属性 shim，同时与宿主隔离。

验证：Workshop 2981249186 黑洞 scene、3113554287 时变 scene；后者原始整个目录 ZIP 导入通过。合成图片、视频、网页 ZIP 经系统 VIEW/SEND content URI 完整导入并渲染通过；网页覆盖外部 JS/CSS、大小写差异、fetch 和宿主隔离，视频覆盖连续画面变化；非法 ZIP 验证保留旧选择。没有在真实 QQ 上测试选择器。Windows application、加密 ZIP、mpkg 不支持；复杂 scene 的效果和性能仍取决于渲染器及手机能力，不能据少量样本承诺兼容全部或量化兼容率。

## 默认插画

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
