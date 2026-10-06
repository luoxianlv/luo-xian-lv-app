package app.luoxianlv.input;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class BoundedInputRpcTest {
  @Test public void frozenCallsTimeOutWithoutAnUnboundedQueue() throws Exception {
    CountDownLatch thaw = new CountDownLatch(1), finished = new CountDownLatch(2);
    AtomicInteger active = new AtomicInteger();
    try (var rpc = new BoundedInputRpc(50)) {
      for (int i = 0; i < 2; i++) {
        try {
          rpc.call(() -> { active.incrementAndGet(); try { thaw.await(); return true; }
            finally { active.decrementAndGet(); finished.countDown(); } });
          fail("冻结调用不应成功");
        } catch (TimeoutException expected) { }
      }
      assertEquals(2, active.get());
      try { rpc.call(() -> true); fail("冻结时不应无限排队"); }
      catch (TimeoutException expected) { }
      assertEquals(2, active.get());
      thaw.countDown();
      assertTrue(finished.await(2, TimeUnit.SECONDS));
      assertTrue(rpc.call(() -> true));
    } finally { thaw.countDown(); }
  }

  @Test public void exceptionKeepsItsTypeAndClosedPoolRejectsCalls() throws Exception {
    var rpc = new BoundedInputRpc(1000);
    try {
      rpc.call(() -> { throw new SecurityException("拒绝"); }); fail();
    } catch (SecurityException expected) { assertEquals("拒绝", expected.getMessage()); }
    rpc.close();
    try { rpc.call(() -> true); fail(); } catch (RejectedExecutionException expected) { }
  }
}
