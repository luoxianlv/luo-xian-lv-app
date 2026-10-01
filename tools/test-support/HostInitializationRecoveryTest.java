package app.luoxianlv.host;

import static org.junit.Assert.*;

import android.os.Handler;
import app.luoxianlv.hot.*;
import app.luoxianlv.hot.contract.BusinessFactory;
import app.luoxianlv.hot.contract.ProcessHooks;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import sun.misc.Unsafe;

/** 真实 Bootstrap、激活日志和隔离记录；独立 JVM 替身只提供平台与已准备模块。 */
public final class HostInitializationRecoveryTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static <T> T allocate(Class<T> type) throws Exception {
    var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
    return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
  }

  private static void set(Class<?> type, Object owner, String name, Object value) throws Exception {
    var field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
  }

  @Before public void reset() throws Exception {
    Handler.class.getMethod("reset").invoke(null);
    for (String name : new String[] {"failure", "source", "process", "startup", "updates", "activation"})
      set(Bootstrap.class, null, name, null);
    for (String name : new String[] {"businessStopped", "recoveryAvailable", "updateBlocked", "finished"})
      set(Bootstrap.class, null, name, false);
  }

  private void checkRecovery(boolean blockedWrite) throws Exception {
    byte[] raw;
    try (var input = getClass().getResourceAsStream("/permit-v1/manifest.json")) { assertNotNull(input); raw = input.readAllBytes(); }
    var manifest = new HotManifest(raw);
    File journalRoot = temporary.newFolder(), quarantineRoot = temporary.newFolder();
    var journal = new ActivationJournal(journalRoot);
    String attempt = journal.begin(manifest.snapshotId, 9, 1, 123, 1000);
    journal.firstFrame(attempt); journal.healthy(attempt, 60000);
    byte[] before = Files.readAllBytes(new File(journalRoot, "activation.bin").toPath());
    var quarantine = new ContentQuarantine(quarantineRoot);
    if (blockedWrite) assertTrue(new File(quarantineRoot, "quarantine.lock").mkdir());
    var startup = allocate(HostStartup.class); var config = allocate(HostUpdateConfig.class);
    set(HostUpdateConfig.class, config, "hostContract", 1L);
    set(HostStartup.class, startup, "config", config);
    set(HostStartup.class, startup, "journal", journal);
    set(HostStartup.class, startup, "quarantine", quarantine);
    set(Bootstrap.class, null, "startup", startup);
    AtomicInteger closed = new AtomicInteger();
    BusinessFactory factory = (BusinessFactory) Proxy.newProxyInstance(BusinessFactory.class.getClassLoader(),
        new Class<?>[] {BusinessFactory.class}, (proxy, method, args) -> {
          if (!method.getName().equals("process")) return null;
          return new ProcessHooks() {
            public void initialize() { throw new IllegalStateException("初始化测试故障"); }
            public void trimMemory(int level) {}
            public void close() { closed.incrementAndGet(); }
          };
        });
    set(NativeLoader.class, null, "testFactory", factory);
    var prepared = allocate(NativeLoader.Prepared.class);
    set(NativeLoader.Prepared.class, prepared, "manifest", manifest);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      var initialize = Bootstrap.class.getDeclaredMethod("initializePrepared", NativeLoader.Prepared.class, ExecutorService.class);
      initialize.setAccessible(true); initialize.invoke(null, prepared, worker);
      assertTrue("初始化错误应立即禁止继续业务", Bootstrap.businessStopped());
      assertFalse("等待落盘时不能开放恢复入口", Bootstrap.recoveryAvailable());
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
      Handler.class.getMethod("drain").invoke(null);
      assertEquals(1, closed.get());
      var persisted = new ActivationJournal(journalRoot).state();
      if (blockedWrite) {
        assertFalse("保存失败不能因 STABLE 阶段开放入口", Bootstrap.recoveryAvailable());
        assertEquals(manifest.snapshotId, persisted.stable);
        assertArrayEquals(before, Files.readAllBytes(new File(journalRoot, "activation.bin").toPath()));
        Bootstrap.componentFailed(new IllegalStateException("未就绪组件后续回调"));
        Handler.class.getMethod("drain").invoke(null);
        assertFalse("后续组件回调不能绕过未保存的恢复", Bootstrap.recoveryAvailable());
      } else {
        assertTrue(Bootstrap.recoveryAvailable()); assertEquals("", persisted.stable);
        assertTrue(persisted.quarantine.contains(manifest.snapshotId));
        assertEquals(9, persisted.revision); assertEquals(1, persisted.trustVersion);
      }
    } finally { worker.shutdownNow(); }
  }

  @Test public void stableInitializationFailureWaitsForActualPersistedRollback() throws Exception { checkRecovery(false); }
  @Test public void blockedRecoveryWriteKeepsEntryClosedAndStableBytesUntouched() throws Exception { checkRecovery(true); }

  @Test public void resourceGateDefaultsReadyButRefusesStoppedBusiness() throws Exception {
    var hooks = new ProcessHooks() {
      public void initialize() {}
      public void trimMemory(int level) {}
    };
    set(Bootstrap.class, null, "process", hooks);
    assertTrue(Bootstrap.resourcesReady());
    set(Bootstrap.class, null, "businessStopped", true);
    assertFalse(Bootstrap.resourcesReady());
  }

  @Test public void resourcesAwaitVisibleFrameWithoutStoppingServiceBusiness() throws Exception {
    var hooks = new ProcessHooks() {
      public void initialize() {}
      public void trimMemory(int level) {}
      public boolean resourcesReady() { return false; }
    };
    set(Bootstrap.class, null, "process", hooks);
    assertFalse(Bootstrap.resourcesReady());
    assertFalse(Bootstrap.businessStopped());
    AtomicInteger created = new AtomicInteger();
    assertTrue(Bootstrap.initialCreation(created::incrementAndGet));
    assertEquals(1, created.get());
  }

  @Test public void resourceProbeFailureStopsBusinessAndReturnsFalse() throws Exception {
    AtomicInteger closed = new AtomicInteger();
    set(Bootstrap.class, null, "process", new ProcessHooks() {
      public void initialize() {}
      public void trimMemory(int level) {}
      public boolean resourcesReady() { throw new IllegalStateException("资源测试故障"); }
      public void close() { closed.incrementAndGet(); }
    });
    assertFalse(Bootstrap.resourcesReady());
    assertTrue(Bootstrap.businessStopped());
    assertEquals(1, closed.get());
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!Bootstrap.recoveryAvailable() && System.nanoTime() < until) {
      Handler.class.getMethod("drain").invoke(null);
      Thread.yield();
    }
    assertTrue("无热更选择时仍需等后台恢复完成", Bootstrap.recoveryAvailable());
    assertFalse(Bootstrap.resourcesReady());
  }
}
