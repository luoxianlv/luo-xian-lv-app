package app.luoxianlv.hot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/** 安装级 API，不包含管理员接口；请求失败不发布、不绕过签名，也不把凭据写入错误。 */
public final class HotApiClient {
  public final InstallationIdentity installation;
  public final long hostContract;
  public final String hostIdentity;
  private final URI origin;
  private final boolean localTest;
  private boolean registered;

  public static final class Failure extends IOException {
    public final int status;
    public final String code;
    public final long retryAfterMillis;

    Failure(int status, String code, long retry) {
      super("热更服务请求失败：" + code + "（HTTP " + status + "）");
      this.status = status;
      this.code = code;
      retryAfterMillis = retry;
    }
  }

  static final class Response {
    final StrictJson.Obj value;
    final Instant time;

    Response(StrictJson.Obj value, Instant time) {
      this.value = value;
      this.time = time;
    }
  }

  public static final class Decision {
    public final String kind, snapshotId;
    public final long revision;
    public final Instant serverTime;
    public final byte[] trust, trustSignature;
    private final StrictJson.Obj value;

    Decision(Response response) {
      value =
          response.value.only(
              "decision",
              "channelRevision",
              "releaseId",
              "snapshotId",
              "candidate",
              "objects",
              "trustVersion",
              "trust",
              "trustSignature");
      kind = value.string("decision");
      revision = value.number("channelRevision");
      serverTime = response.time;
      StrictJson.require(
          (kind.equals("none")
                  || kind.equals("paused")
                  || kind.equals("candidate")
                  || kind.equals("recover"))
              && revision >= 0
              && revision <= 9007199254740991L,
          "发布决定格式无效");
      snapshotId = value.optionalString("snapshotId");
      StrictJson.require(
          value.has("trust") == value.has("trustSignature")
              && value.has("trust") == value.has("trustVersion"),
          "当前根授权不完整");
      trust = value.has("trust") ? bytes(value.string("trust")) : null;
      trustSignature = value.has("trust") ? bytes(value.string("trustSignature")) : null;
      if (hasCandidate())
        StrictJson.require(
            HotManifest.validHash(snapshotId) && revision > 0 && trust != null, "候选身份或授权缺失");
      else
        StrictJson.require(
            snapshotId.isEmpty() && !value.has("candidate") && !value.has("objects"),
            "无候选决定不能附带执行目标");
    }

    public boolean hasCandidate() {
      return kind.equals("candidate") || kind.equals("recover");
    }

    public SignedSnapshot verify(HotPackage.Policy policy) throws Exception {
      StrictJson.require(hasCandidate(), "当前没有候选");
      StrictJson.Obj signed =
          value
              .object("candidate")
              .only("manifest", "manifestSignature", "trust", "trustSignature");
      SignedSnapshot candidate =
          new SignedSnapshot(
              bytes(signed.string("manifest")),
              bytes(signed.string("manifestSignature")),
              bytes(signed.string("trust")),
              bytes(signed.string("trustSignature")),
              policy);
      StrictJson.require(
          snapshotId.equals(candidate.manifest.snapshotId)
              && value.number("trustVersion") == candidate.trust.version
              && value.object("objects").numbers().equals(candidate.manifest.objects),
          "下载目录与签名清单不一致");
      return candidate;
    }
  }

  public static final class PermitReply {
    public final byte[] permit, signature;
    public final Instant serverTime;

    PermitReply(Response response) {
      response.value.only("permit", "signature");
      permit = bytes(response.value.string("permit"));
      signature = bytes(response.value.string("signature"));
      serverTime = response.time;
    }
  }

