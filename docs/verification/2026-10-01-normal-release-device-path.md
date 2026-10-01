# 正常优化 Release 原字节的本机设备验收路径

日期：2026-10-01。原始正常 Host APK：`fce65730a122598dd37ed20bdc13b38a5bc75d2f6d6f2e6b88880e955ae0b918`，构建报告来源 `620ad35957bbb4556237f3ba7ec1f30fa96170f2`。原始 Runtime/Business 分别 `0ab33cd3d66c71ca0dbed605a7892106a7cf36e130e30b2a737067f2cad7ec1f` / `4444d07408ea781fc4f0f1b9e05a3368ff273482a05a4f3c66782fe131be556a`。该组合已有独立 Java/Go 严格 SDK→DEX 门禁，本路径不使用历史 `app.luoxianlv.releaseprobe` 代替正常组件。

## 设计与等价边界

保持正常包名 `app.luoxianlv` 与 Host `debuggable=false`、`testOnly=false`。独立测试 APK 为 `app.luoxianlv.normalrelease.test`，组件 `app.luoxianlv.tools.NativeNormalReleaseInstrumentation`，不会覆盖 Debug 测试 APK。

外部工具只给原 unsigned Host 的副本与独立仪器签一个全新临时 Android Debug 测试证书；不提供用户 keystore、密码、alias 参数。新 key 在系统临时目录生成，固定公共 fake 密码 `android`，签完即清理；不会读取任何既有证书私钥。只开 APK v2 签名（minSdk 26），不产生 v1 ZIP 签名条目。逐项核验完整 ZIP 条目集合、每项原始解压长度和 SHA-256，任何 manifest/DEX/resources/assets/baseline/条目改变均拒绝。

签名块、容器排列/对齐与完整 APK hash 会变化；所有原 ZIP 条目字节保持相同。因此这证明正常 R8 内容在本地同证书仪器下的行为，不能证明生产证书、正式分发环境、安装升级链或 `releaseReady`。不修改 debuggable 避免将不同 Host manifest 环境冒充正常 Release。非 debuggable 目标通过同签名 instrumentation 和安全 Bundle 返回证据，不使用 run-as。

## 准备与已完成的离线检查

新增五个工具/测试文件：准备脚本、共用 payload/policy、policy tests、独立纯 Java runner、测试 manifest。生产源码、正常 Gradle profile 和旧 probe 不变；没有新默认任务/后台服务。

- `test-native-normal-release-policy.ps1`：20 个独立检查通过，包括正确输入、固定正常包名、禁止 Debug/probe/脏来源/非 R8/资源收缩/热更配置、改变 manifest/DEX/资源/内置模块、额外条目、重复条目与越界名称。
- 三个 PowerShell 文件的 parser 检查通过。
- runner 使用本机 `javac --release 17` 和 Android 37 公共 jar 实际编译通过；SDK 36.0.0 D8 `--release --min-api 26` 与 aapt2 独立 manifest link 通过。只编译工具代码，不调用 Gradle。
- 本文作者没有签名生产证书、执行 prepare 签名、ADB、API 或设备。prepare/设备由 root 串行执行，实际状态必须由相应回执另记，不能从离线编译提前记设备 PASS。

在 APP 根目录准备当前 pinned APK：

```powershell
& .\tools\test-native-normal-release-policy.ps1
& .\tools\prepare-native-normal-release.ps1 -ExpectedHostSha256 'fce65730a122598dd37ed20bdc13b38a5bc75d2f6d6f2e6b88880e955ae0b918'
```

prepare 会重新执行既有 `verify-native-release -ExpectOptimized -CompileOnly`，然后用已安装本机 javac/jar/d8/aapt2/zipalign/apksigner 创建两个 signed 副本。它不下载工具、不运行 Gradle/ADB/网络/API。输出新 `app-host/build/native-normal-device-fixtures/<runId>`，只在 `prepared.json.state=prepared`、签名核验/输入 hash/全 ZIP payload 等价检查均完成后才可使用；残留半成品不能视为 prepared。

## root 的准确执行顺序

root 已确认当前模拟器没有正常 `app.luoxianlv` 包。执行前仍重新检查；若出现同包现有安装，立即停止，不卸载、不使用 install -r，也不破坏已有包/数据。此脚本没有自动设备操作；下面命令仅供已授权 root 顺序运行。替换第一行 `<runId>` 为 prepare 实际输出：

```powershell
$normalFixture = Join-Path (Get-Location) 'app-host/build/native-normal-device-fixtures/<runId>'
$normalPrepared = Get-Content -LiteralPath (Join-Path $normalFixture 'prepared.json') -Raw | ConvertFrom-Json
$normalAdb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
if ($normalPrepared.state -ne 'prepared' -or -not $normalPrepared.payloadEntryBytesPreserved -or $normalPrepared.releaseReady) { throw 'Missing valid prepared fixture' }
$normalInstalled = (& $normalAdb -s emulator-5554 shell pm list packages app.luoxianlv) -join "`n"
if ($normalInstalled -match '(?m)^package:app\.luoxianlv\r?$') { throw 'Existing normal package: stop without uninstall/replacement' }
& $normalAdb -s emulator-5554 install (Join-Path $normalFixture 'host.local-test-signed.apk')
if ($LASTEXITCODE -ne 0) { throw 'Normal Host install failed' }
& $normalAdb -s emulator-5554 install -t (Join-Path $normalFixture 'normal-instrumentation.local-test-signed.apk')
if ($LASTEXITCODE -ne 0) { throw 'Independent test APK install failed' }
# root 先确保模拟器全部 INTERNET 网络不可用；helper还会在实际过程中强制核验。
& $normalAdb -s emulator-5554 shell am instrument -w -r `
    -e sourceHostSha256 $normalPrepared.sourceHostSha256 `
    -e installedHostSha256 $normalPrepared.installedHostSha256 `
    -e certificateSha256 $normalPrepared.certificateSha256 `
    -e runtimeSha256 $normalPrepared.runtimeSha256 `
    -e businessSha256 $normalPrepared.businessSha256 `
    app.luoxianlv.normalrelease.test/app.luoxianlv.tools.NativeNormalReleaseInstrumentation
