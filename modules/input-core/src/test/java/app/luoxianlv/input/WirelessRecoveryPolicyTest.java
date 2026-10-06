package app.luoxianlv.input;

import static org.junit.Assert.*;
import org.junit.Test;

public class WirelessRecoveryPolicyTest {
  @Test public void authenticatedBinderDoesNotRequireStartupChannelOrWifi() {
    Session session = new Session();
    session.startupChannel = false;
    session.wifi = false;
    assertTrue(session.binderAlive);
    assertTrue(session.wanted);
    assertFalse(session.needsRecovery());
  }

  @Test public void binderDeathCanRecoverOnlyThreeTimesWithoutForegroundStartup() {
    Session session = new Session();
    session.binderAlive = false;
    for (int attempt = 0; attempt < 3; attempt++) {
      assertTrue(session.needsRecovery());
      session.attempts++;
    }
    assertFalse(session.needsRecovery());
  }

  @Test public void cancellationAndRevokedPairingCannotRestartAutomatically() {
    assertFalse(WirelessRecoveryPolicy.mayRecover(false, true, 0));
    assertFalse(WirelessRecoveryPolicy.mayRecover(true, false, 0));
    assertFalse(WirelessRecoveryPolicy.afterForegroundCancel(true, false));
  }

  @Test public void endingPairingForegroundSessionKeepsEstablishedBinder() {
    assertTrue(WirelessRecoveryPolicy.afterForegroundCancel(true, true));
  }

  private static final class Session {
    boolean binderAlive = true, startupChannel = true, wifi = true, wanted = true, paired = true;
    int attempts;
    boolean needsRecovery() {
      return !binderAlive && WirelessRecoveryPolicy.mayRecover(wanted, paired, attempts);
    }
  }
}
