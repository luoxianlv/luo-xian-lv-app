package app.luoxianlv.host;

import java.lang.reflect.*;
import java.util.function.BooleanSupplier;
import sun.misc.Unsafe;

/** 真实awaitStage状态机，只有JVM测试的SystemClock/sleep为无等待替身；不测实际健康。 */
public final class NativeContinuousBudgetChecks {
  public static void main(String[] args) throws Exception {
    var unsafeField = Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
    Object checks = ((Unsafe) unsafeField.get(null)).allocateInstance(NativeContinuousChecks.class);
    var deadline = NativeContinuousChecks.class.getDeclaredField("stageDeadline"); deadline.setAccessible(true);
    var await = NativeContinuousChecks.class.getDeclaredMethod("awaitStage", String.class, BooleanSupplier.class);
    await.setAccessible(true);
    var clock = android.os.SystemClock.class.getField("now");
    int passed = 0;
    clock.setLong(null, 0); deadline.setLong(checks, NativeContinuousChecks.STAGE_TIMEOUT_MILLIS);
    await.invoke(checks, "JVM正常阶段", (BooleanSupplier) () -> android.os.SystemClock.elapsedRealtime() >= 320000);
    if (clock.getLong(null) != 320000) throw new AssertionError("Legitimate long natural stage was shortened");
    passed++;
    clock.setLong(null, 0); deadline.setLong(checks, NativeContinuousChecks.STAGE_TIMEOUT_MILLIS);
    expectTimeout(await, checks, () -> false);
    if (clock.getLong(null) != NativeContinuousChecks.STAGE_TIMEOUT_MILLIS) throw new AssertionError("Stage did not enforce its total limit");
    passed++;
    clock.setLong(null, 329000); deadline.setLong(checks, NativeContinuousChecks.STAGE_TIMEOUT_MILLIS);
    await.invoke(checks, "JVM末尾检查", (BooleanSupplier) () -> android.os.SystemClock.elapsedRealtime() >= 330000);
    if (clock.getLong(null) != 330000) throw new AssertionError("Remaining budget was reset");
    passed++;
    clock.setLong(null, 330001);
    expectTimeout(await, checks, () -> true);
    passed++;
    System.out.println("NativeContinuousBudgetChecks: " + passed + " passed; actual health/device clock not modified");
  }
  private static void expectTimeout(Method await, Object checks, BooleanSupplier done) throws Exception {
    try { await.invoke(checks, "JVM超期检查", done); throw new AssertionError("Expired stage accepted"); }
    catch (InvocationTargetException failure) {
      if (!(failure.getCause() instanceof AssertionError)) throw failure;
    }
  }
}
