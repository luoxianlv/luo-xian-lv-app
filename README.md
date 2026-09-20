<div align="center">

# 落弦律 · Android 客户端

**口琴谱社区 [luoxianlv.com](https://luoxianlv.com) 官方客户端 —— 找到谱子，剩下的交给它。**

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![minSdk](https://img.shields.io/badge/minSdk-26-3DDC84?logo=android&logoColor=white)](#)
[![CI](https://github.com/luoxianlv/luo-xian-lv-app/actions/workflows/ci.yml/badge.svg)](https://github.com/luoxianlv/luo-xian-lv-app/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/luoxianlv/luo-xian-lv-app?display_name=tag&logo=github)](https://github.com/luoxianlv/luo-xian-lv-app/releases)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
[![Downloads](https://img.shields.io/github/downloads/luoxianlv/luo-xian-lv-app/total)](https://github.com/luoxianlv/luo-xian-lv-app/releases)

</div>

---

## 简介

落弦律是一个口琴谱社区。这个仓库是它的 Android 客户端：浏览、搜索、收藏口琴谱，
并通过系统无障碍服务在游戏内自动演奏口琴。

📂 代码组织与模块职责见 **[项目结构说明](docs/project-structure.md)**。

## 功能

|                   |                                                                                   |
|-------------------|-----------------------------------------------------------------------------------|
| 🎼 **谱库**       | 浏览、搜索、收藏和管理口琴谱                                                 |
| ⭐ **收藏同步**   | 登录后同步收藏，本地曲库可离线使用                                           |
| 🪗 **自动演奏**   | 识别琴键布局，悬浮窗控制播放、暂停和切歌                                     |
| 📥 **谱面导入**   | 导入 MIDI 文件，内置示例曲可直接使用                                        |
| 🔐 **账号**       | 邮箱登录和第三方账号登录                                                     |
| 🔄 **应用内更新** | 自动检查并提示新版本                                                         |

## 使用

1. 从 [Releases](https://github.com/luoxianlv/luo-xian-lv-app/releases) 下载并安装，支持 Android 8.0 及以上版本。
2. 阅读并同意应用内免责声明，按提示开启无障碍服务。
3. 从发现页下载谱子，或导入 `.mid` / `.midi` 文件（不超过 4 MB）。浏览、下载和导入需要联网。
4. 在游戏中打开口琴界面，通过悬浮窗选择曲目并开始演奏。

如遇悬浮窗消失，可在「设置 → 后台运行保护」检查通知、电池优化与自启动设置。

## 快速开始

```bash
git clone https://github.com/luoxianlv/luo-xian-lv-app.git
cd luo-xian-lv-app

# Debug 构建
./gradlew assembleDebug
```

需要 JDK 17 与 Android SDK 37。Windows 使用 `gradlew.bat`。客户端使用 Kotlin 和 Jetpack Compose。

## 测试

```bash
./gradlew testDebugUnitTest
```

## 参与贡献

欢迎提交 Issue 与 Pull Request。合并前请确保 CI 检查全部通过。

## 💖 感谢贡献者

感谢每一位为这个项目添砖加瓦的开发者 🎉

<a href="https://github.com/luoxianlv/luo-xian-lv-app/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=luoxianlv/luo-xian-lv-app&anon=1" alt="Contributors" />
</a>

有你们的贡献，项目才能越来越好~ ❤️

## 📄 开源许可证

本项目基于 **[GNU AGPL-3.0](LICENSE)** 许可证开源：

- ✅ 你可以自由地使用、研究、修改本软件；
- ✅ 你可以依照 AGPL-3.0 复制、修改和再分发代码；
- ⚠️ 分发或通过网络提供修改版服务时，**必须以 AGPL-3.0 公开你的完整对应源码**（包括为运行它所做的全部修改）；
- ⚠️ 本软件按"现状"提供，**不附带任何担保**。

代码复制、修改和再分发的具体权利与义务以 AGPL-3.0 为准；软件服务和品牌使用仍受下方条款约束。

## ⚖️ 免责声明与使用条款

> 下载、构建、安装或使用本软件，即表示你已阅读并同意接受本声明。在法律允许的范围内，本声明与软件使用同时生效；如不同意，请停止使用并删除本软件。

**一、性质与目的**

1. 本项目是独立的开源技术研究作品，仅供个人学习和交流使用。未经作者书面授权，不得将本软件用于商业运营、收费服务、广告引流、代练接单、批量挂机或其他营利活动。
2. 本项目不是外挂、作弊器或游戏修改工具：不注入目标应用进程，不读写目标应用的内存或数据文件，不拦截或篡改网络通信，不绕过目标应用的客户端保护、反作弊或完整性校验机制，也不获取游戏账号凭据。软件使用 Android 公开的无障碍服务 API 模拟用户手势。
3. 本项目与任何游戏开发商、发行商或平台方无关联，未获其授权、认可、赞助或背书。文中提及的名称和商标归其各自权利人所有，仅用于描述性说明。

**二、知识产权**

1. 本项目源代码以 AGPL-3.0 许可证开源。代码的复制、修改和再分发以该许可证为准；本声明中的使用限制不限制 AGPL-3.0 已明确授予的代码权利。
2. 客户端展示和播放的谱面内容版权归原作者或上游内容平台所有。本项目仅提供个人学习交流用途的展示和演奏渠道；如你是权利人并认为内容被不当使用，请通过 Issue 提交权利证明，作者将在核实后处理。
3. 本仓库不包含、不分发任何第三方游戏客户端、美术资源、音频素材、解包数据或专有协议实现；仓库内示例内容为本项目自有或已获授权的内容。

**三、使用限制与禁止行为**

1. 不得将本软件用于批量代练、挂机牟利、自动化刷取游戏资源并交易、破坏游戏公平性与经济系统、骚扰其他玩家，或任何违反所在司法辖区法律法规或目标应用服务条款的行为。
2. 不得以提取服务端凭证、绕过访问频率限制、绕过内容保护或获取未授权数据为目的，对线上服务进行攻击、滥用或逆向分析。
3. 未经授权，不得将本软件包装成官方产品、冒用作者或相关平台名义，或使用本项目的品牌标识进行商业推广。
4. 自动化交互可能增加设备电量和网络流量消耗，也可能触发目标应用的自动化检测或风控。用户应自行遵守目标应用规则，并对自身使用行为负责。

**四、责任限制**

1. 本软件按“现状”提供。作者不保证软件始终无缺陷、不中断、持续可用或持续维护，也不保证其适用于特定设备、游戏或使用场景。
2. 在法律允许的最大范围内，作者及贡献者不对因使用或无法使用本软件产生的间接、附带、特殊或后果性损失承担责任。对于法律不得排除或限制的责任，本条不予排除或限制。
3. 因设备、系统版本、厂商后台策略、网络环境、目标应用更新或第三方服务变化造成的问题，可能超出作者控制范围。用户应在使用前自行评估风险并保留必要的数据备份。
4. 若你代表任何公司、组织或权利方，认为本软件或仓库内容侵犯其合法权益，请通过 GitHub Issue 提交权利证明与具体诉求，作者将在核实后及时处理。

**五、其他**

 1. 本声明可能随软件版本更新。继续使用更新后的软件，即表示在法律允许的范围内接受更新后的条款；如不同意，应停止使用并删除本软件。

---

<div align="center">
<sub>© 2026 落弦律 · 仅供学习交流 · 与任何游戏厂商无关</sub>
</div>