```

保留完整 instrumentation 输出，不因某一阶段状态提前 kill。验收必须核对 `normalReleaseReport.passed=true`、同一 prepared/source/installed/certificate/模块 hash、固定 PASS 文本和 `INSTRUMENTATION_CODE: -1`；进程/ADB 退出码或局部 UI 观测不能单独记通过。runner 结束关闭自己创建的正常首页；设备可见页面与原网络恢复由 root 负责，不能自动覆盖先前 Debug 场景。

必须使用 `-r` raw 模式，否则系统默认 pretty formatter 可能只显示 stream 而丢失 Bundle JSON/结果码。断开 Wi-Fi/data 后要等待系统网络 callback 完成，再确认全部 INTERNET 网络不可用；提交断网操作不代表异步状态已经变更。root 第一次未等待异步断开，仅得到 FAIL pretty 输出，属于失败记录，不能改记通过；第二次使用 raw 模式独立重试。

## root 的实际准备与设备结果

root 已实际运行 prepare，公开 `app-host/build/native-normal-device-fixtures/09b34793e3494f2cbee9a65a22606163/prepared.json` 状态 `prepared`。本审计只读取该公开 metadata，没有读取私钥或 `.local`：

- 原 ZIP payload 13 项全部字节保持；fingerprint `d9dd4c7b8a2470dba7f315b6e89581139e9fce5de9a82845310e63e23599598d`。
- signed Host `8e2b8017568e970527bbcbde5d590c64ad186fed9a8e3c48253e4f9c81efcd2e`；独立 signed test APK `97893fd066d6f080ac7e8117f2520e3fe8ff632d1df28ae4ca9dd34ef4295546`。
- 公开临时测试证书 SHA-256 `eb551a5d21ecce11efdb841b82cece7b22fd2cfccaad4c7705976a410ee988a7`；原始 source/bundled 模块 hash 与本文开头一致。root 确认 prepare finally 已删除该临时私钥。

根据 root 保存完整 raw instrumentation 回执并提供的结果，第二次独立设备执行 `passed=true`、`INSTRUMENTATION_CODE: -1`，PID 31745；正常 non-debuggable/non-testOnly Host、真实可见 Compose、Material 测量、三层加载器、服务 SDK 边界与壁纸组件定义通过。未同意隐私观察 10107 ms，正式 initialize 未发生，总耗时 13186 ms。本文件作者没有自行执行设备或读取 `.local` raw 日志；root 单独保存公共 JSON/设备证据。

该次通过只对应上述正常 payload 和临时签名副本；第一次离线状态未就绪的失败保留，不能被后来的通过覆盖。真实壁纸 screen、服务生命周期/手势、Release 热更健康/回退仍未验证，`releaseReady=false`。

## 实际检查与明确不成立的结论

runner 强制目标/测试包、原 Host manifest flags、两 APK 同一公开 Android Debug 证书、安装 signed Host hash、两内置模块真实字节 hash、缺省不存在 hot/config、全程离线。正常 Application/MainActivity 真实启动，观察可见 Compose；验证 Host→Runtime→Business 实际加载器父链、Host 不定义 Kotlin/Compose、Kotlin 由 Runtime 共享。实际 Material style 必须属于 0x7f，真实 MaterialButton 创建/测量通过。

隐私选择只读：当前 DisclaimerStore hash 与已同意值不相等，真实 `analytics-preinitialize` 已发生，至少 10 秒持续检查正式 initialize 的 ProcessOnce 完成状态、业务 initAt 和固定 initialize 调用诊断均未出现。没有点击同意、写同意状态或调用初始化。首次隐私页挡住业务首页/壁纸按钮时，结果明确为 `blocked-by-unconsented-home`，不能把可见免责声明算作普通业务首页全功能通过。

壁纸检查正常 Activity manifest/Host 定义，记录 `wallpaperScreenRendered=false`；不启动隐藏 Activity 绕过正常入口门禁，也不下载/导入壁纸。服务检查正常无障碍 binding permission、FGS specialUse 非导出声明、Host 服务定义、Host-owned PlaybackPort/NativePlaybackSession 及 Bundle/基础值接口。已有真实 port 时只读 query；没有连接就记录未启用，不启用系统服务，不发 command/手势。`foregroundServiceLifecycleVerified=false`、`gesturePlaybackVerified=false` 始终保留。

正常 Release 没有 test 热更配置，`hotUpdateHealth=not-verified`、`hotUpdateRollback=not-verified`、`activationCalled=false`。同生产宿主代码的 Debug 已验证健康/回退可作为独立辅助证据，但不得称此 signed 正常 Release APK 实际通过这些行为。

KISS/DRY：小工具复用既有严格 Release verifier 和一个共用 payload policy；YAGNI：无需改变 Host flags/构建，也不做新的发布/联网路径；SOLID：prepare 负责字节与证书绑定，runner 负责实际只读设备观察，root 负责设备生命周期。后续报告仅追加实际设备回执及限制，生产签名/联网/业务隐私授权不在此任务范围。

【MCP调用简报】服务：Codex 本地工具；触发：正常 Release 的本机工具准备；参数：限定公开 APK/reports、独立 Java/XML/PowerShell 工具；结果：20 policy checks、PS parser、javac/D8/aapt2 通过；状态：成功，无网络/设备/API执行。
