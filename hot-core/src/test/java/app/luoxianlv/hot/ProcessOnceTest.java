package app.luoxianlv.hot;

import static org.junit.Assert.*;

import app.luoxianlv.hot.contract.ProcessOnce;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** 稳定进程任务跨代际共享成功状态，不让失败、并发或重入误记完成。 */
public final class ProcessOnceTest {
  @Test
  public void failureCanRetryAndOnlySuccessfulCompletionIsShared() {
    String key = key();
    var calls = new AtomicInteger();
    assertThrows(
        IllegalStateException.class,
        () ->
            ProcessOnce.run(
                key,
                () -> {
                  calls.incrementAndGet();
                  throw new IllegalStateException("测试初始化失败");
                }));
    assertNull(ProcessOnce.completedAt(key));
    long completed = ProcessOnce.run(key, calls::incrementAndGet);
    assertEquals(Long.valueOf(completed), ProcessOnce.completedAt(key));
    assertEquals(completed, ProcessOnce.run(key, calls::incrementAndGet));
    assertEquals(2, calls.get());
  }

  @Test
  public void concurrentBusinessCallersWaitForTheSameSuccessfulInitialization() throws Exception {
    String key = key();
    var calls = new AtomicInteger();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var secondEntered = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  ProcessOnce.run(
                      key,
                      () -> {
                        calls.incrementAndGet();
                        started.countDown();
                        await(release);
                      }));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      var second =
          executor.submit(
              () -> {
                secondEntered.countDown();
                return ProcessOnce.run(key, calls::incrementAndGet);
              });
      assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
      assertNull(ProcessOnce.completedAt(key));
      assertFalse(second.isDone());
      release.countDown();
      assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
      assertEquals(1, calls.get());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  public void unrelatedTaskCanCompleteWhileAnotherTaskIsInitializing() throws Exception {
    String firstKey = key(), secondKey = key();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  ProcessOnce.run(
                      firstKey,
                      () -> {
                        started.countDown();
                        await(release);
                      }));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      var second = executor.submit(() -> ProcessOnce.run(secondKey, () -> {}));
      assertEquals(second.get(5, TimeUnit.SECONDS), ProcessOnce.completedAt(secondKey));
      assertFalse(first.isDone());
      release.countDown();
      first.get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  public void recursiveInitializationFailsWithoutPoisoningTheTask() {
    String key = key();
    assertThrows(
        IllegalStateException.class,
        () -> ProcessOnce.run(key, () -> ProcessOnce.run(key, () -> {})));
    assertNull(ProcessOnce.completedAt(key));
    ProcessOnce.run(key, () -> {});
    assertNotNull(ProcessOnce.completedAt(key));
  }

  @Test
  public void legacyClaimStillOnlyAcceptsItsFirstCaller() {
    String key = key();
    assertTrue(ProcessOnce.claim(key));
    assertFalse(ProcessOnce.claim(key));
    assertNotNull(ProcessOnce.completedAt(key));
  }

  @Test
  public void newBusinessLoaderReadsSdkStateWithoutOpeningItsReportingGate() throws Exception {
    String key = key();
    var calls = new AtomicInteger();
    Class<?> oldBusiness = newBusinessClass(), nextBusiness = newBusinessClass();
    assertNotSame(oldBusiness, nextBusiness);
    assertNotSame(oldBusiness.getClassLoader(), nextBusiness.getClassLoader());
    var arguments = new Class<?>[] {String.class, boolean.class, AtomicInteger.class};
    assertEquals(
        0L, oldBusiness.getMethod("initialize", arguments).invoke(null, key, false, calls));
    assertNull(ProcessOnce.completedAt(key));
    Object completed =
        oldBusiness.getMethod("initialize", arguments).invoke(null, key, true, calls);
    assertEquals(completed, nextBusiness.getMethod("completedAt", String.class).invoke(null, key));
    assertEquals(false, nextBusiness.getMethod("canReport").invoke(null));
    assertEquals(
        completed, nextBusiness.getMethod("initialize", arguments).invoke(null, key, true, calls));
    assertEquals(true, nextBusiness.getMethod("canReport").invoke(null));
    assertEquals(1, calls.get());
  }

  /** 只把测试业务类重新定义，稳定 ProcessOnce 始终由公共父加载器提供。 */
  private static Class<?> newBusinessClass() throws Exception {
    String name = BusinessCaller.class.getName();
    byte[] bytecode;
    try (var input =
        ProcessOnceTest.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
      bytecode = input.readAllBytes();
    }
    var loader =
        new ClassLoader(ProcessOnceTest.class.getClassLoader()) {
          @Override
          protected Class<?> loadClass(String requested, boolean resolve)
              throws ClassNotFoundException {
            if (!requested.equals(name)) return super.loadClass(requested, resolve);
            Class<?> loaded = findLoadedClass(requested);
            if (loaded == null) loaded = defineClass(requested, bytecode, 0, bytecode.length);
            if (resolve) resolveClass(loaded);
            return loaded;
          }
        };
    return loader.loadClass(name);
  }

  public static final class BusinessCaller {
    private static boolean reportingEnabled;

    public static long initialize(String key, boolean consent, AtomicInteger sdkCalls) {
      if (!consent) return 0;
      long completed = ProcessOnce.run(key, sdkCalls::incrementAndGet);
      reportingEnabled = true;
      return completed;
    }

    public static Long completedAt(String key) {
      return ProcessOnce.completedAt(key);
    }

    public static boolean canReport() {
      return reportingEnabled;
    }
  }

  private static String key() {
    return "process-test." + UUID.randomUUID();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("测试初始化等待超时");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
