package app.luoxianlv.hot;

import static org.junit.Assert.*;

import app.luoxianlv.hot.contract.WorkGate;
import org.junit.Test;

public final class WorkGateTest {
  @Test
  public void activeWorkCannotBeFrozenAndClosingLeaseTwiceNeverReleasesOtherWork()
      throws Exception {
    var gate = new WorkGate();
    var first = gate.acquire();
    var second = gate.acquire();
    assertFalse(gate.freeze());
    first.close();
    first.close();
    assertFalse(gate.freeze());
    second.close();
    assertTrue(gate.freeze());
    assertNull(gate.acquire());
    gate.resume();
    assertTrue(gate.idle());
    assertNotNull(gate.acquire());
  }

  @Test
  public void retirementWaitsForAcceptedWorkAndCannotBeUndone() throws Exception {
    var gate = new WorkGate();
    var task = gate.acquire();
    gate.retire();
    assertFalse(gate.released());
    assertNull(gate.acquire());
    assertThrows(IllegalStateException.class, gate::resume);
    task.close();
    assertTrue(gate.released());
  }

  @Test
  public void idleResourceCanHandoverButRetirementWaitsForItsPhysicalRelease() throws Exception {
    var gate = new WorkGate();
    var audio = gate.retain();
    assertTrue(gate.idle());
    assertTrue(gate.freeze());
    assertNull(gate.retain());
    gate.retire();
    assertFalse(gate.released());
    audio.close();
    audio.close();
    assertTrue(gate.released());
  }
}
