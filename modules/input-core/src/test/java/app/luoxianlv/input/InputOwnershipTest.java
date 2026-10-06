package app.luoxianlv.input;

import static org.junit.Assert.*;
import org.junit.Test;

public final class InputOwnershipTest {
  @Test public void oldLeaseCannotStealOrReleaseNewOwner() {
    InputOwnership<Object> state = new InputOwnership<>();
    Object old = new Object(), next = new Object();
    assertNull(state.owner()); // 打开候选会话不捕获。
    assertTrue(state.claim(old));
    assertFalse(state.claim(next));
    assertTrue(state.releasing(old, 11));
    assertFalse(state.claim(next)); // 排队释放不算退出。
    assertNull(state.acknowledge(10, false));
    assertNull(state.acknowledge(11, true));
    assertSame(old, state.owner());
    assertSame(old, state.acknowledge(11, false));
    assertTrue(state.claim(next));
    assertFalse(state.releasing(old, 12));
    assertNull(state.acknowledge(11, false));
    assertSame(next, state.owner());
  }

  @Test public void sameLeaseCannotStartWhileItsReleaseIsPending() {
    InputOwnership<Object> state = new InputOwnership<>();
    Object owner = new Object();
    assertTrue(state.claim(owner));
    assertTrue(state.claim(owner));
    assertTrue(state.releasing(owner, 3));
    assertFalse(state.claim(owner));
    assertSame(owner, state.acknowledge(3, false));
    assertTrue(state.claim(owner));
    assertSame(owner, state.clear()); // 仅已确认助手死亡时走无票据清理。
    assertNull(state.owner());
  }
}
