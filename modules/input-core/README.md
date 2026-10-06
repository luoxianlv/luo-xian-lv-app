# 输入助手

本模块在已授权的输入服务中将真实手指和自动按键合成同一条多点触摸流。普通无障碍模式不使用内核，也不要求 Root。

设置入口为「设置 → 演奏 → 输入模式」。默认无障碍；Shizuku 需启动并授权，兼容官方的 ADB 与 root 身份，不要求用户取得 Root。内置无线调试需 Android 11 以上，配对码可从通知填写。悬浮窗使用系统悬浮窗权限；自动截图识别需要无障碍服务，固定按键位置和演练场不依赖截图。

## 连接与宿主

`InputController` 提供只读状态快照，所有设备、网络及 Binder 操作在后台执行。页面恢复、Shizuku 授权变化和服务断开会刷新状态；授权、连接及触屏能力分别显示。

业务通过 `SharedInput.Session` 持有输入租约。音符到期保留手指，暂停、切换模式及业务退出则归还触屏；前一个业务的关闭操作不能取消新业务。宿主确认释放后才允许重新接管。

`WirelessAdbBackend` 自动发现本机配对与连接服务，优先回环地址，必要时尝试经本机网卡核对的地址。用户发起配对时先显示通知，并由最长两分钟的前台服务保持发现；不要求填写端口。无线身份保存在内部 `noBackupFilesDir`；助手核对宿主安装包与 UID，通过受 DUMP 权限保护的 Provider 和一次性令牌交付 Binder。宿主撤销租约或退出时，助手清理并结束进程。第三方来源与本地改动见 [依赖说明](third_party/NOTICE.md)。


无线调试完成一次配对后保留身份，APP 启动即可自动连接。shell 输入进程建立独立会话，合法 Binder 交付后关闭 ADB 启动通道；关闭 Wi-Fi 或无线调试不会主动终止仍存活的演奏服务。进程失效时有限重试；手动断开、退出模式或宿主死亡仍归还触屏并结束服务。手机重启或宿主被系统结束后，需系统无线调试可用才能重新启动，通常无需重新配对。

## 内核调用

`TouchEngine.probe()` 只读取设备能力，`prepare()` 建立会话但不抓取触屏。预检要求唯一的物理触屏具有 `INPUT_PROP_DIRECT`、Type B 多点槽位与有效坐标轴。合流输出采用 scrcpy 的虚拟设备 0、触屏来源及主屏幕注入方式，不依赖厂商设备名称或 `dumpsys input` 文本格式。屏幕方向和尺寸变化仍需重新准备，物理设备断开立即释放。`activate()` 要求所有手指离屏，再执行 `EVIOCGRAB`。

首次演奏时手指尚未抬起，助手每 25 毫秒重试，最多等待 500 毫秒；取消、暂停或释放会话立即终止等待。超时不会把设备标为不支持，抬手后仍可再次播放。状态中的 `waitingForFingers` 与 `activationWaitMs` 用于起播计时；权限、设备和注入错误不属于可重试的抬手状态。

支持 `BTN_TOUCH` 的设备以接触键区分按下与悬停：读取槽位前后都确认键已释放，才允许首次接管；没有该键时仍要求所有 tracking 槽释放。非接触槽不参与物理按键输出，原始 tracking 与坐标保留，因此同一 tracking ID 从悬停转按下也能继续合流。接触键或槽位读取失败、快照不完整均拒绝接管。诊断记录槽数、接触键状态、读取错误及判定，不输出坐标或设备路径。

合流槽位与 Android 指针编号分别管理。输出使用最低空闲编号，同一触点保持编号至抬起；真实手指跨音符间隙保留原来的编号及 `downTime`。自动按键直接使用识别所得像素坐标，不再按显示尺寸二次缩放。

`Listener.frame()` 在原生工作线程同步调用，交给 `MergedTouchDispatcher.frame()`，返回系统注入的实际接受结果。音符 token 随 `noteFinished()` 原样返回；被新音符替换、取消或失败的音符也会收到结果。调度器必须按 token 忽略过期回调。

系统接受注入仅证明事件进入输入管线，不代表目标游戏已响应。

内存模型使用 32 个指针编号，自动触点最多 10 个。Android 单个 `MotionEvent` 最多 16 个同时触点；真实触点增加超过容量时先结束自动音符，真实触点保持。自动音符到期只抬起自动触点，读线程与真实手指继续运行。

激活快照在消费首个音符或物理帧前初始化，避免工作循环中途激活后，下一轮重置抹掉已经开始的触点。

`cancel()` 仅取消自动音符。`yield()` 先抬起自动按键，等待真实手指松开最多 500 毫秒，然后归还触屏；`close()` 立即释放抓取与设备句柄，并有界等待线程结束。心跳超时 4.5 秒、事件丢失、输入设备断开、注入失败或异常坐标均停止合流。独立看门狗不会依赖正在注入的线程，也会处理暂停指令被注入阻塞的情况。

无线 ADB 和 Shizuku 使用 `TouchEngine.initialize(nativeLibraryDir)` 加载宿主原生库；路径由安装信息独立核对，不能加载调用方指定的任意动态库，也不依赖服务类加载器预先配置 JNI 搜索路径。状态保留失败阶段、异常类型及助手／宿主 UID，日志不输出配对材料。

`prepare()` 的 Bundle 提供 `supported`、`message`、`path`、`name`、`descriptor`、`deviceId`、`physicalSlots`、`maxAutomaticPointers`、`maxPointers`、坐标轴范围与屏幕快照。系统不允许 shell 读取设备时返回不支持，不能通过修改权限或 SELinux 绕过。

## 校验

原生模型测试不依赖 Android，覆盖真实长按与自动和弦、自动抬起后继续移动、槽位复用、四方向映射、异常坐标和触点容量。Android 构建支持四种 ABI，原生段按 16 KiB 对齐。

```sh
cmake -S src/main/cpp -B build/model-test
cmake --build build/model-test
ctest --test-dir build/model-test --output-on-failure
```

接入方式对照 [Shizuku 官方 Demo](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/demo/src/main/java/rikka/shizuku/demo/DemoActivity.java)与 [Hail](https://github.com/aistra0528/Hail/blob/main/app/src/main/kotlin/com/aistra/hail/utils/HShizuku.kt)，触摸事件参数参考 [scrcpy Controller](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/control/Controller.java)。触点合流独立编写，未引入这些项目的业务代码或二进制；无线配对移植文件及许可见前述依赖说明。

内核依据：Linux [多点触摸协议](https://docs.kernel.org/input/multi-touch-protocol.html)、[输入事件规范](https://docs.kernel.org/input/event-codes.html)、AOSP [输入注入接口](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/input/InputManagerGlobal.java)及 [Android 16 指针容量](https://android.googlesource.com/platform/frameworks/native/+/android-16.0.0_r1/include/input/Input.h)。
