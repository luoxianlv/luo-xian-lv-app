# update-core

普通 APK 更新与热更只共用字节下载、SHA-256 校验及隔离 FD 合并。普通事务位于宿主内部 `files/apk-updates`，由 `transaction.lock` 保护；业务 SDK 只包含 SharedUpdate 桥接。

`ApkUpdateExecutor.prepare` 必须从后台线程调用。签名 verifier 由宿主注入，验证普通更新专用 `LXUPDATE-MANIFEST-V1` 信任域；认证失败不会回退。取消会贯穿下载、校验、合并；宿主保留任务租约至 prepare 真正返回。恢复重新认证原始签名封装、核对实际 PackageManager 安装基线、校验已有完整结果；未完成合并从零开始。

引擎仅编译 HDiffPatch v5.1.3 解码器和 Zstd v1.5.7 解码器；来源提交、下载包 SHA-256 及逐文件摘要在 `src/main/cpp/vendor/sources.lock.json`。开源许可证随源码保留。四 ABI、API 26、16 KiB ELF 页对齐；工作服务 `:delta_worker` 为非导出的 isolatedProcess，不拥有应用权限，只接受只读旧文件/补丁 FD 和独立输出 FD。最大旧窗口 2 MiB、步骤 256 KiB、Zstd 窗口 8 MiB，数据及工作内存由统一分配器限制在 32 MiB，180 秒后 watchdog 只终止工作进程。官方 CLI 在压缩不划算时可输出 W26 中的原始数据段，该合法形式仍由同一算法名和签名摘要绑定。

模块验证：`./gradlew :update-core:testDebugUnitTest :update-core:assembleDebug :update-core:assembleDebugAndroidTest`。设备测试运行 `adb shell am instrument -w app.luoxianlv.update.test/app.luoxianlv.update.UpdateInstrumentation`（先安装模块 test APK，具体 target package 以生成的 manifest 为准）。真实系统安装、曲谱/设置/壁纸/无障碍保留需在正式签名测试 APK 上另行验证。

Linux 本机解码对照：`cmake -S src/main/cpp -B /tmp/lxupdate-native-test && cmake --build /tmp/lxupdate-native-test`，随后运行 `python3 src/test/native/check_decoder.py /tmp/lxupdate-native-test/lxupdate_decode`。fixture 补丁由固定的正式 HDiffPatch CLI 参数 `-WD-256k -w-2m-256k -c-zstd-20-23 -C-no -s` 生成；两个精确对象以 gzip 保存以控制仓库体积。合成 2,250,015 字节目标，字节差分 120 字节，仅为功能夹具，不代表真实 APK 的节省比例。

本地验证记录（2026-10-04）：JVM 行为测试 14 项（Windows 符号链接权限不足时 1 项跳过，WSL 补测全部通过）；真实 decoder 拒绝全部 120 个截断边界及尾随数据、保留旧对象；官方不可压缩 W26 fixture 也重建一致。四 ABI AAR 和 instrument APK 编译通过，`llvm-readelf` 全部 LOAD 对齐为 `0x4000`。Debug 去符号原生库分别为 arm64-v8a 242,408、armeabi-v7a 149,840、x86 385,148、x86_64 388,184 字节。API 26／29／36 隔离 FD、取消和工作进程崩溃恢复通过；API 26／36 追加输出路径及硬链接保护通过。测试仪器的 SIGKILL、硬链接夹具和 VmHWM 测量需可用 root 的测试镜像；正常更新功能不需要 root。

设备 APK 升级、数据保持、功能夹具 VmHWM 和首包体积边界见 [本地验收记录](../docs/verification/2026-10-04-incremental-local.md)。功能夹具内存测量不能代替正式 APK 的设备峰值；完整 release 首包增量仍需候选实测。
