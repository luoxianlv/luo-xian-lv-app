package app.luoxianlv.host;

import static org.junit.Assert.*;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import app.luoxianlv.hot.*;
import app.luoxianlv.hot.contract.PlaybackBridge;
import app.luoxianlv.hot.contract.PlaybackPort;
import app.luoxianlv.hot.contract.PracticeBridge;
import app.luoxianlv.hot.contract.ProcessHooks;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import sun.misc.Unsafe;

/** 执行真实 HostUpdates.tick/退避/停用；平台、生命周期输入及 worker 是离线可控替身。 */
public final class HostUpdateSchedulerTest {
  private HostUpdates host;
  private UpdateSchedule schedule;
  private ConnectivityManager network;
  private Worker worker;

  private static final class Worker extends ScheduledThreadPoolExecutor {
    final List<Runnable> tasks = new ArrayList<>();
    Worker() { super(1); }
    @Override public void execute(Runnable task) { tasks.add(task); }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
  }

  private static void set(Class<?> type, Object owner, String name, Object value) throws Exception {
    Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
  }
  private static Object get(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
  }
  private static Object invoke(Object owner, String name) throws Exception {
    Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true);
    try { return method.invoke(owner); } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Error error) throw error;
      throw new AssertionError(failure.getCause());
    }
  }
  private void input(boolean foreground, boolean playback, boolean preparing) throws Exception {
    set(Bootstrap.class, null, "testForeground", foreground);
    set(Bootstrap.class, null, "testPlayback", playback);
    set(Bootstrap.class, null, "testPreparing", preparing);
  }
  private static void clock(long value) throws Exception { set(SystemClock.class, null, "now", value); }
  private static int timers() throws Exception { return (Integer) Handler.class.getMethod("count").invoke(null); }
  private void retry(Throwable failure) throws Exception {
    Method method = HostUpdates.class.getDeclaredMethod("retry", Throwable.class);
    method.setAccessible(true); method.invoke(host, failure);
    Handler.class.getMethod("drain").invoke(null);
  }

  @Before public void setup() throws Exception {
    Handler.class.getMethod("reset").invoke(null);
    clock(0);
    input(false, false, false);
    set(Bootstrap.class, null, "testCanActivate", false);
    var config = allocate(HostUpdateConfig.class);
    set(HostUpdateConfig.class, config, "environment", "test");
    var startup = allocate(HostStartup.class);
    set(HostStartup.class, startup, "config", config);
    host = allocate(HostUpdates.class);
    schedule = new UpdateSchedule(SystemClock::elapsedRealtime, () -> .5);
    network = allocate(ConnectivityManager.class);
    set(ConnectivityManager.class, network, "connected", true);
    set(ConnectivityManager.class, network, "validated", true);
    worker = new Worker();
    set(HostUpdates.class, host, "state", startup);
    set(HostUpdates.class, host, "schedule", schedule);
    set(HostUpdates.class, host, "main", new Handler(android.os.Looper.getMainLooper()));
    set(HostUpdates.class, host, "pulse", (Runnable) () -> {});
    set(HostUpdates.class, host, "worker", worker);
    set(HostUpdates.class, host, "connectivity", network);
  }

  @After public void cleanup() {
    if (worker != null) worker.shutdownNow();
  }

  @Test public void idleBackgroundDoesNotQueueRequestsOrTimersAndDisconnectedForegroundDoesNotRequest() throws Exception {
    invoke(host, "tick");
    assertEquals(0, worker.tasks.size());
    assertEquals(0, timers());
    input(true, false, false);
    set(ConnectivityManager.class, network, "connected", false);
    invoke(host, "tick");
    assertEquals(0, worker.tasks.size());
    assertTrue(timers() > 0); // 前台仅探测状态，没有网络检查。
    assertEquals(-1, schedule.delayMillis());
  }

  @Test public void normalPlaybackChecksWhilePreparationCancelsInFlightWork() throws Exception {
    input(false, true, false);
    invoke(host, "tick");
    assertEquals(1, worker.tasks.size());
    assertFalse((Boolean) invoke(host, "cancelled"));
    input(false, true, true);
    invoke(host, "tick");
    assertTrue((Boolean) invoke(host, "cancelled"));
    assertEquals(1, worker.tasks.size());
    input(false, false, true);
    invoke(host, "tick");
    assertTrue((Boolean) invoke(host, "cancelled"));
  }

  @Test public void foregroundChecksCoalesceAndCandidateWaitingForSafePointDoesNotStopPolling() throws Exception {
    input(true, false, false);
    invoke(host, "tick");
    invoke(host, "tick");
    assertEquals(1, worker.tasks.size());
    schedule.finish(true, 0);
    set(HostUpdates.class, host, "busy", false);
    set(HostUpdates.class, host, "pending", allocate(UpdateClient.PreparedUpdate.class));
    clock(59999);
    invoke(host, "tick");
    assertEquals(1, worker.tasks.size());
    clock(60000);
    invoke(host, "tick");
    assertEquals(2, worker.tasks.size()); // 不安全只禁止激活，仍允许一分钟发现新修订。
  }

  @Test public void networkRecoveryCannotBypassRetryAfterAndReleaseRequiresValidatedInternet() throws Exception {
    input(true, false, false);
    invoke(host, "tick");
    schedule.finish(false, 120000);
    set(HostUpdates.class, host, "busy", false);
    set(ConnectivityManager.class, network, "connected", false);
    invoke(host, "tick");
    set(ConnectivityManager.class, network, "connected", true);
    clock(1000);
    invoke(host, "tick");
    assertEquals(1, worker.tasks.size());
    assertEquals(119000, schedule.delayMillis());
    Object startup = get(host, "state");
    Object config = get(startup, "config");
    set(HostUpdateConfig.class, config, "environment", "production");
    set(ConnectivityManager.class, network, "validated", false);
    assertFalse((Boolean) invoke(host, "connected"));
    set(HostUpdateConfig.class, config, "environment", "test");
    assertTrue((Boolean) invoke(host, "connected"));
  }

  @Test public void stopUnregistersNetworkAndLateCallbacksDoNotRestartScheduling() throws Exception {
    input(true, false, false);
    set(HostUpdates.class, host, "networkCallback", new ConnectivityManager.NetworkCallback() {});
    invoke(host, "tick");
    host.stopScheduling();
    assertEquals(1, get(network, "unregistered"));
    assertEquals(0, timers());
    invoke(host, "networkChanged");
    invoke(host, "tick");
    host.usageChanged();
    assertEquals(0, timers());
    assertEquals(1, worker.tasks.size());
    assertFalse((Boolean) get(host, "active"));
    assertTrue((Boolean) invoke(host, "cancelled"));
  }

  @Test public void wrappedApiAndObjectRetryAfterRemainVisibleWithoutReadingErrorText() throws Exception {
    var api = HotApiClient.Failure.class.getDeclaredConstructor(int.class, String.class, long.class);
    api.setAccessible(true);
    Throwable apiFailure = api.newInstance(429, "rate_limited", 120000L);
    var object = HttpObjectSource.Failure.class.getDeclaredConstructor(int.class, long.class);
    object.setAccessible(true);
    Throwable objectFailure = object.newInstance(503, 300000L);
    objectFailure.initCause(apiFailure);
    assertEquals(300000, HostUpdates.retryAfterMillis(new Exception("公开测试错误", objectFailure)));
    assertEquals(0, HostUpdates.retryAfterMillis(new Exception("公开测试错误")));
  }

  @Test public void actualHostRetryCallbackPreservesWrappedServerDeadlineAcrossLifecycleTriggers() throws Exception {
    input(true, false, false);
    invoke(host, "tick");
    var api = HotApiClient.Failure.class.getDeclaredConstructor(int.class, String.class, long.class);
    api.setAccessible(true);
    retry(new Exception("公开测试包装", api.newInstance(429, "rate_limited", 300000L)));
    assertFalse((Boolean) get(host, "busy"));
    assertEquals(300000, schedule.delayMillis());
    input(false, false, false); host.usageChanged();
    input(true, false, false); host.usageChanged();
    invoke(host, "tick");
    assertEquals(1, worker.tasks.size());
    assertEquals(300000, schedule.delayMillis());
  }

  @Test public void actualHostCancellationDoesNotTurnPreparationIntoAnExponentialFailure() throws Exception {
    input(true, false, false); invoke(host, "tick");
    input(true, false, true); invoke(host, "availability");
    retry(new java.io.InterruptedIOException("公开测试准备让路"));
    assertEquals(-1, schedule.delayMillis());
    input(true, false, false); clock(30000); invoke(host, "tick");
    assertEquals(2, worker.tasks.size());
    schedule.finish(false, 0);
    assertEquals(60000, schedule.delayMillis());
  }

  /** 第二组运行使用真实 Bootstrap.java，验证既有 query 状态与异常隔离。 */
  public static final class PlaybackPriorityTest {
    private AutoCloseable connection;
    private Object practiceOwner;
    @After public void cleanup() throws Exception {
      if (connection != null) connection.close();
      if (practiceOwner != null) PracticeBridge.leave(practiceOwner);
      set(Bootstrap.class, null, "process", null);
    }
    private void connect(java.util.function.Supplier<Bundle> state) throws Exception {
      if (connection != null) connection.close();
      connection = PlaybackBridge.connect(new PlaybackPort() {
        public Bundle query(String kind) { assertEquals("state", kind); return state.get(); }
        public void command(String action, Bundle arguments) { throw new AssertionError("只读状态不得发送命令"); }
      });
    }
    @Test public void playingAndUnreplaceableProcessAreNotPreparation() throws Exception {
      AtomicInteger called = new AtomicInteger();
      set(Bootstrap.class, null, "process", new ProcessHooks() {
        public void initialize() {}
        public void trimMemory(int level) {}
        public boolean canReplace() { called.incrementAndGet(); return false; }
      });
      Bundle value = new Bundle(); value.putBoolean("preparing", false); value.putBoolean("playing", true);
      connect(() -> value);
      assertFalse(Bootstrap.playbackPreparing());
      assertEquals(0, called.get());
      for (String key : new String[] {"preparing", "loadingSong", "waitingToPlay"}) {
        value.putBoolean(key, true);
        assertTrue(Bootstrap.playbackPreparing());
        value.putBoolean(key, false);
      }
    }
    @Test public void missingNullAndThrowingQueriesFailClosedWithoutMutatingTheConnection() throws Exception {
      connect(Bundle::new); assertTrue(Bootstrap.playbackPreparing());
      connect(() -> null); assertTrue(Bootstrap.playbackPreparing());
      connect(() -> { throw new IllegalStateException("公开测试状态故障"); });
      PlaybackPort before = PlaybackBridge.current();
      assertTrue(Bootstrap.playbackPreparing());
      assertSame(before, PlaybackBridge.current());
      connection.close(); connection = null;
      assertFalse(Bootstrap.playbackPreparing());
    }
    @Test public void practiceOpeningHasPriorityAndReadyPracticeRemainsEligible() throws Exception {
      practiceOwner = new Object();
      PracticeBridge.enter(practiceOwner, () -> new PracticeBridge.Pitch("NATURAL", false));
      assertTrue(Bootstrap.playbackPreparing());
      PracticeBridge.setReady(practiceOwner, true);
      assertFalse(Bootstrap.playbackPreparing());
    }
  }
}
