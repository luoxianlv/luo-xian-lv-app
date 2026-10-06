package app.luoxianlv.input;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ShizukuConnectionPolicyTest {
  @Test public void coldStartCanUseTheServersFullThirtySecondWindow() {
    var policy = new ShizukuConnectionPolicy();
    long ticket = policy.start(100);
    assertFalse(policy.expired(ticket, 10100));
    assertFalse(policy.expired(ticket, 30100));
    assertFalse(policy.expired(ticket, 35099));
    assertTrue(policy.expired(ticket, 35100));
  }

  @Test public void onlyThreeAttemptsAreAllowedWithBoundedBackoff() {
    var policy = new ShizukuConnectionPolicy();
    long first = policy.start(100);
    assertEquals(1000, policy.failed(first, 35100, true));
    policy.invalidate(); // Controller 清理旧订阅，但不会重置重试预算。
    assertFalse(policy.mayStart(36099));
    assertTrue(policy.waitingToRetry(36099));
    long second = policy.start(36100);
    assertEquals(2000, policy.failed(second, 71100, true));
    policy.invalidate();
    assertEquals(0, policy.start(73099));
    long third = policy.start(73100);
    assertEquals(-1, policy.failed(third, 108100, true));
    policy.invalidate();
    assertFalse(policy.mayStart(200000));
    assertEquals(3, policy.attempts());
  }

  @Test public void connectedCallbackIsAcceptedOnceAndCancelsItsTimeout() {
    var policy = new ShizukuConnectionPolicy();
    long ticket = policy.start(100);
    assertTrue(policy.connected(ticket, 200));
    assertFalse(policy.connected(ticket, 201));
    assertFalse(policy.expired(ticket, 40000));
    assertFalse(policy.mayStart(40000));
  }

  @Test public void attachFailureAfterConnectedCanStillRetry() {
    var policy = new ShizukuConnectionPolicy();
    long ticket = policy.start(100);
    assertTrue(policy.connected(ticket, 200));
    assertEquals(1000, policy.failed(ticket, 201, true));
    policy.invalidate();
    assertTrue(policy.mayStart(1201));
  }

  @Test public void brieflyConnectedServiceDoesNotResetTheBudget() {
    var policy = new ShizukuConnectionPolicy();
    long first = policy.start(100);
    assertTrue(policy.connected(first, 200));
    policy.stable(30199);
    assertEquals(1, policy.attempts());
    policy.stable(30200);
    assertEquals(0, policy.attempts());
    assertEquals(1000, policy.failed(first, 30201, true));
    policy.invalidate();
    assertTrue(policy.mayStart(31201));
  }

  @Test public void cancellationRejectsLateConnectionAndCannotReopenItself() {
    var policy = new ShizukuConnectionPolicy();
    long old = policy.start(100);
    policy.cancel();
    assertFalse(policy.connected(old, 101));
    assertFalse(policy.expired(old, 50000));
    assertEquals(-1, policy.failed(old, 50000, true));
    assertFalse(policy.mayStart(100000));
    policy.reset(); // 只有用户连接、新 Binder 或授权变化才开启新预算。
    assertTrue(policy.mayStart(100000));
  }

  @Test public void oldCallbackCannotBreakTheNextAttempt() {
    var policy = new ShizukuConnectionPolicy();
    long old = policy.start(100);
    assertEquals(1000, policy.failed(old, 35100, true));
    policy.invalidate();
    long next = policy.start(36100);
    assertFalse(policy.connected(old, 36200));
    assertEquals(-1, policy.failed(old, 36200, true));
    assertTrue(policy.connected(next, 36300));
  }

  @Test public void permissionOrConfigurationFailureIsNotRetried() {
    var policy = new ShizukuConnectionPolicy();
    long first = policy.start(100);
    assertEquals(-1, policy.failed(first, 200, false));
    policy.invalidate();
    assertFalse(policy.mayStart(50000));
    assertEquals(1, policy.attempts());
  }

  @Test public void serviceTagsIsolateOldProcessRevisionAndAttemptCleanup() {
    String tag = ShizukuConnectionPolicy.serviceTag(10000, 100, 1, 1);
    assertEquals(tag, ShizukuConnectionPolicy.serviceTag(10000, 100, 1, 1));
    assertNotEquals(tag, ShizukuConnectionPolicy.serviceTag(10001, 100, 1, 1));
    assertNotEquals(tag, ShizukuConnectionPolicy.serviceTag(10000, 101, 1, 1));
    assertNotEquals(tag, ShizukuConnectionPolicy.serviceTag(10000, 100, 2, 1));
    assertNotEquals(tag, ShizukuConnectionPolicy.serviceTag(10000, 100, 1, 2));
  }
}
