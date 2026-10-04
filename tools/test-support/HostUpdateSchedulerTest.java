package app.luoxianlv.host;

import static org.junit.Assert.*;

import android.net.ConnectivityManager;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    Worker() {
      super(1);
    }

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
  }

  private static void set(Class<?> type, Object owner, String name, Object value) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static Object get(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static Object invoke(Object owner, String name) throws Exception {
    Method method = owner.getClass().getDeclaredMethod(name);
    method.setAccessible(true);
    try {
      return method.invoke(owner);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Error error) throw error;
      throw new AssertionError(failure.getCause());
    }
  }

  private void input(boolean foreground, boolean playback, boolean preparing) throws Exception {
    set(Bootstrap.class, null, "testForeground", foreground);
    set(Bootstrap.class, null, "testPlayback", playback);
    set(Bootstrap.class, null, "testPreparing", preparing);
  }

  private static void clock(long value) throws Exception {
    set(SystemClock.class, null, "now", value);
  }

  private static int timers() throws Exception {
    return (Integer) Handler.class.getMethod("count").invoke(null);
  }

  private void retry(Throwable failure) throws Exception {
    Method method = HostUpdates.class.getDeclaredMethod("retry", Throwable.class);
    method.setAccessible(true);
    method.invoke(host, failure);
    Handler.class.getMethod("drain").invoke(null);
  }

  @Before
  public void setup() throws Exception {
    Handler.class.getMethod("reset").invoke(null);
    clock(0);
    input(false, false, false);
    set(Bootstrap.class, null, "testCanActivate", false);
    set(Bootstrap.class, null, "testBusinessWorking", false);
    ((AtomicInteger) getStatic(ApkUpdateBridge.class, "ACTIVE")).set(0);
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
    set(HostUpdates.class, host, "coldPulse", (Runnable) () -> {});
    set(HostUpdates.class, host, "worker", worker);
    set(HostUpdates.class, host, "connectivity", network);
    set(HostUpdates.class, host, "online", true);
    set(HostUpdates.class, host, "networkCallback", invoke(host, "networkListener"));
  }

  @After
  public void cleanup() {
    if (worker != null) worker.shutdownNow();
    try {
      ((AtomicInteger) getStatic(ApkUpdateBridge.class, "ACTIVE")).set(0);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static Object getStatic(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
  }

  @Test
  public void idleBackgroundDoesNotQueueRequestsOrTimersAndDisconnectedForegroundDoesNotRequest()
      throws Exception {
    invoke(host, "tick");
    assertEquals(0, worker.tasks.size());
    assertEquals(0, timers());
    input(true, false, false);
    set(ConnectivityManager.class, network, "connected", false);
    set(HostUpdates.class, host, "online", false);
    invoke(host, "tick");
    assertEquals(0, worker.tasks.size());
    assertTrue(timers() > 0); // 前台仅探测状态，没有网络检查。
    assertEquals(-1, schedule.delayMillis());
  }

  @Test
  public void normalPlaybackChecksWhilePreparationCancelsInFlightWork() throws Exception {
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

  @Test
  public void ordinaryApkAndBusinessIoStopHotPreparationWithoutQueryingBinder() throws Exception {
    input(true, false, false);
    set(ConnectivityManager.class, network, "rejectQuery", true);
    var token = new UpdateCancellation(() -> false);
    set(HostUpdates.class, host, "requestCancellation", token);
    ((AtomicInteger) getStatic(ApkUpdateBridge.class, "ACTIVE")).set(1);
    host.usageChanged();
    assertTrue(token.isCancelled());
    invoke(host, "tick");
    assertTrue(worker.tasks.isEmpty());
    assertEquals(0, get(network, "queries"));
    ((AtomicInteger) getStatic(ApkUpdateBridge.class, "ACTIVE")).set(0);
    set(Bootstrap.class, null, "testBusinessWorking", true);
    invoke(host, "tick");
    assertTrue(worker.tasks.isEmpty());
    set(Bootstrap.class, null, "testBusinessWorking", false);
    invoke(host, "tick");
    assertEquals(1, worker.tasks.size());
  }

  @Test
  public void apkWaitsUntilHotWorkerAndCandidateCleanupHaveBothFinished() throws Exception {
    assertTrue(host.apkWorkIdle());
    set(HostUpdates.class, host, "busy", true);
    assertFalse(host.apkWorkIdle());
    set(HostUpdates.class, host, "busy", false);
    var token = new UpdateCancellation(() -> false);
    set(HostUpdates.class, host, "requestCancellation", token);
    token.cancel();
    assertFalse(host.apkWorkIdle()); // 已取消不等于流、描述符和候选已经释放。
    set(HostUpdates.class, host, "requestCancellation", null);
    assertTrue(host.apkWorkIdle());
  }

  @Test
  public void foregroundChecksCoalesceAndCandidateWaitingForSafePointDoesNotStopPolling()
      throws Exception {
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

  @Test
  public void networkRecoveryCannotBypassRetryAfterAndReleaseRequiresValidatedInternet()
      throws Exception {
    input(true, false, false);
    invoke(host, "tick");
    schedule.finish(false, 120000);
    set(HostUpdates.class, host, "busy", false);
    set(ConnectivityManager.class, network, "connected", false);
    set(HostUpdates.class, host, "online", false);
    invoke(host, "tick");
    set(ConnectivityManager.class, network, "connected", true);
    set(HostUpdates.class, host, "online", true);
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

  @Test
  public void stopUnregistersNetworkAndLateCallbacksDoNotRestartScheduling() throws Exception {
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

  @Test
  public void usageEdgesUseCachedNetworkWithoutBinderAndIgnoreOldNetworkLoss() throws Exception {
    input(true, false, true);
    set(ConnectivityManager.class, network, "rejectQuery", true);
    var callback = (ConnectivityManager.NetworkCallback) get(host, "networkCallback");
    var old = new android.net.Network();
    var current = new android.net.Network();
    var capabilities = new android.net.NetworkCapabilities();
    capabilities.validated = true;
    callback.onAvailable(old);
    callback.onCapabilitiesChanged(old, capabilities);
    callback.onAvailable(current);
    callback.onCapabilitiesChanged(current, capabilities);
    callback.onLost(old);
    host.usageChanged();
    invoke(host, "tick");
    assertTrue((Boolean) get(host, "online"));
    assertEquals(0, get(network, "queries"));
    callback.onLost(current);
    assertFalse((Boolean) get(host, "online"));
    host.stopScheduling();
    callback.onAvailable(current);
    callback.onCapabilitiesChanged(current, capabilities);
    assertFalse((Boolean) get(host, "active"));
  }

  @Test
  public void unavailableListenerQueuesOnlyOneBackgroundReadAndLateResultCannotRestart()
      throws Exception {
    input(true, false, true);
    set(HostUpdates.class, host, "networkCallback", null);
    set(HostUpdates.class, host, "online", false);
    invoke(host, "tick");
    invoke(host, "tick");
    host.usageChanged();
    assertEquals(0, get(network, "queries"));
    assertEquals(1, worker.tasks.size());
    worker.tasks.remove(0).run();
    assertEquals(1, get(network, "queries"));
    host.stopScheduling();
    Handler.class.getMethod("drain").invoke(null);
    assertFalse((Boolean) get(host, "active"));
    assertEquals(0, timers());
  }

  /** worker完成加载与主线程故障停用确定性交替，真实activate取消路径必须关闭并abort。 */
  private void handoffAcrossStop(boolean queuedBeforeStop, int failureMode) throws Exception {
    var directory = java.nio.file.Files.createTempDirectory("host-stop-handoff-").toFile();
    var journal = new ActivationJournal(directory);
    String attempt = journal.begin("a".repeat(64), 1, 1, 100, 1000);
    var ticket = allocate(ActivationController.Ticket.class);
    set(ActivationController.Ticket.class, ticket, "attemptId", attempt);
    set(
        ActivationController.Ticket.class,
        ticket,
        "health",
        new HealthWindow(SystemClock::elapsedRealtime));
    var controller = new ActivationController(journal, null, null, 1, SystemClock::elapsedRealtime);
    set(ActivationController.class, controller, "current", ticket);
    set(HostUpdates.class, host, "controller", controller);
    schedule.availability(true, false, true, false);
    assertTrue(schedule.beginIfDue());
    set(HostUpdates.class, host, "busy", true);
    set(HostUpdates.class, host, "active", true);

    var prepared = allocate(NativeLoader.Prepared.class);
    Class<?> scopeType = Class.forName("app.luoxianlv.hot.ResourceScope");
    Class<?> resourcesType = Class.forName("app.luoxianlv.hot.ModuleResources");
    Object scope = allocate(scopeType), resources = allocate(resourcesType);
    set(resourcesType, resources, "legacyOwners", new WeakHashMap<>());
    set(NativeLoader.Prepared.class, prepared, "official", scope);
    set(NativeLoader.Prepared.class, prepared, "resources", resources);
    AtomicInteger closed = new AtomicInteger();
    set(
        NativeLoader.Prepared.class,
        prepared,
        "contentLease",
        (AutoCloseable)
            () -> {
              closed.incrementAndGet();
              if (failureMode == 1) throw new IllegalStateException("公开测试租约释放故障");
            });
    if (failureMode == 2) {
      var journalFile = directory.toPath().resolve("activation.bin");
      byte[] bytes = java.nio.file.Files.readAllBytes(journalFile);
      bytes[12] ^= 1;
      java.nio.file.Files.write(journalFile, bytes); // 真正的已登记字节失配，abort必须拒绝覆盖。
    }
    var candidate = allocate(UpdateClient.PreparedUpdate.class);
    Method activate =
        HostUpdates.class.getDeclaredMethod(
            "activate",
            UpdateClient.PreparedUpdate.class,
            ActivationController.Ticket.class,
            NativeLoader.Prepared.class);
    activate.setAccessible(true);
    Handler handler = (Handler) get(host, "main");
    handler.post((Runnable) get(host, "pulse"));
    handler.post((Runnable) get(host, "coldPulse"));
    CountDownLatch posted = new CountDownLatch(1),
        allowPost = new CountDownLatch(queuedBeforeStop ? 0 : 1);
    AtomicReference<Throwable> producerFailure = new AtomicReference<>();
    AtomicInteger restored = new AtomicInteger(), rejected = new AtomicInteger();
    AtomicReference<Throwable> rejection = new AtomicReference<>();
    Thread producer =
        new Thread(
            () -> {
              try {
                if (!allowPost.await(5, TimeUnit.SECONDS))
                  throw new AssertionError("producer not released");
                handler.post(
                    () -> {
                      try {
                        activate.invoke(host, candidate, ticket, prepared);
                      } catch (ReflectiveOperationException error) {
                        throw new AssertionError(error);
                      }
                    });
              } catch (Throwable error) {
                producerFailure.set(error);
              } finally {
                posted.countDown();
              }
            },
            "test-loaded-candidate");
    producer.start();
    try {
      if (queuedBeforeStop) assertTrue(posted.await(5, TimeUnit.SECONDS));
      host.stopScheduling();
      host.afterStopped(
          () -> {
            assertEquals(ActivationJournal.Phase.STABLE, journal.state().phase);
            assertEquals(1, closed.get());
            restored.incrementAndGet();
          },
          failure -> {
            rejection.set(failure);
            rejected.incrementAndGet();
          });
      allowPost.countDown();
      assertTrue(posted.await(5, TimeUnit.SECONDS));
      assertNull(producerFailure.get());
      assertEquals("only handoff survives stop", 1, timers());
      assertEquals(1, worker.tasks.size());
      worker.tasks.remove(0).run(); // worker→main屏障排在已完成加载的handoff之后。
      Handler.class.getMethod("drain").invoke(null);
      assertEquals(1, closed.get());
      assertTrue((Boolean) get(prepared, "retired"));
      assertTrue((Boolean) get(scope, "retired"));
      assertTrue((Boolean) get(resources, "closed"));
      assertEquals("abort must precede recovery", 2, worker.tasks.size());
      assertEquals(0, restored.get());
      assertEquals(0, rejected.get());
      worker.tasks.remove(0).run(); // 真实controller.abort/journal.fail落盘，不运行HTTP或SDK加载。
      worker.tasks.remove(0).run(); // main→worker屏障，完成或明确拒绝恢复。
      assertEquals(failureMode == 0 ? 1 : 0, restored.get());
      assertEquals(failureMode == 0 ? 0 : 1, rejected.get());
      if (failureMode == 0) assertNull(rejection.get());
      else assertSame(get(host, "stoppedCleanupFailure"), rejection.get());
      assertEquals(
          failureMode == 2 ? ActivationJournal.Phase.PREPARING : ActivationJournal.Phase.STABLE,
          journal.state().phase);
      if (failureMode != 2) assertEquals("", journal.state().candidate);
      assertTrue(journal.state().quarantine.isEmpty());
      assertEquals(failureMode == 2, controller.valid(ticket));
      Handler.class.getMethod("drain").invoke(null);
      assertFalse((Boolean) get(host, "busy"));
      assertTrue((Boolean) get(host, "blocked"));
      assertEquals(0, timers());
      host.stopScheduling();
      Handler.class.getMethod("drain").invoke(null);
      assertEquals(1, closed.get());
      assertEquals(0, worker.tasks.size());
    } finally {
      allowPost.countDown();
      producer.join(5000);
      assertFalse("producer leaked", producer.isAlive());
      // 仅本测试创建的随机目录，JVM回执保留在系统临时目录供排查。
    }
  }

  @Test
  public void loadedCandidateQueuedBeforeStopStillReleasesAndAborts() throws Exception {
    handoffAcrossStop(true, 0);
  }

  @Test
  public void loadedCandidateQueuedAfterStopStillReleasesAndAborts() throws Exception {
    handoffAcrossStop(false, 0);
  }

  @Test
  public void stoppedRecoveryRejectsCandidateLeaseCloseFailure() throws Exception {
    handoffAcrossStop(true, 1);
  }

  @Test
  public void stoppedRecoveryRejectsActualJournalByteMismatch() throws Exception {
    handoffAcrossStop(true, 2);
  }

  @Test
  public void recoveryBarrierRequiresStoppedInactiveHost() throws Exception {
    AtomicInteger called = new AtomicInteger();
    assertThrows(
        IllegalStateException.class,
        () -> host.afterStopped(called::incrementAndGet, failure -> called.incrementAndGet()));
    set(HostUpdates.class, host, "blocked", true);
    set(HostUpdates.class, host, "active", true);
    assertThrows(
        IllegalStateException.class,
        () -> host.afterStopped(called::incrementAndGet, failure -> called.incrementAndGet()));
    assertEquals(0, called.get());
    assertTrue(worker.tasks.isEmpty());
  }

  @Test
  public void wrappedApiAndObjectRetryAfterRemainVisibleWithoutReadingErrorText() throws Exception {
    var api =
        HotApiClient.Failure.class.getDeclaredConstructor(int.class, String.class, long.class);
    api.setAccessible(true);
    Throwable apiFailure = api.newInstance(429, "rate_limited", 120000L);
    var object = HttpObjectSource.Failure.class.getDeclaredConstructor(int.class, long.class);
    object.setAccessible(true);
    Throwable objectFailure = object.newInstance(503, 300000L);
    objectFailure.initCause(apiFailure);
    assertEquals(300000, HostUpdates.retryAfterMillis(new Exception("公开测试错误", objectFailure)));
    assertEquals(0, HostUpdates.retryAfterMillis(new Exception("公开测试错误")));
  }

  @Test
  public void actualHostRetryCallbackPreservesWrappedServerDeadlineAcrossLifecycleTriggers()
      throws Exception {
    input(true, false, false);
    invoke(host, "tick");
    var api =
        HotApiClient.Failure.class.getDeclaredConstructor(int.class, String.class, long.class);
    api.setAccessible(true);
    retry(new Exception("公开测试包装", api.newInstance(429, "rate_limited", 300000L)));
    assertFalse((Boolean) get(host, "busy"));
    assertEquals(300000, schedule.delayMillis());
    input(false, false, false);
    host.usageChanged();
    input(true, false, false);
    host.usageChanged();
    invoke(host, "tick");
    assertEquals(1, worker.tasks.size());
    assertEquals(300000, schedule.delayMillis());
  }

  @Test
  public void actualHostCancellationDoesNotTurnPreparationIntoAnExponentialFailure()
      throws Exception {
    input(true, false, false);
    invoke(host, "tick");
    input(true, false, true);
    invoke(host, "availability");
    retry(new UpdateCancellation.Cancelled());
    assertEquals(-1, schedule.delayMillis());
    input(true, false, false);
    clock(30000);
    invoke(host, "tick");
    assertEquals(2, worker.tasks.size());
    schedule.finish(false, 0);
    assertEquals(60000, schedule.delayMillis());
  }

  @Test
  public void completedPreparationCannotTurnLateCancellationIntoNetworkBackoff() throws Exception {
    input(true, false, false);
    invoke(host, "tick");
    input(true, false, true);
    invoke(host, "availability");
    var cancelledOperation = new UpdateCancellation.Cancelled();
    input(true, false, false);
    invoke(host, "availability");
    retry(cancelledOperation);
    assertEquals(0, get(schedule, "failures"));
    assertEquals(30000, schedule.delayMillis());
    input(false, false, false);
    host.usageChanged();
    input(true, false, false);
    host.usageChanged();
    assertEquals(30000, schedule.delayMillis());
  }

  /** 第二组运行使用真实 Bootstrap.java，验证既有 query 状态与异常隔离。 */
  public static final class PlaybackPriorityTest {
    private AutoCloseable connection;
    private Object practiceOwner;

    @After
    public void cleanup() throws Exception {
      if (connection != null) connection.close();
      if (practiceOwner != null) PracticeBridge.leave(practiceOwner);
      set(Bootstrap.class, null, "process", null);
      set(Bootstrap.class, null, "updates", null);
      set(Bootstrap.class, null, "activation", null);
    }

    private void connect(java.util.function.Supplier<Bundle> state) throws Exception {
      if (connection != null) connection.close();
      connection =
          PlaybackBridge.connect(
              new PlaybackPort() {
                public Bundle query(String kind) {
                  assertEquals("state", kind);
                  return state.get();
                }

                public void command(String action, Bundle arguments) {
                  throw new AssertionError("只读状态不得发送命令");
                }
              });
    }

    @Test
    public void playingAndUnreplaceableProcessAreNotPreparation() throws Exception {
      AtomicInteger called = new AtomicInteger();
      set(
          Bootstrap.class,
          null,
          "process",
          new ProcessHooks() {
            public void initialize() {}

            public void trimMemory(int level) {}

            public boolean canReplace() {
              called.incrementAndGet();
              return false;
            }
          });
      Bundle value = new Bundle();
      value.putBoolean("preparing", false);
      value.putBoolean("playing", true);
      connect(() -> value);
      assertFalse(Bootstrap.playbackPreparing());
      assertEquals(0, called.get());
      for (String key : new String[] {"preparing", "loadingSong", "waitingToPlay"}) {
        value.putBoolean(key, true);
        assertTrue(Bootstrap.playbackPreparing());
        value.putBoolean(key, false);
      }
    }

    @Test
    public void missingNullAndThrowingQueriesFailClosedWithoutMutatingTheConnection()
        throws Exception {
      connect(Bundle::new);
      assertTrue(Bootstrap.playbackPreparing());
      connect(() -> null);
      assertTrue(Bootstrap.playbackPreparing());
      connect(
          () -> {
            throw new IllegalStateException("公开测试状态故障");
          });
      PlaybackPort before = PlaybackBridge.current();
      assertTrue(Bootstrap.playbackPreparing());
      assertSame(before, PlaybackBridge.current());
      connection.close();
      connection = null;
      assertFalse(Bootstrap.playbackPreparing());
    }

    @Test
    public void practiceOpeningHasPriorityAndReadyPracticeRemainsEligible() throws Exception {
      practiceOwner = new Object();
      PracticeBridge.enter(practiceOwner, () -> new PracticeBridge.Pitch("NATURAL", false));
      assertTrue(Bootstrap.playbackPreparing());
      PracticeBridge.setReady(practiceOwner, true);
      assertFalse(Bootstrap.playbackPreparing());
    }

    @Test
    public void ordinaryApkWaitsForExistingHotWorkAndActivationButNotItsOwnBusinessLease()
        throws Exception {
      var host = allocate(HostUpdates.class);
      set(Bootstrap.class, null, "updates", host);
      assertTrue(Bootstrap.ordinaryUpdateIdle());
      set(HostUpdates.class, host, "busy", true);
      assertFalse(Bootstrap.ordinaryUpdateIdle());
      set(HostUpdates.class, host, "busy", false);
      set(Bootstrap.class, null, "activation", allocate(GroupActivation.class));
      assertFalse(Bootstrap.ordinaryUpdateIdle());
      set(Bootstrap.class, null, "activation", null);
      set(
          Bootstrap.class,
          null,
          "process",
          new ProcessHooks() {
            public void initialize() {}

            public void trimMemory(int level) {}

            public boolean canReplace() {
              return false;
            }
          });
      assertTrue(Bootstrap.businessWorking());
      assertTrue(Bootstrap.ordinaryUpdateIdle());
    }
  }
}
