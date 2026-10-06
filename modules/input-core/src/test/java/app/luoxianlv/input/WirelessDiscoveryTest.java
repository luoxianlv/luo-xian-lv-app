package app.luoxianlv.input;

import static org.junit.Assert.*;
import java.net.InetAddress;
import org.junit.Test;

public class WirelessDiscoveryTest {
  @Test public void serviceTypeVariantsMatchRegisteredKind() {
    String expected = "_adb-tls-connect._tcp.";
    for (String type : new String[]{"_adb-tls-connect._tcp", "._adb-tls-connect._tcp",
        "_adb-tls-connect._tcp.local.", "._ADB-TLS-CONNECT._TCP.LOCAL.", " _adb-tls-connect._tcp. "})
      assertEquals(expected, WirelessDiscovery.normalize(type));
    assertEquals("_adb-tls-pairing._tcp.", WirelessDiscovery.normalize("._adb-tls-pairing._tcp.local."));
  }

  @Test public void unknownAndNullTypesCannotMatchAllowedServices() {
    assertEquals("", WirelessDiscovery.normalize(null));
    assertEquals("", WirelessDiscovery.normalize("..."));
    assertNotEquals("_adb-tls-connect._tcp.", WirelessDiscovery.normalize("_other._tcp.local."));
    assertNotEquals("_adb-tls-connect._tcp.", WirelessDiscovery.normalize("_adb-tls-connect._tcp.other."));
  }

  @Test public void localFilterRejectsWildcardMulticastAndOtherHosts() throws Exception {
    assertTrue(WirelessDiscovery.local(InetAddress.getByName("127.0.0.1")));
    assertTrue(WirelessDiscovery.local(InetAddress.getByName("::1")));
    assertFalse(WirelessDiscovery.local((InetAddress) null));
    assertFalse(WirelessDiscovery.local(InetAddress.getByName("0.0.0.0")));
    assertFalse(WirelessDiscovery.local(InetAddress.getByName("::")));
    assertFalse(WirelessDiscovery.local(InetAddress.getByName("224.0.0.251")));
    assertFalse(WirelessDiscovery.local(InetAddress.getByName("ff02::fb")));
    assertFalse(WirelessDiscovery.local(InetAddress.getByName("203.0.113.254")));
  }

  @Test public void mappedIpv4MatchesOnlyItsExactAddress() throws Exception {
    byte[] mapped = new byte[16];
    mapped[10] = mapped[11] = (byte) 255;
    mapped[12] = (byte) 192; mapped[13] = 0; mapped[14] = 2; mapped[15] = 7;
    InetAddress mappedAddress = java.net.Inet6Address.getByAddress(null, mapped, 0);
    assertTrue(WirelessDiscovery.sameAddress(mappedAddress, InetAddress.getByName("192.0.2.7")));
    assertFalse(WirelessDiscovery.sameAddress(mappedAddress, InetAddress.getByName("192.0.2.8")));
  }

  @Test public void endpointsTryLoopbackBeforeVerifiedLocalAddresses() {
    java.util.List<String> addresses = new java.util.ArrayList<>();
    addresses.add("192.0.2.7"); addresses.add("fe80::1%test");
    WirelessDiscovery.Endpoint endpoint = new WirelessDiscovery.Endpoint(44001, addresses);
    addresses.add("203.0.113.254");
    assertEquals(java.util.Arrays.asList("127.0.0.1", "::1", "192.0.2.7", "fe80::1%test"), endpoint.attempts());
    assertFalse(endpoint.hosts.contains("203.0.113.254"));
  }

  @Test public void failedOldPortCannotDiscardAnAlreadyUpdatedEndpoint() {
    WirelessDiscovery.Endpoint old = new WirelessDiscovery.Endpoint(33783, java.util.Arrays.asList("192.0.2.7"));
    WirelessDiscovery.Endpoint current = new WirelessDiscovery.Endpoint(46751, java.util.Arrays.asList("fe80::1%test"));
    assertSame(WirelessDiscovery.Endpoint.EMPTY, old.withoutFailedPort(33783));
    assertSame(current, current.withoutFailedPort(33783));
    assertEquals(java.util.Arrays.asList("127.0.0.1", "::1", "fe80::1%test"),
        current.withoutFailedPort(33783).attempts());
  }

  @Test public void staleBroadcastIsRejectedUntilFreshDiscoveryCanReplaceIt() {
    WirelessDiscovery.ConnectionFailures failures = new WirelessDiscovery.ConnectionFailures();
    failures.reject(33783, 1000);
    assertFalse(failures.accepts(33783, 1000));
    assertFalse(failures.accepts(33783, 30999));
    assertTrue(failures.accepts(46751, 2000));
    assertTrue(failures.accepts(33783, 31000));
    assertTrue(failures.accepts(33783, 31001));
  }

  @Test public void failedEndpointMemoryIsBoundedAndIgnoresInvalidPorts() {
    WirelessDiscovery.ConnectionFailures failures = new WirelessDiscovery.ConnectionFailures();
    failures.reject(0, 1000);
    failures.reject(65536, 1000);
    assertTrue(failures.accepts(0, 1001));
    assertTrue(failures.accepts(65536, 1001));
    for (int port = 44000; port < 44009; port++) failures.reject(port, 1000);
    assertTrue(failures.accepts(44000, 1001));
    assertFalse(failures.accepts(44001, 1001));
    assertFalse(failures.accepts(44008, 1001));
  }

  @Test public void authenticationErrorsNeverTriggerAddressFallback() {
    assertTrue(WirelessAdbBackend.localRouteFailure(new java.io.IOException(new java.net.ConnectException())));
    assertTrue(WirelessAdbBackend.localRouteFailure(new java.net.NoRouteToHostException()));
    assertFalse(WirelessAdbBackend.localRouteFailure(new javax.net.ssl.SSLHandshakeException("认证失败")));
    assertFalse(WirelessAdbBackend.localRouteFailure(new SecurityException()));
  }

  @Test public void retryableTransportErrorsExcludeTlsAndAuthorization() {
    assertTrue(WirelessAdbBackend.transientConnectionFailure(new java.io.IOException(new java.net.ConnectException())));
    assertTrue(WirelessAdbBackend.transientConnectionFailure(new java.net.SocketTimeoutException()));
    javax.net.ssl.SSLHandshakeException tls = new javax.net.ssl.SSLHandshakeException("授权失败");
    tls.initCause(new java.net.SocketTimeoutException());
    assertFalse(WirelessAdbBackend.transientConnectionFailure(tls));
    assertFalse(WirelessAdbBackend.transientConnectionFailure(new SecurityException()));
    assertFalse(WirelessAdbBackend.transientConnectionFailure(new IllegalStateException("后台组件不支持")));
  }
}
