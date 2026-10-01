# 原生页面装饰器 default 回调转发修复

完整 APP 切页实际回归中，资源 B 通过首帧检查后，Pager 的 Lazy 子组合调用 `rememberPageLauncher`，在 `ComposePage.registerResult` 访问未初始化的 `host` 并闪退。资源首帧检查成功没有覆盖后续页面的系统结果注册路径。

宿主 `NativeHostActivity` 与 `PageSwapHost` 均在 `create` 前调用 `attachHost`，`ComposePage` 内的 `LocalNativePage` 绑定顺序正确。缺口在 `OfficialCheckedPage : NativePage by delegate`：本项目 Kotlin 2.4.20 编译器只自动转发 Java 抽象方法，未为接口 default 方法生成委托桥。宿主实际调用了 `NativePage.attachHost` 的默认空实现，实际业务页面从未收到 host。首页当时没有注册 launcher，因此能 ready；切换到使用 launcher 的子页面才触发异常。

修复限定两个装饰器，未改 `ComposePage`、`PageLauncher` 或接口默认语义：

- `OfficialCheckedPage` 显式转发 11 个 default：`attachHost/newIntent/result/back/windowTouch/hostWarning/configurationChanged/finishing/retain/restoreRetained/canReplace`。
- `OfficialCheckedPlayback` 补齐 7 个原来遗漏的 default：`supportsHandover/snapshot/restore/activate/deactivate/revision/released`。既有 `event/prepare/prepareRecovery` 和渲染就绪门禁保留。
- 平台可空载荷按原 Java 方法传递，包含取消结果的 `Intent?`、`Throwable?`、`Retained?` 和 `Bundle?`；不对可空 snapshot 新增 Kotlin 返回值非空断言。

这也修复了装饰器吞掉返回键/系统结果、把 `canReplace` 错误还原为 true、把 `retain` 错误还原为 null、在 `restoreRetained` 默认实现中提前关闭状态，以及丢失播放交接能力和后台任务释放状态的问题。没有采用跳过未绑定 host 的注册方式。

独立编译验证使用 Kotlin 2.4.20、JDK 21、项目实际 `hot-contract` 编译类、Android SDK 37 和 JUnit 4.13.2，不运行 Gradle或设备：

1. Java default 最小计数负例：普通 `by` 页面 11 个 default、播放 7 个 default 的业务计数全部为 0；页面 retain=null、canReplace=true，restoreRetained 意外关闭状态；播放 snapshot/restore/activate/deactivate 抛出接口默认异常。显式转发对照组所有 hook 计数为 1，返回值正确。
2. 直接编译两个正式装饰器与 `OfficialCheckedDelegationTest`，JUnit **3 项通过**。反射枚举实际 Java 接口的所有 default，检查同 descriptor override，能发现将来新增 hook 的遗漏；计数测试检查同一 HostActions/Retained、返回值及 nullable 载荷。
3. `javap -p -c -s` 确认新增 18 个方法都调用实际 delegate 的 `invokeinterface`；nullable snapshot 是直接 `areturn`。编译用 renderer/findActivity 替身若被调用即抛出异常，3 项测试均未进入它们。

证据在 `.local/page-delegation-probe/counter-results.txt`、`javap.txt` 与 `actual/{junit-results.txt,javap.txt,source-evidence.json,compile-output.txt}`。独立 JVM 验证覆盖委托协议，不能替代实际 Compose/Pager、旋转/后台恢复、系统结果与播放交接设备回归。根代理需重新构建并签名修复后的业务 APK，再统一跑完整 APP 回归；旧 B 业务代码存在该问题，不能继续作为修复后的安全回退证据。
