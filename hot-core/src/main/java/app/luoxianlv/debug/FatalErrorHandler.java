package app.luoxianlv.debug;

/** 磁盘和业务发生任何异常，都不能吞掉原始崩溃或阻断系统/统计处理器。 */
public final class FatalErrorHandler implements Thread.UncaughtExceptionHandler {
  public interface Save {
    void write(Thread thread, Throwable failure) throws Exception;
  }

  private final Save save;
  private final Thread.UncaughtExceptionHandler next;

  public FatalErrorHandler(Save save, Thread.UncaughtExceptionHandler next) {
    this.save = save;
    this.next = next;
  }

  @Override
  public void uncaughtException(Thread thread, Throwable failure) {
    try {
      save.write(thread, failure);
    } catch (Throwable ignored) {
      // 崩溃记录仅作尽力保存，不能替换原始异常。
    } finally {
      next.uncaughtException(thread, failure);
    }
  }
}
