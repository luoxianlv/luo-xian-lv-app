# 琴键识别

识别器从游戏截图定位八个音符键和四个音区按钮，返回归一化坐标，以及当前音区和半音状态。它按琴键的文字、行间几何和圆环边框组合证据，不使用通用 OCR 服务。

## 从哪里开始读

业务源码集中在 `app/src/main/java/app/luoxianlv/recognition/`。

- [ScreenRecognizer](../app/src/main/java/app/luoxianlv/recognition/ScreenRecognizer.kt)：识别入口、候选选择和结果。
- [NoteRowDetection](../app/src/main/java/app/luoxianlv/recognition/NoteRowDetection.kt)、[ModeRowDetection](../app/src/main/java/app/luoxianlv/recognition/ModeRowDetection.kt)：两排文字的定位与补全。
- [ButtonBorderDetector](../app/src/main/java/app/luoxianlv/recognition/ButtonBorderDetector.kt)、[BorderRowFit](../app/src/main/java/app/luoxianlv/recognition/BorderRowFit.kt)：圆环搜索和行拟合。
- [ScreenshotAnalyzer](../app/src/main/java/app/luoxianlv/recognition/ScreenshotAnalyzer.kt)：截图转换、资源释放和诊断日志。

## 输入与坐标

`fromBitmap` 接收 Android 位图。宽度超过 1024 像素时按比例缩小，再转换为灰度数组；较小图片保持原尺寸。`analyze` 接收灰度数组及宽高，宽高均为正数，数组长度等于宽乘高。

识别目标是两排游戏琴键：下排八个等距音符键，上排从左至右为半音、升调、自然音、降调。几何参考仅用于限定候选搜索范围，最终位置由图像证据修正。

坐标按完整截图宽高归一化。播放端通过 [PlaybackCoordinates.Frame](../app/src/main/java/app/luoxianlv/playback/PlaybackCoordinates.kt) 用同一截图尺寸还原像素位置；系统显示尺寸可与截图不同。布局检查包括坐标有限、位于图像内、八个音符横坐标递增，以及四个音区按钮齐全。

## 识别规则

1. 通过局部亮度差和绝对亮度阈值提取亮字形，组成连通域候选。
2. 常规路径至少用五个同高、同行的字形匹配八键网格。边缘字形缺失时，再用音区标签或邻近升号校验起点，避免整行偏移一个键。
3. 音区文字先尝试完整行；至少两个位置和行高一致的标签可补全其他位置。用于校验音符行起点的路径要求三个标签。
4. 文字路径不足时，至少三个音符字形可提出候选；候选还需至少六个音符圆框和三个音区圆框支持，才会接受补全。
5. 圆框检测使用 Sobel 梯度，沿圆周 48 个方向采样，先粗搜索再逐像素修正。两排分别拟合平移与缩放，并按圆心、半径和网格残差排除异常候选。

文字和圆框证据分别记录为 `observedNotes`、`observedModes`、`noteBorders`、`modeBorders`。推算出的按钮位置不等于实际看到了该按钮。

音区状态只从有直接文字或边框证据的按钮读取。证据或亮度差不足时，`mode` 或 `halfTone` 为 `null`；播放端保留原状态。单个亮点、完全缺失的文字与边框不会被当成完整键盘。

## 截图与播放衔接

无障碍截图需 Android 11；Android 8–10 可使用已授权的 Shizuku 截图，旧系统走 PNG 兼容路径。固定口琴布局和演练场使用实际几何，不请求截图。

截图转换和识别在工作线程执行；硬件缓冲及位图在处理后释放。主线程应用结果前再次检查播放请求代次和显示状态，暂停、换曲或旋转后的旧结果不覆盖当前布局。流程入口在 [PlaybackSession](../app/src/main/java/app/luoxianlv/playback/PlaybackSession.kt) 的 `syncWithScreen`。

## 回归与排查

在仓库根目录运行识别测试：

```sh
bash ./gradlew :app:testDebugUnitTest --tests "app.luoxianlv.ScreenRecognizerTest" --tests "app.luoxianlv.ButtonBorderRecognitionTest" --tests "app.luoxianlv.PlaybackCoordinatesTest"
```

Windows 使用 `gradlew.bat`。测试覆盖文字缺失、圆框补全、两排缩放、错误候选和坐标转换；调整阈值时同时检查识别出的坐标和状态。

实际识别失败时，先确认琴键界面已展开，减少系统弹窗和悬浮窗遮挡，再查看诊断包中的截图、证据数量、尺寸和识别耗时。位置补全无法让手势穿过系统弹窗。诊断包的获取方法见 [使用与故障排查](operations.md)。
