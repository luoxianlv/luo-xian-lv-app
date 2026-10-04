package app.luoxianlv.hot;

import static org.junit.Assert.*;

import app.luoxianlv.hot.contract.SharedUpdate;
import app.luoxianlv.update.Cancellation;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** 主线程状态读取用真实队列控制；不调用 Android UI 或伪造隔离进程成功。 */
public final class ApkUpdatePriorityTest {
  @Test
  public void atMostOneMainThreadProbeIsPendingAndIdleCanContinue() {
    var queue = new ArrayDeque<Runnable>();
    var token = new Cancellation();
    var reads = new AtomicInteger();
    var watch =
        new ApkUpdateBridge.PriorityWatch(
            queue::add,
            () -> {
              reads.incrementAndGet();
              return true;
            },
            token);
    for (int index = 0; index < 20; index++) watch.probe();
    assertEquals(1, queue.size());
    assertEquals(0, reads.get());
    queue.remove().run();
    assertEquals(1, reads.get());
    assertFalse(token.isCancelled());
    watch.probe();
    assertEquals(1, queue.size());
    queue.remove().run();
    assertEquals(2, reads.get());
    assertFalse(watch.deferred.get());
    watch.close();
  }

  @Test
  public void playbackBeginningCancelsTheSameTaskAndMarksDeferral() {
    var queue = new ArrayDeque<Runnable>();
    var token = new Cancellation();
    var idle = new AtomicBoolean(true);
    var watch = new ApkUpdateBridge.PriorityWatch(queue::add, idle::get, token);
    watch.probe();
    queue.remove().run();
    assertFalse(token.isCancelled());
    idle.set(false);
    watch.probe();
    queue.remove().run();
    assertTrue(token.isCancelled());
    assertTrue(watch.deferred.get());
    assertThrows(Cancellation.CancelledException.class, token::check);
    watch.probe();
    assertTrue(queue.isEmpty());
    watch.close();
  }

  @Test
  public void userCancellationDoesNotBecomePlaybackDeferral() {
    var queue = new ArrayDeque<Runnable>();
    var token = new Cancellation();
    var watch = new ApkUpdateBridge.PriorityWatch(queue::add, () -> false, token);
    watch.probe();
    token.cancel();
    queue.remove().run();
    assertTrue(token.isCancelled());
    assertFalse(watch.deferred.get());
    watch.close();
  }

  @Test
  public void lateMainCallbackAfterCloseCannotCancelCompletedWork() {
    var queue = new ArrayDeque<Runnable>();
    var token = new Cancellation();
    var reads = new AtomicInteger();
    var watch =
        new ApkUpdateBridge.PriorityWatch(
            queue::add,
            () -> {
              reads.incrementAndGet();
              return false;
            },
            token);
    watch.probe();
    watch.close();
    queue.remove().run();
    assertEquals(0, reads.get());
    assertFalse(token.isCancelled());
    assertFalse(watch.deferred.get());
    watch.probe();
    assertTrue(queue.isEmpty());
  }

  @Test
  public void unavailableMainProbeConservativelyPausesAndUsesDedicatedBusinessException() {
    var token = new Cancellation();
    var watch =
        new ApkUpdateBridge.PriorityWatch(
            task -> {
              throw new IllegalStateException("main unavailable");
            },
            () -> true,
            token);
    watch.probe();
    assertTrue(token.isCancelled());
    assertTrue(watch.deferred.get());
    watch.close();
    assertTrue(new SharedUpdate.DeferredException() instanceof java.io.IOException);
  }
}
