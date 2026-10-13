package app.luoxianlv.host.realtime;

import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RealtimeRequestTest {
  private static final class Connection extends HttpURLConnection {
    boolean disconnected;
    Connection() throws Exception { super(new URL("https://example.invalid")); }
    @Override public void disconnect() { disconnected = true; }
    @Override public void connect() {}
    @Override public boolean usingProxy() { return false; }
  }
  @Test public void delayedCancellationOnlyClosesTheOriginalRequest() throws Exception {
    var first = new RealtimeApi.Request();
    var firstConnection = new Connection();
    first.attach(firstConnection);
    Runnable cancellation = first.cancel();
    first.detach(firstConnection);
    var second = new RealtimeApi.Request();
    var secondConnection = new Connection();
    second.attach(secondConnection);
    cancellation.run();
    assertTrue(firstConnection.disconnected);
    assertFalse(secondConnection.disconnected);
  }
  @Test public void cancelledRequestCannotAttachAnotherConnection() throws Exception {
    var request = new RealtimeApi.Request();
    request.cancel().run();
    var connection = new Connection();
    assertThrows(java.io.IOException.class, () -> request.attach(connection));
  }
}
