# 悬浮播放器歌曲进度条

原进度条只有 10dp 高，轨道没有滑块；触摸坐标与左右缩进不一致，拖动期间也不更新自己的画面。每个 MOVE 都调用播放定位，造成重复暂停、识别和启动；取消触摸没有清理拖动状态，可能让进度停止刷新。

改为系统 SeekBar，沿用系统的坐标换算、键盘和无障碍操作：

- 底部独立 40dp 热区，4dp 轨道和 14dp 圆形滑块；可直接点轨道，不必命中圆点。
- 主控、倍速和进度区不重叠，曲名继续承担窗口拖动。面板宽度不变，默认高度 86dp，展开倍速后 118dp。
- 按下、移动时只更新滑块和时间预览，松手后提交一次定位。预览只刷新时间文字，文字未变化时不重设，不重建按钮和进度布局。
- 播放刷新不能覆盖手指位置；取消后恢复最新进度，不提交定位。曲目在拖动中失效时结束输入并禁用进度调节。
- 定位沿用现有播放会话及中断保护，不由滑块强制恢复播放。

这次复用系统控件，保留进度输入、面板状态和系统窗口各自的职责；没有新增控件依赖或修改播放协议。

## 验证

- Debug 应用和测试 APK 构建成功。
- APP 单元测试：111 通过、2 项可选测试跳过，0 失败。
- `PlaybackButtonInstrumentation` 在 `Clean_X64_API_36_1` 模拟器通过：宽热区点击、轨道端点、即时预览、刷新隔离、松手单次提交、取消及再次拖动、定位回调同步刷新、拖动中曲目失效、无障碍调节、原暂停竞态与按钮操作。
- 开关倍速分别检查实际测量布局，进度热区未覆盖主控或倍速行；原生绘制预览已检查。
- 改动文件 Kotlin 格式和 UTF-8 无 BOM 检查通过。

复现命令：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest '-PpracticeTestRunner=app.luoxianlv.PlaybackButtonInstrumentation'
adb -s <模拟器序号> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <模拟器序号> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <模拟器序号> shell am instrument -w app.luoxianlv.debug.test/app.luoxianlv.PlaybackButtonInstrumentation
```
