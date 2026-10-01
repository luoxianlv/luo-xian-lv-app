package app.luoxianlv.hot;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 薄宿主请求编码，只支持协议基础类型；不对已签名文档重新编码。 */
final class JsonWire {
  static Map<String, Object> fields(Object... pairs) {
    StrictJson.require(pairs.length % 2 == 0, "请求字段缺少值");
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      StrictJson.require(
          pairs[i] instanceof String && pairs[i + 1] != null && !result.containsKey(pairs[i]),
          "请求字段重复或为空");
      result.put((String) pairs[i], pairs[i + 1]);
    }
    return result;
  }

  static byte[] encode(Map<String, ?> value) {
    StringBuilder out = new StringBuilder();
    append(out, value, 0);
    byte[] raw = out.toString().getBytes(StandardCharsets.UTF_8);
    StrictJson.require(raw.length <= StrictJson.MAX_BYTES, "请求内容过大");
    return raw;
  }

  private static void append(StringBuilder out, Object value, int depth) {
    StrictJson.require(depth <= 32 && out.length() <= StrictJson.MAX_BYTES, "请求嵌套或大小超限");
    if (value instanceof String) quote(out, (String) value);
    else if (value instanceof Boolean || value instanceof Integer || value instanceof Long)
      out.append(value);
    else if (value instanceof Map) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        StrictJson.require(entry.getKey() instanceof String, "请求键必须是字符串");
        if (!first) out.append(',');
        first = false;
        quote(out, (String) entry.getKey());
        out.append(':');
        append(out, entry.getValue(), depth + 1);
      }
      out.append('}');
    } else if (value instanceof Iterable) {
      out.append('[');
      boolean first = true;
      for (Object item : (Iterable<?>) value) {
        if (!first) out.append(',');
        first = false;
        append(out, item, depth + 1);
      }
      out.append(']');
    } else throw new IllegalArgumentException("请求只接受协议基础值");
  }

  private static void quote(StringBuilder out, String value) {
    StrictJson.require(value.length() <= StrictJson.MAX_BYTES, "请求字符串过大");
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') out.append('\\').append(c);
      else if (c < 32)
        out.append("\\u00")
            .append("0123456789abcdef".charAt(c >> 4))
            .append("0123456789abcdef".charAt(c & 15));
      else if (Character.isHighSurrogate(c)) {
        StrictJson.require(
            i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)),
            "请求包含孤立 Unicode 代理项");
        out.append(c).append(value.charAt(++i));
      } else {
        StrictJson.require(!Character.isLowSurrogate(c), "请求包含孤立 Unicode 代理项");
        out.append(c);
      }
    }
    out.append('"');
  }
}
