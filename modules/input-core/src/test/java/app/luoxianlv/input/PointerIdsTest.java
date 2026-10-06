package app.luoxianlv.input;

import static org.junit.Assert.*;
import org.junit.Test;

public class PointerIdsTest {
    @Test public void firstAutomaticPointerUsesZero() {
        MergedTouchDispatcher.PointerIds ids = new MergedTouchDispatcher.PointerIds();
        assertEquals(0, ids.allocate(10));
        assertEquals(0, ids.get(10));
        assertEquals(0, ids.allocate(10));
    }

    @Test public void newFingerKeepsAutomaticAndPhysicalIdsStable() {
        MergedTouchDispatcher.PointerIds ids = new MergedTouchDispatcher.PointerIds();
        assertEquals(0, ids.allocate(10));
        assertEquals(1, ids.allocate(0));
        assertEquals(0, ids.get(10));
        ids.release(10);
        assertEquals(1, ids.get(0));
        assertEquals(0, ids.allocate(11));
        assertEquals(1, ids.get(0));
        ids.release(11);
        assertEquals(0, ids.allocate(10));
        assertEquals(1, ids.get(0));
    }

    @Test public void simultaneousPointersHaveUniqueLowIds() {
        MergedTouchDispatcher.PointerIds ids = new MergedTouchDispatcher.PointerIds();
        for (int i = 0; i < 16; ++i) assertEquals(i, ids.allocate(31 - i));
        assertThrows(IllegalStateException.class, () -> ids.allocate(0));
        ids.release(25);
        assertEquals(6, ids.allocate(0));
        for (int i = 0; i < 16; ++i) if (31 - i != 25) assertEquals(i, ids.get(31 - i));
    }

    @Test public void cancelAndResetRestartAtZero() {
        MergedTouchDispatcher.PointerIds ids = new MergedTouchDispatcher.PointerIds();
        ids.allocate(10);
        ids.allocate(0);
        ids.clear();
        assertThrows(IllegalStateException.class, () -> ids.get(0));
        assertEquals(0, ids.allocate(31));
        ids.release(31);
        ids.release(31);
        assertEquals(0, ids.allocate(10));
    }

    @Test public void invalidLogicalIdsAreRejected() {
        MergedTouchDispatcher.PointerIds ids = new MergedTouchDispatcher.PointerIds();
        assertThrows(IllegalArgumentException.class, () -> ids.allocate(-1));
        assertThrows(IllegalArgumentException.class, () -> ids.allocate(32));
        assertThrows(IllegalArgumentException.class, () -> ids.release(32));
    }
}
