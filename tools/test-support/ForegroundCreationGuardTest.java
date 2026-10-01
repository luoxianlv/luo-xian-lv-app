package app.luoxianlv.host;

import static org.junit.Assert.*;

import android.content.Intent;
import android.os.Handler;
import app.luoxianlv.hot.NativeLoader;
import app.luoxianlv.hot.contract.BusinessFactory;
import app.luoxianlv.hot.contract.ForegroundPolicy;
import app.luoxianlv.service.PlaybackForegroundService;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import sun.misc.Unsafe;

/** 独立 runner 执行真实前台服务准备逻辑；平台生命周期和 Bootstrap 门禁使用测试替身。 */
public final class ForegroundCreationGuardTest {
  private PlaybackForegroundService service;
  private AtomicInteger constructed, stopped;

  private static void set(Class<?> type, Object owner, String name, Object value) throws Exception {
    var field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
  }

  private static Object get(Class<?> type, String name) throws Exception {
    var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null);
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
    return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
  }

  private static void drain() throws Exception { Handler.class.getMethod("drain").invoke(null); }

  @Before public void fixture() throws Exception {
    Handler.class.getMethod("reset").invoke(null);
    Bootstrap.class.getMethod("resetTest").invoke(null);
    service = allocate(PlaybackForegroundService.class);
    set(PlaybackForegroundService.class, null, "instance", service);
    set(PlaybackForegroundService.class, null, "stopRequested", false);
    set(PlaybackForegroundService.class, service, "lastStartId", 7);
    constructed = new AtomicInteger(); stopped = new AtomicInteger();
    policy(new ForegroundPolicy() {
      public boolean shouldRun() { return true; }
      public void stopPlayback() { stopped.incrementAndGet(); }
    });
  }

  private void policy(ForegroundPolicy policy) throws Exception {
    BusinessFactory factory = (BusinessFactory) Proxy.newProxyInstance(
        BusinessFactory.class.getClassLoader(), new Class<?>[] {BusinessFactory.class},
        (proxy, method, args) -> { if (method.getName().equals("foreground")) { constructed.incrementAndGet(); return policy; } return null; });
    var selected = allocate(Bootstrap.Source.class);
    set(Bootstrap.Source.class, selected, "factory", factory);
    var prepared = allocate(NativeLoader.Prepared.class);
    set(Bootstrap.Source.class, selected, "prepared", prepared);
    set(Bootstrap.class, null, "testSource", selected);
  }

  private void begin(Intent intent) throws Exception {
    Class<?> type = Class.forName(PlaybackForegroundService.class.getName() + "$Preparation");
    Constructor<?> constructor = type.getDeclaredConstructor(PlaybackForegroundService.class, Intent.class, int.class);
    constructor.setAccessible(true); Object preparation = constructor.newInstance(service, intent, 7);
    set(PlaybackForegroundService.class, service, "preparation", preparation);
    Method await = type.getDeclaredMethod("awaitReady"); await.setAccessible(true); await.invoke(preparation);
  }

  @Test public void coldGateDefersAllPolicyCodeAndRetriesAfterAdmission() throws Exception {
    set(Bootstrap.class, null, "testDeferred", true);
    begin(null); assertEquals(0, constructed.get());
    set(Bootstrap.class, null, "testDeferred", false); drain();
    assertEquals(1, constructed.get()); assertNull(get(Bootstrap.class, "testFailure"));
    assertEquals(2, get(Bootstrap.class, "testGateCalls"));
  }

  @Test public void sourcePreparationWaitDoesNotReportBusinessFailure() throws Exception {
    set(Bootstrap.class, null, "testReady", false);
    begin(null); assertEquals(0, constructed.get()); assertNull(get(Bootstrap.class, "testFailure"));
    Bootstrap.class.getMethod("finishReady").invoke(null);
    assertEquals(1, constructed.get()); assertNull(get(Bootstrap.class, "testFailure"));
  }

  @Test public void synchronousReadyCannotLoseRetryCancellation() throws Exception {
    set(Bootstrap.class, null, "testDeferred", true); begin(null);
    var close = PlaybackForegroundService.class.getDeclaredMethod("closePreparation"); close.setAccessible(true); close.invoke(service);
    set(Bootstrap.class, null, "testDeferred", false); drain();
    assertEquals(0, constructed.get());
  }

  @Test public void laterStartInvalidatesOlderDeferredPolicy() throws Exception {
    set(Bootstrap.class, null, "testDeferred", true); begin(null);
    set(PlaybackForegroundService.class, service, "lastStartId", 8);
    set(Bootstrap.class, null, "testDeferred", false); drain(); assertEquals(0, constructed.get());
  }

  @Test public void stopCommandAlsoWaitsForTheColdCreationGate() throws Exception {
    set(Bootstrap.class, null, "testDeferred", true);
    begin(new Intent().setAction("app.luoxianlv.STOP_FLOATING_PLAYER"));
    assertEquals(0, constructed.get()); assertEquals(0, stopped.get());
    set(Bootstrap.class, null, "testDeferred", false); drain();
    assertEquals(1, constructed.get()); assertEquals(1, stopped.get());
  }

  @Test public void actualPolicyExceptionReachesWholeBusinessRecovery() throws Exception {
    var failure = new IllegalStateException("策略测试故障");
    policy(new ForegroundPolicy() {
      public boolean shouldRun() { throw failure; }
      public void stopPlayback() {}
    });
    begin(null); assertEquals(1, constructed.get()); assertSame(failure, get(Bootstrap.class, "testFailure"));
  }
}
