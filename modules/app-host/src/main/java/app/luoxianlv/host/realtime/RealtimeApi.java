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
  private final android.content.SharedPreferences cache;
  private final Object issuance = new Object();
  private String accessToken = "";
  private String scope = "";
  private long generation;

  /** 返回账号边界是否变化；刷新同账号的登录 token 不打断当前演奏。 */
  public synchronized boolean login(String token) {
    String subject = "";
    if (!token.isEmpty()) {
      try {
        String[] parts = token.split("\\.", -1);
        if (parts.length == 3) {
          subject = new JSONObject(new String(android.util.Base64.decode(parts[1],
              android.util.Base64.URL_SAFE), StandardCharsets.UTF_8)).getString("sub");
        }
      } catch (Exception invalid) { /* 只有服务端验签成功才能签发。 */ }
    }
    String next = subject.isEmpty() ? "" : origin + "|" + identity.id + "|" + subject;
    boolean changed = !next.equals(scope);
    if (changed) generation++;
    scope = next;
    accessToken = subject.isEmpty() ? "" : token;
    if (!next.equals(cache.getString("scope", ""))) {
      cache.edit().clear().putString("scope", next).apply();
    }
    return changed;
  }

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

  public RealtimeApi(android.content.SharedPreferences cache, URI origin, InstallationIdentity identity, long hostContract,
      String hostIdentity, long versionCode, boolean localTest) {
    boolean local = localTest && "http".equals(origin.getScheme())
        && java.util.Set.of("127.0.0.1", "localhost", "[::1]").contains(origin.getHost());
    if ((!"https".equals(origin.getScheme()) && !local) || origin.getHost() == null
        || origin.getRawUserInfo() != null || origin.getRawQuery() != null
        || origin.getRawFragment() != null || !java.util.Set.of("", "/").contains(origin.getPath()))
      throw new IllegalArgumentException("实时服务地址无效");
    this.cache = cache;
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

  private static Failure noLogin() {
    return new Failure(Stage.REGISTER, FailureKind.HTTP, 401, 0, null);
  }

  private String credential(Request request, long expected) throws Exception {
    synchronized (issuance) {
      String pending, token, expectedScope;
      long now = System.currentTimeMillis();
      synchronized (this) {
        if (generation != expected || accessToken.isEmpty()) throw noLogin();
        String current = cache.getString("credential", "");
        long expiry = cache.getLong("expires", 0);
        long retry = cache.getLong("retry", 0) - now;
        if (!current.isEmpty() && expiry > now && (expiry - now > 600_000 || retry > 0))
          return current;
        if (retry > 0) throw new Failure(Stage.REGISTER, FailureKind.HTTP, 429, retry, null);
        token = accessToken;
        expectedScope = scope;
        pending = cache.getString("pending", "");
        if (pending.isEmpty()) {
          pending = java.util.UUID.randomUUID().toString();
          if (!cache.edit().putString("pending", pending).commit())
            throw new IOException("无法保存 WS 申请编号");
        }
      }
      try {
        JSONObject result = post(request, "/installations",
            identity.registration(hostContract, hostIdentity, versionCode), token, false,
            "Idempotency-Key", pending);
        String value = checkedSecret(result, "credential", Stage.REGISTER);
        long seconds = result.getLong("expiresIn");
        if (seconds < 1 || seconds > 3600 || !identity.id.equals(result.getString("installationId"))
            || !expectedScope.equals(origin + "|" + identity.id + "|" + result.getString("accountId")))
          throw new Failure(Stage.REGISTER, FailureKind.VALIDATION, 200, 1000, null);
        synchronized (this) {
          if (generation != expected) throw noLogin();
          if (!cache.edit().putString("credential", value).putLong("expires", now + seconds * 1000)
              .remove("pending").remove("retry").commit()) throw new IOException("无法保存 WS 凭证");
        }
        return value;
      } catch (Failure failure) {
        synchronized (this) {
          if (generation != expected) throw noLogin();
          if (failure.status == 429)
            cache.edit().putLong("retry", System.currentTimeMillis() + failure.retryAfter).apply();
          if ((failure.status == 429 || failure.status >= 500 || failure.kind == FailureKind.TRANSPORT)
              && cache.getLong("expires", 0) > System.currentTimeMillis()) {
            String valid = cache.getString("credential", "");
            if (!valid.isEmpty()) return valid;
          }
        }
        throw failure;
      }
    }
  }

  private synchronized long loginGeneration() throws Failure {
    if (accessToken.isEmpty()) throw noLogin();
    return generation;
  }

  public String ticket(Request request) throws Exception {
    long expected = loginGeneration();
    for (int attempt = 0; ; attempt++) {
      String value = credential(request, expected);
      String login;
      synchronized (this) {
        if (expected != generation || accessToken.isEmpty()) throw noLogin();
        login = accessToken;
      }
      try {
        return checkedSecret(post(request, "/tickets", "{}".getBytes(StandardCharsets.UTF_8),
            login, true, "X-WS-Credential", value), "ticket", Stage.TICKET);
      } catch (Failure failure) {
        if (failure.status != 410 || attempt != 0) throw failure;
        synchronized (this) {
          if (expected != generation) throw noLogin();
          if (value.equals(cache.getString("credential", "")))
            cache.edit().remove("credential").remove("expires").apply();
        }
      }
    }
  }

  public String accountGrant(Request request, String token, String epoch) throws Exception {
    long expected = loginGeneration();
    credential(request, expected); // 活跃连接也续期；无需重新握手。
    JSONObject body = new JSONObject().put("installationId", identity.id)
        .put("connectionEpoch", epoch);
    return checkedSecret(post(request, "/account-grants", body.toString().getBytes(StandardCharsets.UTF_8),
        token, true, null, null), "grant", Stage.ACCOUNT_GRANT);
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

  private JSONObject post(Request request, String path, byte[] body, String account, boolean authorize, String header, String value) throws Exception {
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
      if (header != null) connection.setRequestProperty(header, value);
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