  public HotApiClient(
      URI origin,
      InstallationIdentity installation,
      long hostContract,
      String hostIdentity,
      boolean localTest) {
    HttpObjectSource.validateUrl(origin, localTest);
    StrictJson.require(
        (origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
            && origin.getRawQuery() == null
            && hostContract > 0
            && hostContract <= Integer.MAX_VALUE
            && HotManifest.validHash(hostIdentity),
        "API 来源或宿主身份无效");
    this.origin = origin;
    this.installation = installation;
    this.hostContract = hostContract;
    this.hostIdentity = hostIdentity;
    this.localTest = localTest;
  }

  public static final class ReportReply {
    public final long revision;
    public final boolean autoPaused;
    public final Instant serverTime;

    ReportReply(Response response) {
      response.value.only("accepted", "autoPaused", "channelRevision");
      StrictJson.require(response.value.bool("accepted"), "健康事件未被接受");
      revision = response.value.number("channelRevision");
      autoPaused = response.value.bool("autoPaused");
      serverTime = response.time;
      StrictJson.require(revision >= 0 && revision <= 9007199254740991L, "健康回报修订无效");
    }
  }

  /** 关闭诊断时连安装登记也不触发；Debug 只允许显式测试启用。 */
  public ReportReply report(java.util.List<HealthEvent> events, boolean enabled) throws Exception {
    if (!enabled) return null;
    StrictJson.require(!events.isEmpty() && events.size() <= 100, "健康回报批次大小无效");
    java.util.List<Map<String, Object>> records = new java.util.ArrayList<>();
    for (HealthEvent event : events) records.add(event.fields());
    Map<String, Object> request = JsonWire.fields("events", records);
    String key = "events-" + HotSignatures.hash(JsonWire.encode(request));
    register();
    return new ReportReply(post("/events", request, key, true));
  }

  public synchronized void register() throws Exception {
    if (registered) return;
    Response response =
        post(
            "/installations",
            JsonWire.fields(
                "installationId",
                installation.id,
                "secret",
                installation.secret,
                "applicationId",
                installation.applicationId,
                "environment",
                installation.environment,
                "hostContract",
                hostContract,
                "hostIdentity",
                hostIdentity),
            "register-" + installation.id + "-" + hostIdentity.substring(0, 16),
            false);
    response.value.only("installationId", "credentialFormat", "scope");
    StrictJson.require(
        response.value.string("installationId").equals(installation.id)
            && response.value.string("scope").equals("installation"),
        "服务端安装身份不匹配");
    registered = true;
  }

  public Decision check(String currentSnapshot, long stateSchema) throws Exception {
    StrictJson.require(
        (currentSnapshot.isEmpty() || HotManifest.validHash(currentSnapshot))
            && stateSchema > 0
            && stateSchema <= Integer.MAX_VALUE,
        "当前快照或状态格式无效");
    register();
    return new Decision(
        post(
            "/check",
            JsonWire.fields(
                "currentSnapshotId", currentSnapshot, "currentStateSchema", stateSchema),
            "",
            true));
  }

  public PermitReply activate(ActivationPermit.Request request, long stateSchema) throws Exception {
    StrictJson.require(
        request.installationId.equals(installation.id)
            && request.hostIdentity.equals(hostIdentity)
            && request.hostContract == hostContract
            && stateSchema > 0
            && stateSchema <= Integer.MAX_VALUE,
        "许可请求不属于当前宿主");
    register();
    return new PermitReply(
        post(
            "/activate",
            JsonWire.fields(
                "snapshotId",
                request.manifest.snapshotId,
                "nonce",
                request.nonce,
                "channelRevision",
                request.revision,
                "currentStateSchema",
                stateSchema),
            "activate-" + request.nonce,
            true));
  }

  public HttpObjectSource object(SignedSnapshot candidate, String hash) {
    Long size = candidate.manifest.objects.get(hash);
    StrictJson.require(size != null && HotManifest.validHash(hash), "对象不属于签名清单");
    return new HttpObjectSource(
        origin.resolve(
            "/api/hot/v2/objects/" + hash + "?snapshotId=" + candidate.manifest.snapshotId),
        size,
        origin,
        installation.credential(),
        localTest);
  }

  Response post(String route, Map<String, ?> body, String idempotency, boolean authenticated)
      throws Exception {
    byte[] raw = JsonWire.encode(body);
    HttpURLConnection connection =
        HttpObjectSource.connect(origin.resolve("/api/hot/v2" + route), localTest);
    try {
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(15000);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(raw.length);
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      connection.setRequestProperty("Accept-Encoding", "identity");
      if (authenticated)
        connection.setRequestProperty("Authorization", "Bearer " + installation.credential());
      if (!idempotency.isEmpty()) connection.setRequestProperty("Idempotency-Key", idempotency);
      try (OutputStream output = connection.getOutputStream()) {
        output.write(raw);
      }
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        String code = "request_failed";
        try (InputStream input = connection.getErrorStream()) {
          if (input != null) {
            String parsed = StrictJson.envelope(read(input, 65536)).string("code");
            if (parsed.matches("[a-z_]{1,64}")) code = parsed;
          }
        } catch (Exception ignored) {
          /* 代理或网关可能不返回协议 JSON，保留安全的通用错误码。 */
        }
        throw new Failure(
            status, code, HttpObjectSource.retryAfter(connection.getHeaderField("Retry-After")));
      }
      String stamp = connection.getHeaderField("X-Lxhot-Time");
      StrictJson.require(stamp != null && stamp.matches("[0-9]{1,12}"), "API 未提供可验证来源的服务端时间");
      Instant serverTime = Instant.ofEpochSecond(Long.parseLong(stamp));
      try (InputStream input = connection.getInputStream()) {
        return new Response(StrictJson.envelope(read(input, 5 * StrictJson.MAX_BYTES)), serverTime);
      }
    } finally {
      connection.disconnect();
    }
  }

  private static byte[] read(InputStream input, int limit) throws Exception {
    long start = System.nanoTime();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = input.read(buffer)) != -1) {
      StrictJson.require(n > 0 && output.size() + n <= limit, "API 响应大小无效");
      if (System.nanoTime() - start > 30_000_000_000L)
        throw new java.net.SocketTimeoutException("API 响应等待超时");
      output.write(buffer, 0, n);
    }
    return output.toByteArray();
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
