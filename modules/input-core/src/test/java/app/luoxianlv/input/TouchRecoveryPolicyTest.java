package app.luoxianlv.input;

import static org.junit.Assert.*;
import org.junit.Test;

public final class TouchRecoveryPolicyTest {
  @Test public void transientFailuresRetryOnlyWithinABoundedWindow() {
    assertEquals(100,TouchRecoveryPolicy.retryDelay("state-changed","",0));
    assertEquals(350,TouchRecoveryPolicy.retryDelay("stale-route","",1));
    assertEquals(1000,TouchRecoveryPolicy.retryDelay("","IOException",2));
    assertEquals(-1,TouchRecoveryPolicy.retryDelay("stale-route","",3));
  }

  @Test public void stableAmbiguityAndPermissionFailureDoNotPollForever() {
    assertEquals(-1,TouchRecoveryPolicy.retryDelay("ambiguous","",0));
    assertEquals(-1,TouchRecoveryPolicy.retryDelay("unknown-capabilities","",0));
    assertEquals(-1,TouchRecoveryPolicy.retryDelay("","SecurityException",0));
    assertEquals(-1,TouchRecoveryPolicy.retryDelay("android-host-touch","",0));
  }
}
