package app.luoxianlv.input;

/** 启动通道与演奏会话分离；真实 Binder 失效可以有限恢复，用户停止保持停止。 */
final class WirelessRecoveryPolicy {
  private WirelessRecoveryPolicy() { }
  static boolean mayRecover(boolean wanted, boolean paired, int attempts) { return wanted && paired && attempts < 3; }
  static boolean afterForegroundCancel(boolean wanted, boolean connected) {
    return wanted && connected;
  }
}
