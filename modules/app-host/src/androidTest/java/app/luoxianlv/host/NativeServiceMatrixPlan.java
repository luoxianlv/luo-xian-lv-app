package app.luoxianlv.host;

/** 冷服务矩阵的只读预期；不构造许可、选择记录或候选加载器。 */
final class NativeServiceMatrixPlan {
  final String runId, scenario, consumer, target, source, sourceRuntime, targetRuntime;
  final String expectedSnapshot, expectedRuntime, expectedPhase;

  NativeServiceMatrixPlan(
      String runId, String scenario, String consumer, String target, String source,
      String sourceRuntime, String targetRuntime) {
    check(runId != null && runId.matches("[a-f0-9]{32}"), "矩阵runId无效");
    check(java.util.Set.of("valid", "revoked", "expired", "offline").contains(scenario), "矩阵场景无效");
    check(java.util.Set.of("accessibility", "foreground").contains(consumer), "矩阵服务入口无效");
    for (String hash : new String[] {target, source, sourceRuntime, targetRuntime})
      check(hash != null && hash.matches("[a-f0-9]{64}"), "矩阵公开身份无效");
    check(!target.equals(source) && !targetRuntime.equals(sourceRuntime), "矩阵必须使用不同待重启运行时");
    this.runId = runId;
    this.scenario = scenario;
    this.consumer = consumer;
    this.target = target;
    this.source = source;
    this.sourceRuntime = sourceRuntime;
    this.targetRuntime = targetRuntime;
    boolean valid = scenario.equals("valid");
    expectedSnapshot = valid ? target : source;
    expectedRuntime = valid ? targetRuntime : sourceRuntime;
    expectedPhase = valid ? consumer.equals("accessibility") ? "TRIAL" : "PREPARING" : "STABLE";
  }

  void expected(String snapshot, String stable, String pending, String runtime, String phase) {
    check(expectedSnapshot.equals(snapshot) && source.equals(stable) && target.equals(pending)
        && expectedRuntime.equals(runtime) && expectedPhase.equals(phase), "矩阵预期必须匹配实际冷服务契约");
  }

  boolean marker(ClassLoader businessLoader) throws Exception {
    Class<?> marker;
    try {
      marker = Class.forName("app.luoxianlv.runtime.probe.NewRuntimeMarker", false, businessLoader);
    } catch (ClassNotFoundException missing) {
      check(!scenario.equals("valid"), "有效新运行时缺少新增测试类");
      return false;
    }
    check(scenario.equals("valid"), "拒绝场景加载了新运行时测试类");
    ClassLoader runtime = Class.forName("kotlin.Unit", false, businessLoader).getClassLoader();
    check(marker.getClassLoader() == runtime && runtime == businessLoader.getParent(),
        "新增测试类与业务未共用同一新运行时父加载器");
    return true;
  }

  private static void check(boolean value, String message) {
    if (!value) throw new IllegalArgumentException(message);
  }
}
