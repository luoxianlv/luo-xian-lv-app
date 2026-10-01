# 非空用户壁纸的生产导入与首帧检查 helper

`app-host/src/androidTest/java/app/luoxianlv/host/NativeUserWallpaperFixture.java` 是独立 Debug 仪器 helper。只允许 `app.luoxianlv.debug`、`test`、`http://127.0.0.1:18472` 和 instrumentation 工作线程；业务 Context 与 ClassLoader 都来自当前 `Bootstrap.source()`。没有修改更新日志、许可或健康状态，也没有调用热更新激活入口。

## 导入入口

同包仪器调用 `NativeUserWallpaperFixture.prepare(runner, home)`。它通过真实 `WallpaperProjectStore.import(Context, Uri, false, checkpoint)` 导入 cache 内一次性 ZIP；ZIP 包含合法 web `project.json`、离线 `index.html` 和 8×8 PNG 预览，生产导入器另写 `.root`。

返回 `JSONObject` 并保存到应用 `files/native-user-wallpaper-fixture-report.json`，主要字段是 `projectId/projectPath/projectHash/fileCount/totalBytes/files/checkpointCalls/selectionRestored/existingProjectsUnchanged`。id 来自生产 `selectedId`，不能把 importer 返回的标题当作 UUID。会核对正常外部目录、生产 `current/root/preview`、原始字节和预览可解码性；旧项目与新项目均保留。导入器短暂选择新 UUID，helper 同步恢复原 `project` 键的存在状态和值，只清理自己创建的 cache ZIP。

根代理已实际构建并在本机 Debug 模拟器执行：UUID `1ae242fd-3d67-435c-bd2a-bdc8d14bb49a`，4 文件、487 字节、`selectionRestored=true`。随后新官方资源故障 E 真实隔离并恢复 D，该非空项目树前后不变。实际回执与限定范围记录于相邻 CLI 仓库 `luo-xian-lv-hot-update/docs/verification/2026-10-01-nonempty-wallpaper-recovery.md`。这项保留证据本身不证明用户项目已播放。

## 渲染入口

同包仪器调用 `NativeUserWallpaperFixture.render(runner, home, projectId)`，可传上面已保留的 UUID。它不会重新导入、改写或删除用户文件，只接受 helper 先前导入的已知 web fixture：元数据、实际入口字节、空 `.root` 和四个文件必须吻合。通过生产 `entries/select` 选择该 UUID，再使用 `startActivitySync` 启动真实 `app.luoxianlv.ui.practice.PracticeActivity`。

成功必须同时满足：

- 生产 `selectedId/root` 与列表 Entry 都指向正常外部 UUID 项目。
- 演练场 `PracticeBridge.ready()`、窗口焦点和当前业务加载器中的真实 `PracticeBackdrop` 就绪；私有 `project` 的 canonical 路径确实等于 UUID 根。
- `renderState=ready`、`prepared=true`、真实 WebView 标题为 `wallpaper:ready`，预览 ImageView 的 drawable 已清空；`static/error`、空项目或只有 `prepared` 均失败。
- WebView 已附着、可见、alpha≥0.99、尺寸非零，并完成自己的 `postVisualStateCallback`。helper 不调用 `evaluateJavascript`，不注入 title 或 ready。
- `UiAutomation.takeScreenshot` 的实际合成帧中，WebView 可见区域有至少 1% 的采样像素匹配 fixture 的固定 `#30485c` 背景，RGB 各容差 8。按屏幕坐标裁剪，并保存 PNG/hash/宽高/匹配计数。该颜色检查还必须结合上面的项目根、真实 ready 和预览清空，不能单独用预览颜色放行。
- 全部用户项目文件树未变；恢复原选择后，实际 Activity 已 destroyed，`PracticeBridge.active()` 为 false。清理失败不会生成成功回执。

正常返回 `JSONObject`；回执在应用 `files/native-user-wallpaper-render-report.json`，帧在 `files/native-user-wallpaper-render.png`。主要字段包括 `projectId/projectPath/projectHash/files`、`backdropProjectPath/renderState/prepared/practiceReady/webTitle/webVisualStateCallback/previewCleared/visibleFrame`、`selectionRestored/stageClosed/allUserProjectsUnchanged`。`visibleFrame` 含路径、SHA256、字节数、尺寸和像素采样统计。失败抛 `AssertionError`，原因保留为 cause/suppressed；会尝试恢复选择并关闭自己启动的 stage。开始时清除自己上轮回执和帧；中途保存的 PNG 可以是失败诊断，只有 JSON 的 `passed=true` 且清理字段齐全才是成功。

生产 `host.mjs` 的 `mount` 会等待 iframe 的 load/first-frame，再写 `wallpaper:ready`。因此这里的证据覆盖已导入小型离线 web 的实际首帧和屏幕像素，不能扩展成连续动画、音频、视频、scene 或任意工坊项目兼容保证。回执明确 `continuousAnimationVerified=false/audioVerified=false`。关闭首次全屏系统提示只允许 `com.android.systemui` 中配对的英文标题与按钮，不点击任何应用授权或更新操作。

## 当前验证状态

新增 render 代码已用 Android SDK 37 与当前宿主/core/contract 编译输出离线 `javac --release 17 -encoding UTF-8` 编译通过；已用真实业务 Kotlin 编译输出的 `javap -p` 对照所用 store/Entry/Backdrop 方法和字段。没有运行共享 Gradle、设备、API 或渠道操作。render 的最终设备结果由根代理接线后执行，当前不得把源码检查当作已成功播放。

共同 Debug 门禁、主线程包装、选择恢复和原子回执写入复用单一小函数；反射仅用于测试读取当前业务代际，保持测试 APK 不引入 Kotlin/Compose，避免掩盖原生三层类加载边界。
