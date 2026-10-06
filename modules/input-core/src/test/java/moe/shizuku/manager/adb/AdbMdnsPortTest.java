package moe.shizuku.manager.adb;

import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.Test;
import static org.junit.Assert.*;

/** 验证上游的本机地址与端口策略；不启动 Android NSD 或任何设备上的无线调试。 */
public final class AdbMdnsPortTest {
  @Test public void pairingAndConnectionAreDistinctTlsServiceTypes() {
    assertEquals("_adb-tls-pairing._tcp", AdbMdns.TLS_PAIRING);
    assertEquals("_adb-tls-connect._tcp", AdbMdns.TLS_CONNECT);
  }

  @Test public void loopbackAddressMustBelongToThisMachine() throws Exception {
    assertTrue(AdbMdns.isLocalHost(InetAddress.getByName("127.0.0.1")));
  }

  @Test public void unrelatedAndMissingAddressesAreRejected() throws Exception {
    assertFalse(AdbMdns.isLocalHost(InetAddress.getByName("192.0.2.123")));
    assertFalse(AdbMdns.isLocalHost(null));
  }

  @Test public void anAlreadyListeningLocalPortPassesTheUpstreamProbe() throws Exception {
    try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      assertTrue(AdbMdns.isPortAvailable(listening.getLocalPort()));
    }
  }

  @Test public void aFreelyBindablePortDoesNotRepresentAnAdbService() throws Exception {
    int port;
    try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      port = reservation.getLocalPort();
    }
    assertFalse(AdbMdns.isPortAvailable(port));
  }

  @Test public void portZeroCannotBeMistakenForAResolvedAdbEndpoint() {
    assertFalse(AdbMdns.isPortAvailable(0));
  }
}
