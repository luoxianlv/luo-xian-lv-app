package app.luoxianlv.host.realtime;

import app.luoxianlv.hot.InstallationIdentity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** 宿主独立鉴权通道；业务运行时不可用时仍能恢复热更订阅。 */
public final class RealtimeApi {
  enum Stage {
    REGISTER("register"), TICKET("ticket"), ACCOUNT_GRANT("account-grant");
    final String label;
    Stage(String label) { this.label = label; }
  }
  enum FailureKind {
    HTTP("http"), TRANSPORT("transport"), PARSE("parse"), VALIDATION("validation");
    final String label;
    FailureKind(String label) { this.label = label; }
  }
  public static final class Failure extends IOException {
    final Stage stage;
    final FailureKind kind;
    /** 实际收到的 HTTP 状态；尚未收到响应时为 0。 */
    public final int status;
    public final long retryAfter;
    Failure(Stage stage, FailureKind kind, int status, long retryAfter, Exception cause) {
      super("实时授权请求失败", cause);
      this.stage = stage;
      this.kind = kind;
      this.status = status;
      this.retryAfter = retryAfter;
    }
  }
  private final URI origin;
  private final InstallationIdentity identity;
  private final long hostContract, versionCode;
  private final String hostIdentity;
  private volatile boolean registered;

  public static final class Request {
    private HttpURLConnection connection;
    private boolean cancelled;
    synchronized void attach(HttpURLConnection connection) throws IOException {
      if (cancelled) throw new IOException("实时请求已取消");
      this.connection = connection;
    }
    synchronized void detach(HttpURLConnection expected) {
      if (connection == expected) connection = null;
    }
    /** 迟到的取消只能关闭它自己的请求。 */
    public synchronized Runnable cancel() {
      cancelled = true;
      HttpURLConnection selected = connection;
      connection = null;
      return () -> { if (selected != null) selected.disconnect(); };
    }
  }

  public RealtimeApi(URI origin, InstallationIdentity identity, long hostContract,
      String hostIdentity, long versionCode, boolean localTest) {
    boolean local = localTest && "http".equals(origin.getScheme())
        && java.util.Set.of("127.0.0.1", "localhost", "[::1]").contains(origin.getHost());
    if ((!"https".equals(origin.getScheme()) && !local) || origin.getHost() == null
        || origin.getRawUserInfo() != null || origin.getRawQuery() != null
        || origin.getRawFragment() != null || !java.util.Set.of("", "/").contains(origin.getPath()))
      throw new IllegalArgumentException("实时服务地址无效");
    this.origin = origin;
    this.identity = identity;
    this.hostContract = hostContract;
    this.hostIdentity = hostIdentity;
    this.versionCode = versionCode;
  }

  public String installationId() { return identity.id; }
  public URI socketUri() {
    return URI.create(("https".equals(origin.getScheme()) ? "wss" : "ws")
        + "://" + origin.getRawAuthority() + "/api/realtime/v1/connect");
  }

  public String ticket(Request request) throws Exception {
    return ticket(request, false);
  }
  private String ticket(Request request, boolean retried) throws Exception {
    if (!registered) {
      byte[] body;
      try {
        body = identity.registration(hostContract, hostIdentity, versionCode);
      } catch (Exception invalid) {
        throw new Failure(Stage.REGISTER, FailureKind.VALIDATION, 0, 1000, invalid);
      }
      JSONObject response = post(request, "/installations", body, null, false);
      try {
        if (!identity.id.equals(response.getString("installationId")))
          throw new IOException("安装登记身份不一致");
      } catch (Exception invalid) {
        throw new Failure(Stage.REGISTER, FailureKind.VALIDATION, 200, 1000, invalid);
      }
      registered = true;
    }
    try {
      return checkedSecret(post(request, "/tickets", "{}".getBytes(StandardCharsets.UTF_8), null, true), "ticket", Stage.TICKET);
    } catch (Failure denied) {
      if (denied.status == 401 && !retried) {
        registered = false;
        return ticket(request, true);
      }
      throw denied;
    }
  }

  public String accountGrant(Request request, String accessToken, String epoch) throws Exception {
    byte[] body = new JSONObject().put("installationId", identity.id)
        .put("connectionEpoch", epoch).toString().getBytes(StandardCharsets.UTF_8);
    return checkedSecret(post(request, "/account-grants", body, accessToken, true), "grant", Stage.ACCOUNT_GRANT);
  }

  private String checkedSecret(JSONObject value, String name, Stage stage) throws Exception {
    try {
      String secret = value.getString(name);
      if (!secret.matches("[0-9a-f]{64}")) throw new IOException("连接授权资料无效");
      return secret;
    } catch (Exception invalid) {
      throw new Failure(stage, FailureKind.VALIDATION, 200, 1000, invalid);
    }
  }

  private Stage stage(String path) {
    return switch (path) {
      case "/installations" -> Stage.REGISTER;
      case "/tickets" -> Stage.TICKET;
      case "/account-grants" -> Stage.ACCOUNT_GRANT;
      default -> throw new IllegalArgumentException("实时授权路径无效");
    };
  }

  private JSONObject post(Request request, String path, byte[] body, String account, boolean authorize) throws Exception {
    Stage stage = stage(path);
    int status = 0;
    HttpURLConnection connection = null;
    try {
      if (Thread.currentThread().isInterrupted()) throw new IOException("实时请求已取消");
      connection = (HttpURLConnection) origin.resolve("/api/realtime/v1" + path).toURL().openConnection();
      request.attach(connection);
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(8000);
      connection.setReadTimeout(8000);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      connection.setRequestProperty("User-Agent", "Luoxianlv/" + versionCode);
      if (authorize) identity.authorize(connection, account == null ? "Authorization" : "X-Installation-Authorization");
      if (account != null) connection.setRequestProperty("Authorization", "Bearer " + account);
      connection.setFixedLengthStreamingMode(body.length);
      try (var output = connection.getOutputStream()) { output.write(body); }
      status = connection.getResponseCode();
      if (status != 200) {
        long retry = 0;
        try { retry = Math.min(3600, Math.max(0, Long.parseLong(connection.getHeaderField("Retry-After")))) * 1000; }
        catch (RuntimeException ignored) {}
        if (status == 404 || status == 503) retry = Math.max(retry, 60_000);
        throw new Failure(stage, FailureKind.HTTP, status, retry, null);
      }
      try (InputStream input = connection.getInputStream()) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count; (count = input.read(buffer)) != -1;) {
          if (output.size() + count > 65536) throw new IOException("实时响应过大");
          if (Thread.currentThread().isInterrupted()) throw new IOException("实时请求已取消");
          output.write(buffer, 0, count);
        }
        try {
          return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        } catch (Exception invalid) {
          throw new Failure(stage, FailureKind.PARSE, status, 1000, invalid);
        }
      }
    } catch (Failure failure) {
      throw failure;
    } catch (Exception failure) {
      throw new Failure(stage, FailureKind.TRANSPORT, status, 1000, failure);
    } finally {
      if (connection != null) {
        connection.disconnect();
        request.detach(connection);
      }
    }
  }

}
