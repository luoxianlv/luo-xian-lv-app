# 页面切换性能排查

## 范围与结论

检查「我的、曲库、发现、设置」之间的点击、横向拖动，以及「我的 ↔ 设置」跨页点击。动画时长、页面顺序、底部胶囊外观与跟手方式保持不变。

已确认的额外开销：

- `AppNavHost` 在组合阶段读取连续页码，使导航宿主随每帧偏移失效。改为传递读取函数，只在胶囊绘制时取值；选中颜色仅在页码变化时更新。
- 胶囊用 `offset`、`width` 实现逐帧伸缩，重复测量和布局。改为固定布局内绘制同尺寸圆角，保留 RTL 坐标方向。
- 首页入口窗口坐标写入 Compose 状态，页面移动时重复触发重组。改为普通矩形缓存，点击时复制坐标供转场使用。
- 壁纸预加载挂在首页组合中，离屏回收会销毁缓存，再返回时重新创建。改为跟随导航宿主生命周期；暂停、销毁时取消待执行的预热任务。
- 飘雪与页面共用绘制层。为雪花建立独立显示列表，更新装饰动画时不再连带重新录制页面内容。

滚动结束后的半页纠偏仍保留，但不再逐帧收集偏移。没有增加页面常驻数量、改写切换动画曲线或引入新渲染库。

## 验证方法

`NavigationFrameInstrumentation` 使用真实触摸和无障碍树定位四个 Tab，检查每次切换后的页面标识。每组相邻页面分别执行四次双向点击和滑动，另测三次「我的 ↔ 设置」跨页往返。窗口 `FrameMetrics` 记录总帧耗时、测量布局和绘制耗时，结果以 UTF-8 写入应用外部目录 `files/diagnostics/navigation-frames.json`。

测量时暂时清除所选壁纸，使用无内置壁纸包的静态背景；结束后恢复原选择，不删除用户文件。

先用普通 Debug 构建测试 APK，再用 `-PcompactDebug=true` 构建并安装待测应用，两版均用 R8，避免将普通 Debug 与优化包混比。测量期间不并行执行 Gradle 构建。

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest '-PpracticeTestRunner=app.luoxianlv.NavigationFrameInstrumentation'
.\gradlew.bat :app:assembleDebug -PcompactDebug=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e label navigation app.luoxianlv.debug.test/app.luoxianlv.NavigationFrameInstrumentation
```

扩展的 `StageEntryChecks` 验证跳到设置页再回首页后仍复用同一壁纸渲染器，再检查进入演练场、后台挂起与恢复、退出返回竖屏。单元测试 84 项中 82 项通过、2 项可选样本跳过。

模拟器为 API 36.1、720×1600、280 dpi、host GPU。窗口总耗时包含宿主 GPU 和调度等待，采样还覆盖转场收尾及雪花动画；数字只作同环境对照，不代表真机帧率或所有手机均无掉帧。最终体验仍应在用户实际出现问题的手机上复核。


## 固定背景对照结果

单位为毫秒；数据来自同一模拟器和 R8 构建，未对结果做异常值剔除。原始记录见 `performance/navigation-before.json` 与 `performance/navigation-after.json`。

| 操作 | 绘制 P95：修改前 → 后 | 总帧耗时 P95：修改前 → 后 |
| --- | --- | --- |
| 首次点击 | 9.10 → 8.74 | 63.55 → 90.29 |
| 点击我的曲库 | 6.81 → 6.29 | 69.49 → 60.93 |
| 点击曲库发现 | 12.78 → 12.92 | 66.21 → 62.68 |
| 点击发现设置 | 11.98 → 11.83 | 77.41 → 69.67 |
| 滑动我的曲库 | 2.09 → 1.69 | 72.31 → 69.48 |
| 滑动曲库发现 | 1.59 → 1.23 | 66.51 → 59.88 |
| 滑动发现设置 | 1.80 → 1.34 | 66.46 → 66.41 |
| 跨页点击 | 4.64 → 4.64 | 64.81 → 61.65 |

三组滑动绘制 P95 降低约 19%、23%、26%；总超时帧比例为 41.5% → 44.2%，首次点击总耗时 P95 也未改善。因此只能确认局部绘制开销减少，不能把本轮数据表述为已消除掉帧或整体帧率显著提升。

固定背景最终整套导航检查通过，帧回调无丢失。此前一轮自动滑动出现过“曲库返回我的”目标页等待超时，整套复测未重现；尚未确认原因，测试已补充失败时截图留存，需结合真机复核。
