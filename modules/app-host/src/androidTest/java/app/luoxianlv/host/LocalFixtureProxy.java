package app.luoxianlv.host;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

/** 仅仪器进程的本机夹具直连；系统验证网络和其他域名仍使用原代理。 */
final class LocalFixtureProxy {
  static AutoCloseable direct() {
    ProxySelector previous = ProxySelector.getDefault();
    ProxySelector selected = new ProxySelector() {
      @Override public List<Proxy> select(URI uri) {
        String host = uri.getHost();
        if ("127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host))
          return List.of(Proxy.NO_PROXY);
        return previous == null ? List.of(Proxy.NO_PROXY) : previous.select(uri);
      }
      @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {
        if (previous != null) previous.connectFailed(uri, address, failure);
      }
    };
    ProxySelector.setDefault(selected);
    return () -> { if (ProxySelector.getDefault() == selected) ProxySelector.setDefault(previous); };
  }
}
