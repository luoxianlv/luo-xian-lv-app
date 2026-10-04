package app.luoxianlv.hot.contract;

/** 安全点变化属于暂缓，不能把正在下载或解析误记成候选代码故障。 */
public final class HandoverDeferred extends IllegalStateException {
  public final boolean restartRequired;

  public HandoverDeferred(String message) {
    this(message, false);
  }

  public HandoverDeferred(String message, boolean restartRequired) {
    super(message);
    this.restartRequired = restartRequired;
  }
}
