package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ForegroundStopperTest {
  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(3, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  @Test public void blockingStopDoesNotBlockCallerAndCompletesOnMain() throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var main = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
    var completed = new AtomicInteger();
    Thread caller = Thread.currentThread();
    try {
      var stopper = new ForegroundStopper(worker, main::add, id -> {
        assertNotSame(caller, Thread.currentThread());
        entered.countDown(); await(release); return id == 7;
      }, completed::set);
      stopper.stop(7);
      await(entered);
      assertEquals(0, completed.get());
      release.countDown();
      Runnable finish = main.poll(3, TimeUnit.SECONDS);
      assertNotNull(finish); finish.run();
      assertEquals(7, completed.get());
      assertFalse(stopper.cancel());
    } finally {
      release.countDown(); worker.shutdownNow();
    }
  }

  @Test public void duplicateStopAndZeroStartIdDoNotFloodWorker() {
    Queue<Runnable> worker = new ArrayDeque<>(), main = new ArrayDeque<>();
    var calls = new AtomicInteger();
    var completed = new AtomicInteger();
    var stopper = new ForegroundStopper(worker::add, main::add, id -> {
      calls.incrementAndGet(); return true;
    }, completed::set);
    stopper.stop(0);
    for (int i = 0; i < 100; i++) stopper.stop(7);
    assertEquals(1, worker.size());
    worker.remove().run(); main.remove().run();
    assertEquals(1, calls.get()); assertEquals(7, completed.get());
  }

  @Test public void restartCancelsQueuedStopBeforeAnyBinderCall() {
    Queue<Runnable> worker = new ArrayDeque<>();
    var calls = new AtomicInteger();
    var stopper = new ForegroundStopper(worker::add, Runnable::run, id -> {
      calls.incrementAndGet(); return true;
    }, id -> fail("旧停止结果不应生效"));
    stopper.stop(7); assertTrue(stopper.cancel());
    worker.remove().run(); assertEquals(0, calls.get());
  }

  @Test public void restartDuringBinderWaitDropsLateSuccessfulResult() throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var main = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
    var completed = new AtomicInteger();
    try {
      var stopper = new ForegroundStopper(worker, main::add, id -> {
        entered.countDown(); await(release); return true;
      }, completed::set);
      stopper.stop(7); await(entered);
      assertTrue(stopper.cancel()); release.countDown();
      Runnable finish = main.poll(3, TimeUnit.SECONDS);
      assertNotNull(finish); finish.run(); assertEquals(0, completed.get());
    } finally {
      release.countDown(); worker.shutdownNow();
    }
  }

  @Test public void newerStopSupersedesEarlierPendingResult() {
    Queue<Runnable> worker = new ArrayDeque<>(), main = new ArrayDeque<>();
    var completed = new AtomicInteger();
    var stopper = new ForegroundStopper(worker::add, main::add, id -> true, completed::set);
    stopper.stop(7); worker.remove().run();
    stopper.stop(8); worker.remove().run();
    main.remove().run(); assertEquals(0, completed.get());
    main.remove().run(); assertEquals(8, completed.get());
  }

  @Test public void systemRejectsObsoleteStartIdWithoutStoppingForegroundAndAllowsRetry() {
    Queue<Runnable> worker = new ArrayDeque<>(), main = new ArrayDeque<>();
    var completed = new AtomicInteger();
    var stopper = new ForegroundStopper(worker::add, main::add, id -> id == 8, completed::set);
    stopper.stop(7); worker.remove().run(); main.remove().run();
    assertEquals(0, completed.get()); assertFalse(stopper.cancel());
    stopper.stop(8); worker.remove().run(); main.remove().run();
    assertEquals(8, completed.get());
  }
}
