# 1.0.9 卡顿修复核查

日期：2026-09-29。版本号 1.0.9，versionCode 16，可覆盖 1.0.8（15）及此前撤回的测试版本（14）。

## 已确认的原因

友盟卡顿记录 `11733266469046`、主要 ANR `11733266485046`，以及 vivo 的 `11744856464046`，指向曲目行计算时长时触发谱面解析。后者摘要看似不同，详情仍包含 `MatcherNative.setInputImpl → mq1.a:171 → cj1.invoke:478 → tg1.f:889`。使用 v1.0.8 对应的 R8 mapping 按方法和行号还原，分别落在 `ScoreParser.parse`、`Song.events` 和 `SongRow`。

旧解析器用 `Regex.findAll(source).toList()`。Android 上每个匹配结果的 next 都会重建 Matcher、复制完整输入，列表又保留全部匹配器；长谱导致二次增长的工作量和大量原生内存分配。曲库刷新和离屏页面重新组合时在主线程触发，解释了下载完成卡一下、从设置返回后无响应等现象。

另有系统空闲栈类 ANR，免费控制台未提供完整 ANR_INFO；不能仅凭 `nativePollOnce` 摘要认定全部异常都出于同一原因，也不将本轮修复表述为消除所有设备上的卡顿。

## 修复范围

- 单个 Matcher 顺序分词，不再保留匹配结果列表；简谱语法和 10 万音符上限保持不变。
- 曲库首次读取、刷新、另存、删除和悬浮窗选歌读取放到后台。曲目行按需组合，后台计算并缓存时长，不一次构造全部行。
- 同步谱面在写入时完整校验，读取列表时不再重复解析全库；内置谱面复用校验所得事件。
- 播放准备与列表时长各自使用独立单线程队列，列表线程使用后台优先级。事件结果采用安全发布，播放不等待预览所持的懒加载锁。
- 播放服务记录准备期间的播放意图；完成后自动衔接。暂停清除意图，切歌代次阻止旧结果覆盖新选择，服务销毁取消任务。
- 设置页的系统权限查询移出主线程，去掉重复刷新。导航仅在窗口尺寸变化时纠正半页位置，普通动画不再与额外纠偏争抢滚动。
- 下载状态覆盖校验与保存过程，阻止同一首歌重复点击入库。远端修复完成后的曲库读取也放到后台。

保持职责分离：解析器只解释文本，队列只分配执行资源，播放门控只处理请求代次；没有增加全库常驻解析或重写播放节拍，避免为修卡顿扩大行为改动。

## 验证

- Debug、Release 单元测试分别 99 项：97 通过、2 项可选样本跳过，零失败。新增语法、长谱上限、取消和旧请求隔离回归。
- API 36.1 模拟器实测：2,000 音符旧分词 482 ms，新完整解析 52 ms；100,000 音符完整解析 647 ms。旧对照仅分词，新对照包含完整解析，属于同环境功能测试测量，不能直接当作真机帧率。
- `ScorePlaybackInstrumentation`：低优先级队列被阻塞时播放队列仍可运行；真实无障碍服务启动即播放、准备期间取消、快速换曲仅播放最新曲目均通过。使用休止谱，测试不向其他应用发送按键。
- `ExperimentalInstrumentation`：ZIP/MIDI 系统文件选择协议、受保护窗口中八键及升降调/半音、识别缓存保留、关闭固定布局后恢复截图识别通过。与 R8 编译并行时出现一次漏触；停止编译压力后的整套复测通过，保留该限制，不把模拟器当作所有手机的保证。
- 导航压力测试使用 R8 分发包，临时加入 60 首、每首 6,000 音符，覆盖全部点击/滑动及设置返回曲库/发现；结束恢复原曲库。`-e refresh true` 额外触发下载完成同类的刷新事件，因涉及内部单例，仅在未裁剪包运行；R8 包不启用该参数。
- 最终导航功能回归全部通过，零帧回调丢失，原始记录见 `performance/navigation-1.0.9-long-scores.json`。常规切换绘制 P95 约 2–5 ms、布局 P95 不超过 0.5 ms，但窗口总帧耗时 P95 为 193–482 ms，仍明显偏高，不能据此宣称整体流畅度达标。该轮保留用户所选壁纸，与旧固定背景测试条件不同；需继续真机复核，不作直接前后倍数比较。

复测命令：

```powershell
# 解析和真实播放队列测试使用未裁剪包，避免测试专用内部调用被 R8 删除。
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest '-PpracticeTestRunner=app.luoxianlv.ScorePlaybackInstrumentation'
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w app.luoxianlv.debug.test/app.luoxianlv.ScorePlaybackInstrumentation

# 导航使用优化分发包；采样期间不并行本地构建。
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PcompactDebug=true '-PpracticeTestRunner=app.luoxianlv.NavigationFrameInstrumentation'
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e stress true -e label anr109 app.luoxianlv.debug.test/app.luoxianlv.NavigationFrameInstrumentation
```

## 交付状态

已交付 `luoxianlv-1.0.9-debug-performance-20260929.apk`，12,083,603 字节，包名 `app.luoxianlv.debug`，与正式版共存；无友盟统计，使用 R8 优化便于测试实际性能。APK 签名校验通过，SHA-256：`605cbf553bd5e3d72e2e26193a57e1d1806baa7c0000b834a58eba6154a14b34`。用户完成真机测试并确认没有问题，已批准发布 1.0.9；正式包沿用该实现。

## 后续发布要求

CI 检查后发布签名 v1.0.9；GitHub Release、OSS 上传与稳定更新部署按独立流程顺序执行。OSS 使用已实测的 BBR 配置，上传后完整回读并核对 SHA-256，成功后才部署更新清单。保留旧公开版本，手机端按用户要求仅展示「修复了卡顿问题。」。
