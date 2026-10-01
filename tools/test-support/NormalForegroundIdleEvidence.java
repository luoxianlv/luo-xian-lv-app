package app.luoxianlv.host;

/** 仅测试APK的小适配器；复用既有固定tag/PID/epoch/startId解析器，不访问宿主私有字段。 */
public final class NormalForegroundIdleEvidence {
  public static final int MAX_BYTES = NativeForegroundLogEvents.MAX_BYTES;
  private final NativeForegroundLogEvents.Cursor cursor;

  public NormalForegroundIdleEvidence(int pid, String before) {
    cursor = new NativeForegroundLogEvents.Cursor(pid, before);
  }

  public void startedAt(long epochMillis) { cursor.startedAt(epochMillis); }

  public record Observation(boolean entered, boolean idleStopped, boolean unavailable,
      boolean stopRequested, int startId, long enterEpochMillis, long stopEpochMillis) {}

  public Observation observe(String text) {
    var result = cursor.observe(text);
    return new Observation(result.enterObserved, result.normalIdleStopped, result.businessUnavailable,
        result.stopRequestedObserved, result.startId, result.enterAt, result.stopAt);
  }
}
