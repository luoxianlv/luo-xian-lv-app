package app.luoxianlv.host;

import static org.junit.Assert.*;

import android.os.Handler;
import android.os.Looper;
import app.luoxianlv.hot.*;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import sun.misc.Unsafe;

/** 真实宿主、许可、日志与Prepared关闭；平台/加载完成输入使用可控离线替身。 */
public final class HostStableRecoveryHandoffTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private Worker worker;

  /** 两个真实线程交替执行FIFO工作项，测试没有重写生产调度或恢复方法。 */
  private static final class Worker extends ScheduledThreadPoolExecutor {
    private final Queue<Runnable> waiting = new ArrayDeque<>();

    Worker() {
      super(1);
    }

    @Override
    public synchronized void execute(Runnable task) {
      waiting.add(task);
    }

    synchronized int pending() {
      return waiting.size();
    }

    Thread runNextAsync(AtomicReference<Throwable> failure) {
      final Runnable task;
      synchronized (this) {
        task = waiting.remove();
      }
      Thread thread =
          new Thread(
              () -> {
                try {
                  task.run();
                } catch (Throwable error) {
                  failure.set(error);
                }
              },
              "test-native-update");
      thread.start();
      return thread;
    }

    void runNext() throws Exception {
      var failure = new AtomicReference<Throwable>();
      Thread thread = runNextAsync(failure);
      thread.join(5000);
      assertFalse("工作线程未退出", thread.isAlive());
      if (failure.get() != null) throw new AssertionError(failure.get());
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    var field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
  }

  private static void set(Class<?> type, Object owner, String name, Object value) throws Exception {
    var field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static Object get(Class<?> type, Object owner, String name) throws Exception {
    var field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void drainMain() throws Exception {
    Handler.class.getMethod("drain").invoke(null);
  }

  private static byte[] vector(String name) throws Exception {
    return Files.readAllBytes(new File("modules/hot-core/src/test/resources/permit-v1", name).toPath());
  }

  private static HotManifest oldManifest() throws Exception {
    try (var archive = new ZipFile("modules/hot-core/src/test/resources/protocol-v1/base.lxhp");
        var input = archive.getInputStream(archive.getEntry("manifest.json"))) {
      return new HotManifest(input.readAllBytes());
    }
  }

  @Before
  public void reset() throws Exception {
    Handler.class.getMethod("reset").invoke(null);
    for (String name :
        new String[] {
          "failure", "source", "process", "startup", "updates", "activation", "playback"
        }) set(Bootstrap.class, null, name, null);
    for (String name :
        new String[] {"businessStopped", "recoveryAvailable", "updateBlocked", "finished"})
      set(Bootstrap.class, null, name, false);
    ((Map<?, ?>) get(Bootstrap.class, null, "PAGES")).clear();
    ((List<?>) get(Bootstrap.class, null, "WINDOWS")).clear();
    worker = new Worker();
  }

  @After
  public void close() {
    if (worker != null) worker.shutdownNow();
  }

  private void verify(boolean transferBeforeFailure, boolean failLease, boolean failJournal)
      throws Exception {
    HotManifest old = oldManifest(), candidateManifest = new HotManifest(vector("manifest.json"));
    assertNotEquals(old.snapshotId, candidateManifest.snapshotId);
    File journalRoot = temporary.newFolder();
    var journal = new ActivationJournal(journalRoot);
    String oldAttempt = journal.begin(old.snapshotId, 1, 1, 123, 1000);
    journal.firstFrame(oldAttempt);
    journal.healthy(oldAttempt, 60000);
    var quarantine = new ContentQuarantine(temporary.newFolder());
    var root = new HotSignatures.PublicKey(StrictJson.object(vector("root.public.json")));
    var trust = new TrustStore(temporary.newFolder(), root);
    var permitJson = StrictJson.object(vector("permit.json"));
    Instant serverTime = Instant.ofEpochSecond(permitJson.number("issuedAt") + 1);
    trust.accept(vector("trust.json"), vector("trust.sig.json"), journal, serverTime);
    var request =
        new ActivationPermit.Request(
            permitJson.string("installationId"),
            permitJson.string("nonce"),
            permitJson.string("hostIdentity"),
            1,
            permitJson.number("channelRevision"),
            1000,
            candidateManifest);
    var permit =
        new ActivationPermit(
            vector("permit.json"),
            vector("permit.sig.json"),
            trust.current().authority,
            request,
            1,
            1,
            serverTime,
            1100);
    File metadata = temporary.newFolder();
    Files.write(new File(metadata, "manifest.json").toPath(), vector("manifest.json"));
    Files.write(new File(metadata, "manifest.sig.json").toPath(), vector("manifest.sig.json"));
    var snapshot = allocate(ContentStore.Snapshot.class);
    set(ContentStore.Snapshot.class, snapshot, "manifest", candidateManifest);
    set(ContentStore.Snapshot.class, snapshot, "directory", metadata);
    var controller = new ActivationController(journal, trust, quarantine, 1, () -> 1200);
    var config = allocate(HostUpdateConfig.class);
    set(HostUpdateConfig.class, config, "hostContract", 1L);
    var startup = allocate(HostStartup.class);
    set(HostStartup.class, startup, "config", config);
    set(HostStartup.class, startup, "journal", journal);
    set(HostStartup.class, startup, "quarantine", quarantine);
    var host = allocate(HostUpdates.class);
    var main = new Handler(Looper.getMainLooper());
    set(HostUpdates.class, host, "state", startup);
    set(HostUpdates.class, host, "controller", controller);
    set(HostUpdates.class, host, "worker", worker);
    set(HostUpdates.class, host, "main", main);
    var schedule = new UpdateSchedule(() -> 1200, () -> .5);
    schedule.availability(true, false, true, false);
    assertTrue(schedule.beginIfDue());
    set(HostUpdates.class, host, "schedule", schedule);
    set(HostUpdates.class, host, "pulse", (Runnable) () -> fail("停止后的查询不应执行"));
    set(HostUpdates.class, host, "coldPulse", (Runnable) () -> fail("停止后的健康轮询不应执行"));
    set(HostUpdates.class, host, "active", true);
    set(HostUpdates.class, host, "busy", true);
    var previous = allocate(NativeLoader.Prepared.class);
    set(NativeLoader.Prepared.class, previous, "manifest", old);
    var source = allocate(Bootstrap.Source.class);
    set(Bootstrap.Source.class, source, "prepared", previous);
    set(Bootstrap.class, null, "source", source);
    set(Bootstrap.class, null, "startup", startup);
    set(Bootstrap.class, null, "updates", host);

    // 只替代已完成加载的对象输入；真正执行Prepared.closeCallbacks/ResourceScope.retire。
    var prepared = allocate(NativeLoader.Prepared.class);
    Class<?> scopeType = Class.forName("app.luoxianlv.hot.ResourceScope");
    Class<?> resourcesType = Class.forName("app.luoxianlv.hot.ModuleResources");
    Object scope = allocate(scopeType), resources = allocate(resourcesType);
    set(resourcesType, resources, "legacyOwners", new WeakHashMap<>());
    set(NativeLoader.Prepared.class, prepared, "official", scope);
    set(NativeLoader.Prepared.class, prepared, "resources", resources);
    var closed = new AtomicInteger();
    set(
        NativeLoader.Prepared.class,
        prepared,
        "contentLease",
        (AutoCloseable)
            () -> {
              closed.incrementAndGet();
              if (failLease) throw new IllegalStateException("测试：候选租约无法释放");
            });
    var candidate = allocate(UpdateClient.PreparedUpdate.class);
    Method activate =
        HostUpdates.class.getDeclaredMethod(
            "activate",
            UpdateClient.PreparedUpdate.class,
            ActivationController.Ticket.class,
            NativeLoader.Prepared.class);
    activate.setAccessible(true);
    var authorized = new CountDownLatch(1);
    var transfer = new CountDownLatch(transferBeforeFailure ? 0 : 1);
    var producerFailure = new AtomicReference<Throwable>();
    var ticketReference = new AtomicReference<ActivationController.Ticket>();
    worker.execute(
        () -> {
          try {
            var ticket = controller.begin(snapshot, permit, serverTime, 123);
            ticketReference.set(ticket);
            authorized.countDown();
            if (!transfer.await(5, TimeUnit.SECONDS)) throw new AssertionError("转交闸门未释放");
            main.post(
                () -> {
                  try {
                    activate.invoke(host, candidate, ticket, prepared);
                  } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                  }
                });
          } catch (Throwable error) {
            throw new AssertionError(error);
          }
        });
    Thread producer = worker.runNextAsync(producerFailure);
    try {
      assertTrue(authorized.await(5, TimeUnit.SECONDS));
      if (transferBeforeFailure) producer.join(5000);
      assertEquals(ActivationJournal.Phase.PREPARING, journal.state().phase);
      main.post((Runnable) get(HostUpdates.class, host, "pulse"));
      Bootstrap.componentFailed(new IllegalStateException("测试：当前稳定业务故障"));
      assertTrue(Bootstrap.businessStopped());
      assertFalse("未经候选退出/落盘不能开放入口", Bootstrap.recoveryAvailable());
      transfer.countDown();
      producer.join(5000);
      assertFalse(producer.isAlive());
      assertNull(producerFailure.get());
      assertEquals("恢复的第一工作屏障尚未执行", 1, worker.pending());
      worker.runNext(); // worker→main：授权转交一定先于主线程屏障。
      assertEquals(ActivationJournal.Phase.PREPARING, journal.state().phase);
      assertFalse(Bootstrap.recoveryAvailable());
      drainMain(); // 实际activate取消→closeCallbacks→排abort，再排恢复工作。
      assertEquals(1, closed.get());
      assertTrue((Boolean) get(NativeLoader.Prepared.class, prepared, "retired"));
      assertTrue((Boolean) get(scopeType, scope, "retired"));
      assertEquals("abort必须排在恢复前", 2, worker.pending());
      if (failJournal)
        Files.write(new File(journalRoot, "activation.bin").toPath(), new byte[] {1});
      worker.runNext(); // 真正controller.abort/journal.fail；稳定故障尚未处理。
      if (!failJournal) {
        assertEquals(ActivationJournal.Phase.STABLE, journal.state().phase);
        assertEquals(old.snapshotId, journal.state().stable);
        assertTrue(journal.state().quarantine.isEmpty());
        assertFalse(controller.valid(ticketReference.get()));
      } else assertEquals(ActivationJournal.Phase.PREPARING, journal.state().phase);
      assertFalse(Bootstrap.recoveryAvailable());
      drainMain();
      worker.runNext(); // 真正Bootstrap稳定故障恢复，或候选退出失败拒绝。
      drainMain();
      if (failLease || failJournal) {
        assertFalse("退出/取消落盘失败不能宣称安全", Bootstrap.recoveryAvailable());
        assertEquals(old.snapshotId, journal.state().stable);
        assertTrue("不能提前隔离稳定业务并开放入口", journal.state().quarantine.isEmpty());
      } else {
        assertTrue(Bootstrap.recoveryAvailable());
        assertEquals(ActivationJournal.Phase.STABLE, journal.state().phase);
        assertEquals("", journal.state().stable);
        assertTrue(journal.state().quarantine.contains(old.snapshotId));
        assertFalse(
            "取消不能错误归因候选", journal.state().quarantine.contains(candidateManifest.snapshotId));
        var reopened = new ActivationJournal(journalRoot).state();
        assertEquals("", reopened.stable);
        assertEquals(ActivationJournal.Phase.STABLE, reopened.phase);
      }
      assertEquals(0, worker.pending());
      assertTrue((Boolean) get(HostUpdates.class, host, "blocked"));
      assertFalse((Boolean) get(HostUpdates.class, host, "active"));
      assertEquals(0, ((Integer) Handler.class.getMethod("count").invoke(null)).intValue());
    } finally {
      transfer.countDown();
      producer.join(5000);
    }
  }

  @Test
  public void alreadyQueuedTransferAbortsBeforeStableRecovery() throws Exception {
    verify(true, false, false);
  }

  @Test
  public void inFlightTransferAbortsBeforeStableRecovery() throws Exception {
    verify(false, false, false);
  }

  @Test
  public void resourceCloseFailureNeverOpensRecovery() throws Exception {
    verify(false, true, false);
  }

  @Test
  public void abortJournalFailureNeverOpensRecovery() throws Exception {
    verify(true, false, true);
  }
}
