package app.luoxianlv.input;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ActivationWaitTest {
  private InputUserService.ActivationWait request(long token) {
    return new InputUserService.ActivationWait(new float[] {100, 200}, 1000, token);
  }

  @Test public void noWaitDoesNotDelayAnAlreadyActiveSession() {
    var wait = request(1);
    assertTrue(wait.current(1));
    assertFalse(wait.waiting());
    assertEquals(0, wait.waitedMs(10000));
  }

  @Test public void heldFingerHasOneBoundedWaitWindow() {
    var wait = request(1);
    assertTrue(wait.retry(100));
    assertTrue(wait.waiting());
    for (long now = 125; now < 600; now += 25) assertTrue(wait.retry(now));
    assertFalse(wait.retry(600));
    assertFalse(wait.retry(625));
    assertEquals(500, wait.waitedMs(600));
  }

  @Test public void cancelCannotCaptureAfterTheFingerIsReleased() {
    var wait = request(1);
    assertTrue(wait.retry(100));
    wait.cancel();
    assertFalse(wait.current(1));
    assertFalse(wait.waiting());
    assertFalse(wait.retry(125));
    assertFalse(wait.retry(700));
    assertEquals(25, wait.waitedMs(125));
  }

  @Test public void replacementInvalidatesOldRetryWithoutCancellingNewRequest() {
    var old = request(1);
    assertTrue(old.retry(100));
    old.cancel();
    var next = request(2);
    assertFalse(old.current(2));
    assertFalse(old.retry(125));
    assertTrue(next.current(2));
    assertTrue(next.retry(125));
    assertEquals(0, next.waitedMs(125));
    assertTrue(next.retry(600));
    assertFalse(next.retry(625));
  }

  @Test public void aLaterPlaybackStartsAFreshWindowAfterTimeout() {
    var first = request(1);
    assertTrue(first.retry(100));
    assertFalse(first.retry(600));
    first.cancel();
    var later = request(2);
    assertTrue(later.current(2));
    assertTrue(later.retry(10000));
    assertTrue(later.retry(10499));
    assertFalse(later.retry(10500));
  }
}
