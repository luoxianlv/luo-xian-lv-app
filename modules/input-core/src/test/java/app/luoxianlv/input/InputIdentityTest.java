package app.luoxianlv.input;

import static org.junit.Assert.*;
import org.junit.Test;

public final class InputIdentityTest {
  @Test public void officialShizukuIdentitiesAreAccepted() {
    assertTrue(InputIdentity.privileged(2000));
    assertTrue(InputIdentity.privileged(0));
  }
  @Test public void otherSystemAndApplicationIdentitiesAreRejected() {
    for (int uid : new int[] {-1, 1000, 10000, 102000, 200000})
      assertFalse(InputIdentity.privileged(uid));
  }
  @Test public void helperMustMatchSelectedServer() {
    assertTrue(InputIdentity.matches(2000, 2000));
    assertTrue(InputIdentity.matches(0, 0));
    assertFalse(InputIdentity.matches(2000, 0));
    assertFalse(InputIdentity.matches(0, 2000));
    assertFalse(InputIdentity.matches(-1, -1));
    assertFalse(InputIdentity.matches(10000, 10000));
  }
}
