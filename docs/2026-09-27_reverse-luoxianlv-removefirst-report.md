# 落弦律 1.0.6 removeFirst 崩溃排查

## 结论

崩溃发生在诊断流水达到 100 条后淘汰最旧记录时。1.0.6 的 `Analytics.record()` 使用 `removeFirst()`，APK 将其编译为系统 `java.util.List.removeFirst()` 调用。该系统方法从 Android 15（API 35）才提供，因此旧系统会抛出 `NoSuchMethodError`。

当前 main 已在 `e651c61` 中改用 `removeAt(0)`。本次保留该修复，添加兼容性说明和容量边界测试。

官方兼容性说明：[Android 15 OpenJDK API changes](https://developer.android.com/about/versions/15/behavior-changes-15#openjdk-api-changes)。

## 范围

见 [scope.md](crash-removefirst/scope.md)。分析工具为本机 DexClub MCP 1.0.0-preview.5；通过 streamable HTTP 调用，未修改输入 APK。

## Evidence

### E-001 原始 APK

- source_ref：`C:/Users/XiaoMing/Downloads/luoxianlv-v1.0.6-release.apk`
- content_hash：SHA256 `f83682a53b2980e7f0b6b3e3aa8c2549a4090a3a2c0ee899044a16d53271db31`
- repro_command：PowerShell `Get-FileHash -Algorithm SHA256 C:/Users/XiaoMing/Downloads/luoxianlv-v1.0.6-release.apk`
- DexClub `find_methods` 查询调用 `java.util.List.removeFirst` 的方法，返回 total=1、hasMore=false。
- 方法 descriptor：`Ld5;->c(Ljava/lang/String;Ljava/lang/String;)V`。
- `export_method_java` 关键输出：

```java
ut1 ut1Var = c;
if (ut1Var.size() >= 100) {
    ut1Var.removeFirst();
}
ut1Var.add(new c5(str, System.currentTimeMillis(), str2));
```

复查查询需先以原始 APK 路径调用 `open_target_session`，再将返回的 sessionId 用作 session_id：

```json
{
  "matcher": {
    "invokeMethods": {
      "methods": [{
        "declaredClass": {"className": {"value": "java.util.List", "matchType": "Equals"}},
        "name": {"value": "removeFirst", "matchType": "Equals"}
      }]
    }
  }
}
```

### E-002 源码对应关系

- source_ref：`app/src/main/java/app/luoxianlv/core/Analytics.kt`，`Analytics.record()`。
- content_hash：n/a（Git 版本标识）。
- repro_command：`git show v1.0.6:app/src/main/java/app/luoxianlv/core/Analytics.kt`；`git show e651c61 -- app/src/main/java/app/luoxianlv/core/Analytics.kt`。
- 1.0.6 的函数包含相同的 100 条阈值、`removeFirst()`、当前时间和两个字符串参数；修复提交将删除操作替换为 `removeAt(0)`。

## Finding

- F-001：Android 15 才提供的 List API 导致旧系统崩溃。
- severity：n/a_re。
- evidence_ids：E-001、E-002、E-003。
- confidence：high。
- location：`Analytics.record()`；1.0.6 APK 的 `d5.c`。
- status：validated；源码修复已存在，本次验证新 Release 产物。

## Path

P-001，path_type=callflow：页面／事件记录 → `Analytics.record()` → 诊断列表已满 100 条 → `List.removeFirst()` → 旧 Android 缺少该接口方法 → 主线程崩溃。

## 验证

命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests app.luoxianlv.AnalyticsDiagnosticsTest :app:assembleRelease --offline
```

`AnalyticsDiagnosticsTest` 覆盖首次达到 100 条和继续写入至 105 条的淘汰行为。JVM 测试只验证记录容量和顺序；旧 Android 的兼容性还通过 Release APK 的调用检索验证。

### E-003 修复验证

- source_ref：`app/build/outputs/apk/release/app-release-unsigned.apk`（1.0.7，versionCode 12；用于分析的未签名包）。
- content_hash：SHA256 `f1af37ea0ddac3de72c98471ed617f645e4f4c55beb46e610f7b2753b8485e6e`。
- repro_command：本节 Gradle 命令；然后用 DexClub 打开该 APK，按 E-001 的查询检索调用。将名称匹配改为 `SimilarRegex`、`remove(First|Last)` 可同时检查两个方法。
- Gradle 结果：BUILD SUCCESSFUL；容量测试 1 项，failures=0，errors=0。
- 新 APK 查询结果：total=0、hasMore=false，没有对 `java.util.List.removeFirst/removeLast` 的调用。
- `d5.c` 的新 Java 导出明确使用 `au1Var.remove(0)`，其余容量阈值和追加行为不变。
- 未在 Android 14 或更低版本手机上做运行验证；本次未发布、未安装新包。

## Timeline

1. 从崩溃日志确认缺少系统 List.removeFirst 接口，当前源码直接检索无此调用。
2. 启动本机 DexClub MCP，定位原始 APK 的唯一调用点，并对应到 1.0.6 的 Analytics.record。
3. 确认 main 中已有 e651c61 修复，补充说明和容量测试，构建 Release 检查实际产物。
