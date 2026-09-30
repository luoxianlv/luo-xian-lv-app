package app.luoxianlv.hot.contract;

/** 进程业务初始化与内存通知；系统 Application 和崩溃兜底由宿主持有。 */
public interface ProcessHooks extends AutoCloseable {
  void initialize();

  void trimMemory(int level);

  /** 旧实现缺少退出协议时推迟到重启，不能把关闭页面当成全代已释放。 */
  default boolean canReplace() {
    return false;
  }

  default boolean supportsRetirement() {
    return false;
  }

  default boolean quiesce() {
    return false;
  }

  default void resumeWork() {}

  default boolean released() {
    return false;
  }

  /** 后台读取当前隐私选择；缺省禁止远端诊断，不影响本地回退。 */
  default boolean diagnosticsAllowed() {
    return false;
  }

  @Override
  default void close() {}
}
