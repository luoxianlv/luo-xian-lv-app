# AGENTS.md

落弦律 Android 客户端的 AI 协作约定。通用贡献规范见 [CONTRIBUTING.md](CONTRIBUTING.md)，模块与目录见 [docs/project-structure.md](docs/project-structure.md)。

## 技术要求

- 逻辑代码优先使用 **Kotlin**，界面优先使用 **Jetpack Compose**（Material 3）。不新增 XML 布局或 View 体系页面。
- 业务代码放在 `app/src/main/java/app/luoxianlv/` 对应功能目录；多个功能实际共用的控件才放 `shared/`，主题与容器基础在 `modules/business-ui/`。
- 例外：`modules/app-host/`、`modules/hot-core/` 是热更架构中的**纯 Java 宿主**，不能引入 Kotlin 或 Compose（宿主不带 Kotlin 运行时，见 [docs/hot-update.md](docs/hot-update.md)）。这里的界面只能用原生 View，并沿用所在文件的 Java 风格；`app/` 根目录、`service/`、`ui/practice/` 下的 Java 系统桥同理。
- 使用依赖前先确认已在 `gradle/libs.versions.toml` 中声明；系统组件类名、`AppBusinessFactory` 和 `hot-contract` 接口有跨版本兼容要求，改动前检查宿主与业务的调用关系。

## 设计要求

- 多用卡片组织内容：一组相关信息放进一张圆角卡片，卡与卡之间用留白分隔，露出页面渐变底。
- 复用现有组件和常量，不要自行定义一套：`SettingsCard`、`GroupCardCornerRadius`（20dp）、`containerElevation()`、`ActionPill`、`PageTitle`，颜色取 `MaterialTheme.colorScheme` 与 `LocalBackdropPalette`。
- 卡片使用主题 `surface`（已是半透明），不要另加描边或阴影；直接压在渐变底上的文字用 `OnBackdropContent`。
- 尽量不用无用的文本控件：不加只重复标题含义的说明文字、装饰性提示行或冗余状态文字；能用图标、标签（chip）、卡片层次表达的信息不再单独放一行 `Text`。
- 深色与浅色主题都要可读；顶级页面装在 `HorizontalPager` 中，页面内不要放横向滚动控件抢占切页手势。

## 提交要求

- 提交信息使用**英文**，并且必须遵循[约定式提交](https://www.conventionalcommits.org/)：`<type>(<scope>): <subject>`。
- 常用 type：`feat`、`fix`、`refactor`、`perf`、`style`、`test`、`docs`、`build`、`ci`、`chore`；scope 可选，用功能目录或模块名，如 `discover`、`library`、`update`、`host`。
- subject 用祈使句、小写开头、不加句号，例如 `feat(discover): add card layout for featured scores`。破坏性变更在 type 后加 `!` 或在正文写 `BREAKING CHANGE:`。
- 每个提交聚焦一件事；未经要求不要推送、强推或改写历史。

## 保密要求

- 不向仓库提交任何需保密的内容：签名密钥库与密码、热更内容签名私钥、账号令牌、API 密钥、服务器凭据、个人路径或用户数据。
- 签名使用本地 Gradle 属性（`releaseStoreFile` 等）或 CI Secrets；`local.properties` 等本地文件不入库。
- 不在代码或日志里打印密钥、令牌；提交前检查 `git diff`，日志与截图先脱敏。

## 构建与验证

```bash
bash ./gradlew :app-host:assembleDebug                      # 调试包：modules/app-host/build/outputs/apk/debug/app-host-debug.apk
bash ./gradlew :app:testDebugUnitTest :hot-core:testDebugUnitTest :buildSrc:test
python tools/format_kotlin.py            # 格式化 Kotlin；--check 只检查
```

- 修改后至少运行相关单元测试、`format_kotlin.py --check` 和 Debug 构建。
- 设备测试位于 `app/src/androidTest/` 与 `modules/app-host/src/androidTest/`；它们按 `搜索谱子`、`演练场`、`导入谱子` 等文字定位页面，改动这些文案或页面结构时同步检查。
- 调试包包名为 `app.luoxianlv.debug`；冷启动开屏只在桌面启动（`-a android.intent.action.MAIN -c android.intent.category.LAUNCHER`）时出现，`am start -n` 直接启动不显示。
