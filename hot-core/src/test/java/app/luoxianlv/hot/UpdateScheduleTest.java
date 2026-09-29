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
}
