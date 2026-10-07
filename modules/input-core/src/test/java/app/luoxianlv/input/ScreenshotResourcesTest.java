package app.luoxianlv.input;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ScreenshotResourcesTest {
  @Test public void oldDeadlineCannotReleaseNewRequest() throws Exception {
    AtomicInteger oldClosed = new AtomicInteger(), nextClosed = new AtomicInteger();
    var old = new ScreenshotResources(); var next = new ScreenshotResources();
    old.track(oldClosed::incrementAndGet); next.track(nextClosed::incrementAndGet);
    old.close(); old.close();
    assertEquals(1, oldClosed.get()); assertEquals(0, nextClosed.get());
    next.close(); assertEquals(1, nextClosed.get());
  }
  @Test public void latePipeIsClosedAfterDeadline() {
    var resources = new ScreenshotResources(); resources.close();
    AtomicInteger closed = new AtomicInteger();
    assertThrows(IOException.class, () -> resources.track(closed::incrementAndGet));
    assertEquals(1, closed.get());
  }
  @Test public void fallbackReleasesFirstPipeAndOwnsReplacement() throws Exception {
    AtomicInteger raw = new AtomicInteger(), png = new AtomicInteger();
    var resources = new ScreenshotResources();
    resources.track(raw::incrementAndGet); resources.clear();
    resources.track(png::incrementAndGet); resources.close();
    assertEquals(1, raw.get()); assertEquals(1, png.get());
  }
}
