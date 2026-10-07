package app.luoxianlv.input;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class CaptureTicketTest {
  @Test public void deliveredBufferMovesToCaller() throws Exception {
    AtomicInteger closed = new AtomicInteger();
    AutoCloseable buffer = closed::incrementAndGet;
    var ticket = new CaptureTicket<AutoCloseable>();
    ticket.offer(buffer);
    assertSame(buffer, ticket.await(10));
    ticket.close();
    assertEquals(0, closed.get());
    buffer.close();
    assertEquals(1, closed.get());
  }
  @Test public void timeoutDisposesLateAndDuplicateBuffers() throws Exception {
    AtomicInteger closed = new AtomicInteger();
    var ticket = new CaptureTicket<AutoCloseable>();
    assertNull(ticket.await(1));
    ticket.offer(closed::incrementAndGet);
    ticket.offer(closed::incrementAndGet);
    assertEquals(2, closed.get());
  }
  @Test public void closingBeforeDeliveryReleasesOwnedBuffer() {
    AtomicInteger closed = new AtomicInteger();
    var ticket = new CaptureTicket<AutoCloseable>();
    ticket.offer(closed::incrementAndGet);
    ticket.close(); ticket.close();
    assertEquals(1, closed.get());
  }
  @Test public void duplicateDeliveryCannotReplaceFirst() throws Exception {
    AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
    AutoCloseable chosen = first::incrementAndGet;
    var ticket = new CaptureTicket<AutoCloseable>();
    ticket.offer(chosen); ticket.offer(second::incrementAndGet);
    assertSame(chosen, ticket.await(10));
    assertEquals(0, first.get()); assertEquals(1, second.get());
    chosen.close();
  }
}
