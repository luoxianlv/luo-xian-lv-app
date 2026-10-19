package app.luoxianlv.host.realtime;

import java.io.IOException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RealtimeDiagnosticsTest {
  @Test public void httpFailureKeepsItsActualStageStatusAndRetry() {
    var failure = new RealtimeApi.Failure(RealtimeApi.Stage.REGISTER,
        RealtimeApi.FailureKind.HTTP, 404, 60_000, null);
    String summary = HostRealtime.failureSummary(RealtimeApi.Stage.TICKET, failure.stage,
        failure.kind.label, failure.status, failure.retryAfter, failure);
    assertTrue(summary.contains("operation=ticket stage=register kind=http httpStatus=404"));
    assertTrue(summary.contains("retryMs=60000"));
  }

  @Test public void transportAndParseFailuresDoNotInventHttp503() {
    var transport = new RealtimeApi.Failure(RealtimeApi.Stage.TICKET,
        RealtimeApi.FailureKind.TRANSPORT, 0, 1000, new SSLHandshakeException("hidden-tls-detail"));
    String summary = HostRealtime.failureSummary(RealtimeApi.Stage.TICKET, transport.stage,
        transport.kind.label, transport.status, transport.retryAfter, transport);
    assertTrue(summary.contains("kind=transport httpStatus=0"));
    assertTrue(summary.contains("types=Failure->SSLHandshakeException"));
    assertFalse(summary.contains("503"));
    assertFalse(summary.contains("hidden-tls-detail"));

    var parse = new RealtimeApi.Failure(RealtimeApi.Stage.ACCOUNT_GRANT,
        RealtimeApi.FailureKind.PARSE, 200, 1000, new IllegalArgumentException("hidden-response"));
    summary = HostRealtime.failureSummary(RealtimeApi.Stage.ACCOUNT_GRANT, parse.stage,
        parse.kind.label, parse.status, parse.retryAfter, parse);
    assertTrue(summary.contains("stage=account-grant kind=parse httpStatus=200"));
    assertFalse(summary.contains("hidden-response"));
  }

  @Test public void diagnosticsBoundTheCauseChainAndExcludeAllMessages() {
    Throwable cause = new IOException("hidden-cause");
    for (int index = 0; index < 10; index++) cause = new IOException("hidden-message", cause);
    String summary = HostRealtime.failureSummary(RealtimeApi.Stage.TICKET,
        RealtimeApi.Stage.TICKET, "transport", 0, 1000, cause);
    assertTrue(summary.contains("types=IOException->IOException->IOException->IOException retryMs="));
    assertEquals(3, summary.split("->", -1).length - 1);
    assertFalse(summary.contains("hidden"));
  }
}
