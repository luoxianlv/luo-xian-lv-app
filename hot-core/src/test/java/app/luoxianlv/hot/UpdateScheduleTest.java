package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class UpdateScheduleTest {
  @Test
  public void foregroundNetworkAndPlaybackRequestsCoalesceWithoutPollingInBackground() {
    AtomicLong clock = new AtomicLong();
    UpdateSchedule schedule = new UpdateSchedule(clock::get, () -> 0.5);
    assertEquals(-1, schedule.delayMillis());
    schedule.availability(true, false, true, false);
    assertTrue(schedule.beginIfDue());
    schedule.request();
    schedule.request();
    assertFalse(schedule.beginIfDue());
    assertEquals(-1, schedule.delayMillis());
    schedule.finish(true, 0);
    assertEquals(30000, schedule.delayMillis());
    schedule.availability(false, false, true, false);
    clock.set(300000);
    assertEquals(-1, schedule.delayMillis());
    schedule.availability(false, true, true, true);
    assertFalse(schedule.beginIfDue());
    schedule.availability(false, true, true, false);
    assertTrue(schedule.beginIfDue());
  }

  @Test
  public void failuresBackOffAndRecoveryNeverCreatesARequestStorm() {
    AtomicLong clock = new AtomicLong();
    UpdateSchedule schedule = new UpdateSchedule(clock::get, () -> 0.5);
    schedule.availability(true, false, true, false);
    for (long wait : new long[] {60000, 120000, 240000, 480000, 960000, 1800000, 1800000}) {
      assertTrue(schedule.beginIfDue());
      schedule.request();
      schedule.finish(false, 0);
      assertEquals(wait, schedule.delayMillis());
      clock.addAndGet(wait);
    }
    assertTrue(schedule.beginIfDue());
    schedule.finish(true, 120000);
    assertEquals(120000, schedule.delayMillis());
    schedule.availability(true, false, false, false);
    schedule.availability(true, false, true, false);
    assertEquals(120000, schedule.delayMillis());
    schedule.request();
    assertFalse(schedule.beginIfDue());
  }

  @Test public void foregroundAndNetworkTriggersRespectFailureBackoffWithoutRetryAfter() {
    AtomicLong clock = new AtomicLong();
    UpdateSchedule schedule = new UpdateSchedule(clock::get, () -> .5);
    schedule.availability(true, false, true, false);
    assertTrue(schedule.beginIfDue());
    schedule.finish(false, 0);
    clock.set(1000);
    schedule.availability(false, false, false, false);
    schedule.availability(true, false, true, false);
    schedule.request();
    assertEquals(59000, schedule.delayMillis());
    assertFalse(schedule.beginFollowupIfAllowed());
    clock.set(60000);
    assertTrue(schedule.beginIfDue());
    schedule.finish(false, 0);
    assertEquals(120000, schedule.delayMillis());
  }

  @Test public void jitterBoundsAndNormalPlaybackRemainEligible() {
    for (double sample : new double[] {0, 1}) {
      AtomicLong clock = new AtomicLong();
      UpdateSchedule schedule = new UpdateSchedule(clock::get, () -> sample);
      schedule.availability(false, true, true, false);
      assertTrue(schedule.beginIfDue());
      schedule.finish(true, 0);
      assertEquals(sample == 0 ? 48000 : 72000, schedule.delayMillis());
      schedule.availability(false, true, true, true);
      assertEquals(-1, schedule.delayMillis());
      schedule.availability(false, true, true, false);
      assertEquals(sample == 0 ? 48000 : 72000, schedule.delayMillis());
    }
  }

  @Test public void immediateCandidateFollowupIsSerializedAndCannotBypassServerDelay() {
    AtomicLong clock = new AtomicLong();
    UpdateSchedule schedule = new UpdateSchedule(clock::get, () -> .5);
    schedule.availability(true, false, true, false);
    assertTrue(schedule.beginIfDue());
    assertFalse(schedule.beginFollowupIfAllowed());
    schedule.finish(true, 0);
    assertEquals(60000, schedule.delayMillis());
    assertTrue(schedule.beginFollowupIfAllowed());
    assertFalse(schedule.beginIfDue());
    schedule.finish(false, 120000);
    schedule.request();
    assertFalse(schedule.beginFollowupIfAllowed());
    clock.set(120000);
    assertTrue(schedule.beginFollowupIfAllowed());
  }

  @Test public void cancellationDoesNotIncreaseFailuresAndDeferredWorkWaitsNormally() {
    AtomicLong clock = new AtomicLong();
    UpdateSchedule schedule = new UpdateSchedule(clock::get, () -> .5);
    schedule.availability(true, false, true, false);
    assertTrue(schedule.beginIfDue());
    schedule.availability(true, false, true, true);
    schedule.cancelled();
    assertEquals(-1, schedule.delayMillis());
    schedule.availability(true, false, true, false);
    assertEquals(30000, schedule.delayMillis());
    assertFalse(schedule.beginFollowupIfAllowed());
    clock.set(30000);
    assertTrue(schedule.beginIfDue());
    schedule.finish(false, 0);
    assertEquals(60000, schedule.delayMillis());
    clock.addAndGet(60000);
    assertTrue(schedule.beginIfDue());
    schedule.deferred();
    assertEquals(60000, schedule.delayMillis());
    assertFalse(schedule.beginFollowupIfAllowed());
  }
}
