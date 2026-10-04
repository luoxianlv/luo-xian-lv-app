package app.luoxianlv.update;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

public final class UpdateManifest {
  @FunctionalInterface
  public interface Verifier extends app.luoxianlv.update.Verifier {}

  public static final String ALGORITHM = "hdiff-w26-zstd-v1";
  public final String applicationId, environment, variant, issuedAt;
  public final Target target;
  public final List<Delta> deltas;

  private UpdateManifest(JSONObject object) throws org.json.JSONException {
    require(
        integer(object, "schema") == 1 && "apk-update".equals(object.getString("type")),
        "更新说明类型无效");
    applicationId = text(object, "applicationId");
    environment = text(object, "environment");
    variant = text(object, "variant");
    issuedAt = text(object, "issuedAt");
    require("release-universal".equals(variant), "不支持此安装类型");
    java.time.Instant.parse(issuedAt);
    target = new Target(object.getJSONObject("target"));
    JSONArray values = object.getJSONArray("deltas");
    require(values.length() <= 3, "增量基线过多");
    List<Delta> parsed = new ArrayList<>();
    for (int i = 0; i < values.length(); i++) {
      Delta delta = new Delta(values.getJSONObject(i));
      require(delta.base.versionCode < target.versionCode, "增量基线版本无效");
      for (Delta old : parsed) require(!old.base.sha256.equals(delta.base.sha256), "增量基线重复");
      require(
          delta.patch.object.equals(
              "luoxianlv/delta/"
                  + target.sha256
                  + "/"
                  + delta.base.sha256
                  + "/"
                  + delta.patch.sha256
                  + ".hpatch"),
          "补丁对象身份无效");
      parsed.add(delta);
    }
    deltas = Collections.unmodifiableList(parsed);
  }

  public static UpdateManifest authenticate(
      SignedDelivery delivery, app.luoxianlv.update.Verifier verifier) {
    Objects.requireNonNull(verifier);
    try {
      require(delivery != null && delivery.schema == 1, "更新说明封装无效");
      require(delivery.manifest != null && delivery.manifest.length() <= 192 * 1024, "更新说明过大");
      byte[] raw = Base64.getDecoder().decode(delivery.manifest);
      require(Base64.getEncoder().encodeToString(raw).equals(delivery.manifest), "更新说明编码无效");
      verifier.verify(delivery, raw);
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(raw))
              .toString();
      StrictJson.check(json);
      return new UpdateManifest(new JSONObject(json));
    } catch (SecurityException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new SecurityException("更新说明无效", failure);
    }
  }

  public static boolean worthwhile(long targetSize, long patchSize) {
    return targetSize > 0
        && patchSize > 0
        && patchSize <= targetSize - 65536
        && patchSize <= targetSize / 10 * 7 + (targetSize % 10) * 7 / 10;
  }

  private static String text(JSONObject object, String key) throws org.json.JSONException {
    Object raw = object.get(key);
    require(raw instanceof String, "更新说明文本字段无效：" + key);
    String value = (String) raw;
    require(!value.isEmpty() && value.length() <= 256, "更新说明字段无效：" + key);
    return value;
  }

  private static long integer(JSONObject object, String key) throws org.json.JSONException {
    Object value = object.get(key);
    require(value instanceof Number && value.toString().matches("[0-9]+"), "更新说明整数无效：" + key);
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException overflow) {
      throw new SecurityException("更新说明整数超出范围", overflow);
    }
  }

  static String digest(JSONObject object, String key) throws org.json.JSONException {
    String value = text(object, key);
    require(value.matches("[0-9a-f]{64}"), "文件摘要无效");
    return value;
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new SecurityException(message);
  }

  public static class Artifact {
    public final long size;
    public final String sha256;

    Artifact(JSONObject object) throws org.json.JSONException {
      size = integer(object, "size");
      sha256 = digest(object, "sha256");
      require(size > 0 && size <= 2L * 1024 * 1024 * 1024, "文件大小无效");
    }
  }

  public static class Base extends Artifact {
    public final long versionCode;

    Base(JSONObject object) throws org.json.JSONException {
      super(object);
      versionCode = integer(object, "versionCode");
      require(versionCode > 0, "版本无效");
    }
  }

  public static final class Target extends Base {
    public final String versionName, certificateSha256;

    Target(JSONObject object) throws org.json.JSONException {
      super(object);
      versionName = text(object, "versionName");
      certificateSha256 = digest(object, "certificateSha256");
    }
  }

  public static final class Patch extends Artifact {
    public final String algorithm, object;

    Patch(JSONObject value) throws org.json.JSONException {
      super(value);
      algorithm = text(value, "algorithm");
      object = text(value, "object");
    }
  }

  public static final class Delta {
    public final Base base;
    public final Patch patch;

    Delta(JSONObject object) throws org.json.JSONException {
      base = new Base(object.getJSONObject("base"));
      patch = new Patch(object.getJSONObject("patch"));
    }
  }
}
