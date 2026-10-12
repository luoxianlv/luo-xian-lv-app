package app.luoxianlv.input;

/** 仅对配置变化和短暂读取失败自动重查；稳定歧义由后续真实触摸触发，不循环耗电。 */
final class TouchRecoveryPolicy {
  static long retryDelay(String method, String errorType, int attempt) {
    if (attempt < 0 || attempt >= 3) return -1;
    if (!"state-changed".equals(method) && !"stale-route".equals(method)
        && !"IOException".equals(errorType)) return -1;
    return new long[] {100, 350, 1000}[attempt];
  }

  private TouchRecoveryPolicy() { }
}
