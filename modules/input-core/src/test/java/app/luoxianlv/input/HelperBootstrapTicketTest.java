package app.luoxianlv.input;

import static org.junit.Assert.*;
import org.junit.Test;

public final class HelperBootstrapTicketTest {
  @Test public void eachConnectionHasItsOwnOneTimeTicket() {
    var first = new HelperBootstrapTicket(100, 10000);
    var second = new HelperBootstrapTicket(100, 10000);
    assertNotEquals(first.nonce, second.nonce);
    assertFalse(first.accept(second.nonce, 101));
    assertFalse(first.accept(null, 101));
    assertFalse(first.accept("0", 101));
    assertTrue(first.accept(first.nonce, 101));
    assertFalse(first.accept(first.nonce, 102));
    assertTrue(second.accept(second.nonce, 102));
  }

  @Test public void cancelThenReconnectRejectsLateDelivery() {
    var cancelled = new HelperBootstrapTicket(100, 10000);
    cancelled.revoke();
    var current = new HelperBootstrapTicket(200, 10000);
    assertFalse(cancelled.accept(cancelled.nonce, 201));
    assertFalse(current.accept(cancelled.nonce, 201));
    assertTrue(current.accept(current.nonce, 201));
  }

  @Test public void startupDeadlineDoesNotExpireAnAcceptedConnection() {
    var expired = new HelperBootstrapTicket(100, 10000);
    assertTrue(expired.expired(10100));
    assertFalse(expired.accept(expired.nonce, 10100));
    var connected = new HelperBootstrapTicket(100, 10000);
    assertTrue(connected.accept(connected.nonce, 10099));
    assertFalse(connected.expired(20000));
    connected.revoke();
    assertTrue(connected.revoked());
  }
}
