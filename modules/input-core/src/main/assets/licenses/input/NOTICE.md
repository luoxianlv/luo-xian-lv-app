# 输入模式的开源来源

无线调试的服务发现与配对通知移植自 [Shizuku Manager v13.6.0](https://github.com/RikkaApps/Shizuku/tree/v13.6.0)，固定提交 `2650830c5b099ae0dd34fedf614d4f592ca05d65`，采用 Apache-2.0。`AdbMdns.kt` 忠实移植为纯 Java，保留配对／连接两类服务、本机网卡地址筛选与回环端口检查；配对通知按 `AdbPairingService.kt` 的搜索、输入、处理中及结果四态，以及 PendingIntent 保存配对端口的流程移植。操作引导依据 `AdbPairingTutorialActivity.kt`，配对通知文案采用原中文资源并适配落弦律名称。

移植文件标明原始路径和提交。必要适配包括：包名与品牌资源、Java 回调／执行器替代 Kotlin 依赖、Java 网卡检查异常及发现停止收尾、配对调用接入既有 LibADB、前台服务先显示通知再搜索，以及有限时的后台取消清理。发现日志改为中文，仅记录阶段与失败类型，不输出服务名、设备序列号或完整地址。未把 Shizuku 品牌图标或整个管理器应用作为落弦律组件分发。

Shizuku 模式实际使用 Shizuku-API 的 API、Provider 及其 AIDL／Shared 依赖，它们采用 MIT；与管理器的 Apache-2.0 区分。无需安装 Shizuku 的无线调试模式使用下面列出的 LibADB 与 SPAKE2。

| 依赖 | 版本／提交 | 许可证 |
| --- | --- | --- |
| [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) | API／Provider／AIDL／Shared 13.1.5；许可核对提交 `510fc988c02c3475d8c25db170f96792f105bdf8` | MIT，Copyright (c) 2021 RikkaW |
| [LibADB Android](https://github.com/MuntashirAkon/libadb-android) | 3.1.1 / `c849886ebc6d48e7b46d967e78a6bb65c90c3b74` | 双许可 GPL-3.0-or-later OR Apache-2.0；本项目选择 Apache-2.0，另保留文件的 BSD-3-Clause／MIT 要求 |
| [SPAKE2-Java 的 Android JNI](https://github.com/MuntashirAkon/spake2-java) | 2.2.1 / `7615ddd680b990e14513ebb66eac4cb0dbf82464` | LGPL-3.0 |
| [SPAKE2-C](https://github.com/MuntashirAkon/spake2-c) | `0d15933e5ba3e662cb01245a7ac0dc9fca3eac31` | 仓库 LGPL-3.0；文件另含 ISC、MIT、Apache-2.0，以及 SHA512 的 LGPL-2.1-or-later |
| [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) | 6.1 | Apache-2.0，由 Maven Central 获取 |

`java/` 保留上游版权与原始注释。`sources.json` 的 `upstreamSha256` 记录取得的上游原文件摘要；`sha256` 按仓库 LF 换行计算，避免 Windows 换行转换影响校验。

本地调整：

- `AdbConnection`：本机 Socket 五秒连接时限、连接失败释放 Socket、关闭线程等待一秒；OPEN 使用条件等待与十秒时限，超时或取消移除半开流；连接中断先标记状态再唤醒流；单调时钟计时并保留连接异常原因，不输出连接堆栈。
- `AdbStream`：提前收到 OKAY 不丢通知；远端关闭后先读完已收数据再返回 EOF，本地主动关闭及线程中断保持取消语义；已关闭的对端不再等待写入确认。
- `PairingConnectionCtx`：握手及读取十秒时限，关闭可取消未完成握手，不输出配对材料。
- `PairingAuthCtx`：相同的 HKDF-SHA256、AES-128-GCM 改用系统 JCA，移除 Bouncy Castle 依赖。保留上游 SPAKE2 协议参数；RFC 5869、NIST GCM 与双端协议测试验证兼容性。
- `AndroidPubkey`：Base64 改用 Java 标准库。
- SPAKE2 JNI：生成、处理、销毁串行；重复销毁安全；失败时由唯一清理路径释放，防止重复释放。
- SPAKE2-C：临时密钥随机源改为操作系统 `/dev/urandom`，读取失败拒绝配对；释放时抹除上下文密钥。曲线计算与协议参数保持上游实现。

TLS 使用设备系统实现，只开放 Conscrypt 导出方法所在类的隐藏 API 豁免。SPAKE2 从固定源码构建四种 ABI；所有原生库使用 16 KiB 段对齐。

## 原始版权与完整许可

- `Shizuku-API-MIT.txt`：13.1.5 上游 MIT 原文，包含 RikkaW 的版权声明。
- `Shizuku-Manager-Apache-2.0.txt`：移植版本 v13.6.0 的完整 Apache-2.0 原文，保留来源及修改说明。
- `LibADB-COPYING.txt`：LibADB 的双许可选择原文；完整条款另见 `Apache-2.0.txt`、`GPL-3.0-or-later.txt`、`BSD-3-Clause.txt`、`MIT.txt`。
- LibADB 的 BSD 来源声明：`AdbConnection.java`、`AdbProtocol.java`、`AdbStream.java` 为 `Copyright 2013 Cameron Gutman`；`AdbAuthenticationFailedException.java` 为 `Copyright 2020 Sam Palmer`。`PRNGFixes.java` 的 MIT 来源为 `Copyright 2013 Google Inc.`。`android/AdbMdns.java` 保留 `Copyright 2020 南宫雪珊` 与 `Copyright 2022 Muntashir Al-Islam`。这些原始声明仍保留在源码中。
- `SPAKE2-LGPL-3.0.txt` 与 `GPL-3.0-or-later.txt`：JNI／C 库许可全文。上游 JNI／Java 头保留 `Copyright (C) 2021 Muntashir Al-Islam`。
- `SPAKE2-ISC-notice.txt`、`Fiat-Crypto-MIT-notice.txt`、`SPAKE2-SHA512-notice.txt`：逐字保存对应文件的原始版权与许可头；SHA512 另附完整 `LGPL-2.1.txt`。`spake2.h` 保留 `Copyright 2022 Muntashir Al-Islam` 的 Apache-2.0 头，`ed25519.h` 保留 ref10 的公有领域来源说明。

本项目的总许可证不替代这些第三方条款。SPAKE2 的源码、本地修改和构建脚本随仓库提供，独立构建为 `libspake2.so`；再分发时一并保留对应源码、修改记录及 LGPL 所要求的重建／重新链接能力。
