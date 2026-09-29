package app.luoxianlv.hot.contract;

/** 进程业务初始化与内存通知；系统 Application 和崩溃兜底由宿主持有。 */
public interface ProcessHooks extends AutoCloseable {
  void initialize();

  void trimMemory(int level);

  @Override
  default void close() {}
}
