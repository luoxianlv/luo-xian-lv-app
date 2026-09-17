<div align="center">

# 落弦律 · Android 客户端

**口琴谱社区 [luoxianlv.com](https://luoxianlv.com) 官方客户端 —— 找到谱子，剩下的交给它。**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![minSdk](https://img.shields.io/badge/minSdk-26-3DDC84?logo=android&logoColor=white)](#)
[![targetSdk](https://img.shields.io/badge/targetSdk-35-3DDC84?logo=android&logoColor=white)](#)
[![CI](https://github.com/luoxianlv/luo-xian-lv-app/actions/workflows/ci.yml/badge.svg)](https://github.com/luoxianlv/luo-xian-lv-app/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/luoxianlv/luo-xian-lv-app?display_name=tag&logo=github)](https://github.com/luoxianlv/luo-xian-lv-app/releases)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
[![Downloads](https://img.shields.io/github/downloads/luoxianlv/luo-xian-lv-app/total)](https://github.com/luoxianlv/luo-xian-lv-app/releases)

</div>

---

## 简介

落弦律是一个口琴谱社区。这个仓库是它的 Android 客户端：浏览、搜索、收藏口琴谱，
并通过系统**无障碍服务**模拟手势，在游戏内自动演奏口琴。

客户端本身**不包含任何 MIDI 编译/音频合成核心**：谱面编译由 luoxianlv.com 服务端统一完成，
客户端只负责拉取演奏事件流并驱动手势，APK 零原生依赖（纯 Kotlin，全架构通用）。

## 功能

| | |
| --- | --- |
| 🎼 **谱库** | 精选 / 最新 / 热门榜单，关键词搜索，创作者主页，谱子详情与播放统计 |
| ⭐ **收藏同步** | 登录后收藏跨设备同步，本地离线缓存随时可弹 |
| 🪗 **自动演奏** | 无障碍服务实时截屏识别琴键布局，`GestureDescription` 毫秒级手势序列，悬浮窗控制播放/暂停/切歌 |
| 📥 **谱面导入** | 本地 MIDI 文件一键导入收藏（服务端编译），内置示例曲开箱即弹 |
| 🔐 **账号** | QQ 邮箱验证码注册，鼠鼠 OAuth 一键登录，JWT 会话 |
| 🔄 **应用内更新** | 内置更新通道，新版本自动提醒 |

## 架构

```
app/src/main/java/app/luoxianlv/
├── service/    无障碍服务：截屏识别琴键位置与音高状态，按事件时间线驱动手势
├── ui/         Compose 界面层：曲库 / 导入 / 发现 / 设置 / 更新
├── core/
│   └── score/  谱面模型：简谱文本 ⇄ NoteEvent 按键事件时间线
├── data/       曲库与会话持久化（SharedPreferences）
└── update/     REST 客户端：鉴权、谱面下载、服务端编译、应用更新
```

**演奏数据流：**

```
┌───────────────┐   MIDI 文件    ┌────────────────────┐   按键事件流(JSON)   ┌───────────────┐
│  Android 客户端 │ ─────────────▶ │  luoxianlv.com 服务端 │ ──────────────────▶ │ 内存事件时间线  │
│ (零原生依赖)    │  可选 Token    │  POST /api/compile-midi │                    │ 无障碍手势驱动  │
└───────────────┘                └────────────────────┘                    └───────────────┘
```

设计要点：

- **核心不上客户端** —— MIDI → 按键事件的编译在服务端完成（无需登录，并发限制），APK 不携带任何原生库，算法不分发、不暴露；
- **内置曲离线可用** —— 示例曲在构建期用同一核心离线预编译进 `assets/builtin-scores/`，未登录场景零网络依赖；
- **不留敏感数据** —— 演奏事件仅存内存与本地偏好，原始 MIDI 字节不落盘。

## 技术栈

| 层面 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.0（JVM 17） |
| UI | Jetpack Compose（Material 3） |
| 演奏 | `AccessibilityService` + `GestureDescription` + `MediaProjection` 截屏识别 |
| 网络 | 零依赖 `HttpURLConnection` 封装，JWT Bearer 鉴权 |
| 持久化 | SharedPreferences |
| 构建 | Gradle 9 · AGP 8.7 · compileSdk 35 |

## 快速开始

```bash
git clone https://github.com/luoxianlv/luo-xian-lv-app.git
cd luo-xian-lv-app

# Debug 构建（默认 API 地址 https://luoxianlv.com）
./gradlew assembleDebug

# 指定 API 基地址
./gradlew assembleRelease -PupdateBaseUrl=https://your-domain.com
```

> 需要 JDK 17 与 Android SDK（compileSdk 35）。

## 测试

```bash
./gradlew testDebugUnitTest
```

## CI/CD

| 工作流 | 触发 | 行为 |
| --- | --- | --- |
| [CI](.github/workflows/ci.yml) | push / PR → `main` | 单元测试 + Debug APK 构建，产物 14 天可下载 |
| [Release](.github/workflows/release.yml) | 推送 `v*` tag / 手动 | 签名 Release APK → GitHub Release（附 SHA-256） |
| [Issue Auto Label](.github/workflows/issue-auto-label.yml) | 新 Issue | 按内容关键词自动打 `bug` / `enhancement` / `question` 等标签，维护者 Issue 打 `maintainer` |
| [Stale](.github/workflows/stale.yml) | 每日定时 | 60 天无活动的 Issue 标记 `stale`，再过 7 天自动关闭 |
| [Cleanup Cache](.github/workflows/cleanup-cache.yml) | PR 关闭 / 手动 | 清理该 PR 的 Actions 构建缓存 |

Release 流水线需要的配置：

- **Secrets**：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`
- **Variables**：`UPDATE_BASE_URL`（可选，覆盖默认 API 地址）

## 参与贡献

欢迎 Issue 与 Pull Request。提交前请确保 `./gradlew testDebugUnitTest` 通过。

## 💖 感谢贡献者

感谢每一位为这个项目添砖加瓦的开发者 🎉

<a href="https://github.com/luoxianlv/luo-xian-lv-app/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=luoxianlv/luo-xian-lv-app&anon=1" alt="Contributors" />
</a>

有你们的贡献，项目才能越来越好~ ❤️

## 📄 开源许可证

本项目基于 **[GNU AGPL-3.0](LICENSE)** 许可证开源：

- ✅ 你可以自由地使用、研究、修改本软件；
- ✅ 你可以分发原版或修改版，包括用于商业环境；
- ⚠️ 分发或通过网络提供修改版服务时，**必须以 AGPL-3.0 公开你的完整对应源码**（包括为运行它所做的全部修改）；
- ⚠️ 本软件按"现状"提供，**不附带任何担保**。

简单来说：**可以随便用，但改了再发（或上线服务）就必须开源**。这既保护使用者自由，也防止有人拿它闭源牟利。

## ⚖️ 免责声明与使用条款

> **下载、构建、安装或使用本软件，即表示你已阅读、理解并不可撤销地同意接受本声明全部条款。** 如不同意，请立即停止并删除本软件。

**一、性质与目的**

1. 本项目是**独立的、非商业的、开源的技术研究作品**，仅供个人学习编程技术与交流使用，**严禁任何形式的商业用途**（包括但不限于售卖、二次打包分发、植入广告、引流变现、代练接单）。
2. 本项目**不是外挂、作弊器或游戏修改工具**：不注入目标应用进程，不读写目标应用的内存与数据文件，不拦截、不篡改任何网络通信，不绕过目标应用的任何客户端保护、反作弊或完整性校验机制，不获取任何游戏账号凭据。软件全部能力基于 Android 系统**公开的无障碍服务（AccessibilityService）API**，仅模拟用户触摸、滑动手势，其行为在法律与技术层面等价于用户本人操作输入设备。
3. 本项目与任何游戏开发商、发行商、平台方（**包括但不限于腾讯及其关联公司、网易、米哈游等**）均无任何关联，未获其授权、认可、赞助或背书。文中提及的任何游戏、应用、公司名称、商标、标识均为其各自所有者的财产，仅用于描述性说明（nominative fair use），不暗示任何从属或合作关系。

**二、知识产权**

4. 本项目源代码以开源形式发布，**代码层面的使用须遵守仓库附带的开源许可证**；除许可证明确授予的权利外，作者保留一切权利。
5. 客户端展示、播放的**谱面（MIDI/简谱等）内容版权归原作者或上游内容平台所有**，本项目仅提供个人学习交流之展示与演奏渠道；如你是权利人且认为内容被不当使用，请通过 Issue 联系，将在核实后第一时间移除相关内容。
6. 本仓库**不包含、不分发任何第三方游戏客户端、美术资源、音频素材、解包数据或专有协议实现**；仓库内全部示例内容为本项目自有或已获授权的内容。

**三、使用限制与禁止行为**

7. 不得将本软件用于：批量代练、挂机牟利、自动化刷取游戏资源并交易、破坏游戏公平性与经济系统、骚扰其他玩家、规避平台区域限制，或任何违反**所在司法辖区法律法规**、目标应用服务条款的行为。
8. 不得对本软件进行逆向工程以提取服务端鉴权信息，不得尝试绕过 luoxianlv.com 服务端的登录鉴权、访问频率限制或内容加密措施；此类行为构成违约，并可能构成违法。
9. 不得将本软件或其衍生作品上传至任何应用分发市场（包括但不限于各品牌应用商店、第三方市场），不得宣称本软件为"官方出品"或与任何公司存在合作。
10. 自动化交互会产生额外的设备电量消耗与网络流量，并可能触发目标应用的自动化检测机制（如行为验证码、风控限制）。**由此导致的账号警告、限制、封禁、数据丢失或其他任何后果，由使用者自行承担，作者概不负责。**

**四、责任限制**

11. 本软件按"**现状（AS IS）**"提供，不附带任何形式的明示或默示担保，包括但不限于：适销性担保、特定用途适用性担保、不侵权担保。作者不保证软件无缺陷、不间断、持续可用或持续维护，亦保留随时停止更新、删除仓库、变更功能的权利，且不承担因此产生的任何责任。
12. 在适用法律允许的最大范围内，**作者及贡献者对因使用或无法使用本软件而产生的任何直接、间接、偶然、特殊、惩罚性或后果性损害（包括账号损失、数据损失、利润损失、商誉损失）均不承担责任**，即使已事先告知该等损害的可能性。
13. 部分司法辖区不允许排除默示担保或限制责任，上述限制在你所在辖区不适用时，以法律允许的最低限度为准；其余条款继续有效。
14. 若你代表任何公司、组织或权利方，认为本软件或仓库内容侵犯其合法权益，请通过 GitHub Issue 提交权利证明与具体诉求，作者将在核实后及时处理（删除、修改或添加声明）。

**五、其他**

15. 本声明可能不时更新，更新后的版本发布于本仓库即生效；继续使用视为接受更新后的条款。

---

<div align="center">
<sub>© 2026 落弦律 · 仅供学习交流 · 与任何游戏厂商无关</sub>
</div>
