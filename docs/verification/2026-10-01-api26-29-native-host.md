# Android 8 与 Android 10 的真实三层宿主回归

本轮在本机新建的独立 API 26、API 29 AOSP x86_64 模拟器执行，原 API 36.1 可见模拟器保持运行。仅 Debug 本机测试配置，没有发布、生产连接或用户真机操作。

## 修复

旧系统的 `AssetManager` 本身也持有资源配置。原实现只为窗口创建新的 `Resources`，仍共用同一资产管理器；真实检查观察到先创建的夜间窗口随后读到了日间颜色。现在旧系统每个窗口创建独立管理器，加载宿主、运行时和业务路径，退役时只关闭本代拥有的管理器；API 30 以上的公开 `ResourcesLoader` 分支保持原逻辑。

API 26/27 的 ART 会在分支执行前解析 `ImageDecoder.OnHeaderDecodedListener`。将新版预览解码器隔离到 `Api28` 类后，旧分支继续使用有尺寸限制的 `BitmapFactory`。恢复进程的 Debug 回执改用 API 26 已有的 UTF-8 字节写入。

仪器入口也补齐兼容：有界读流替代新版 `InputStream.readAllBytes`，仅新版系统调用 `UiAutomation.clearCache`。API 26 实际全屏提示的标题为 `Viewing full screen`、按钮为 `GOT IT`，两者属于 `android`；只允许这对同包框架提示或已知 SystemUI 提示，未点击应用协议或授权。

## 实际结果

两个系统均通过完整默认 `NativeAppInstrumentation`：加载器边界、跨包 Material 主题、夜间/字体/横竖屏窗口隔离、损坏基线修复、首页/设置/曲库、真实无障碍与 Material 浮窗、两个业务加载器间播放交接及故障回退、演练场进入退出、Debug 不含统计 SDK。

API 26 还执行了独立 `HotCoreInstrumentation`，签名向量、完整/增量恢复、只读对象、旧快照、激活日志与隔离、页面/结果/代际交接及 HTTP 续传通过。核心最新 JVM 共 149 项，146 通过、3 项既有 Windows 链接能力跳过。

原始记录保存在 APP `.local/native-api26-angle-default-device.txt`、`.local/native-api29-default-device.txt`、`.local/native-api26-core-device.txt`。实际宿主 APK SHA-256 为 `4ca6d255ba18731f9889955b3381e5568939d28252376e977683f4cba0bb9f70`，API 29 设备上的测试 APK 为 `762bdf6181c326741c303b5fb5fb27895176eec0eb041a04db87021f9c5ff600`；本轮构建时源码包含尚未提交的兼容修复，不能将当时提交号单独当作全部输入。

API 26 最初使用 host 图形后端，测试被未识别的系统全屏提示阻塞，超时清理还出现 `EGL_BAD_ALLOC` 原生中止；换 ANGLE 后端并修正提示识别后整套通过。失败记录没有算作成功，也不能用模拟器后端表现推断真机 GPU 性能。

## 边界

这些结果覆盖当前 Debug APK 的基线运行与同内容双加载器交接，不等于不同新版 APK 的全部在线/冷故障矩阵、Release 统计授权、任意工坊场景兼容或长期真机性能。API 26 和 API 29 的资源分支已实际执行，但 API 28、OEM 与物理设备仍未验收。

版本专用接口集中隔离，窗口资源有单一所有者，复用已有回归检查而未增加另一套激活协议。
