package app.luoxianlv.hot.contract;

import android.os.Bundle;

/** 播放快照与命令只接受有界基础值；大谱面文本也不需要复制或解析业务对象。 */
public final class PlaybackValues {
  private PlaybackValues() {}

  public static Bundle copy(Bundle source) {
    return copy(source, 0, new long[] {16L * 1024 * 1024});
  }

  @SuppressWarnings("deprecation")
  private static Bundle copy(Bundle source, int depth, long[] remaining) {
    Bundle result = new Bundle();
    if (source == null) return result;
    if (depth > 3 || source.size() > 64) throw new IllegalArgumentException("播放消息结构过大");
    for (String key : source.keySet()) {
      if (key == null || key.length() > 128) throw new IllegalArgumentException("播放消息字段无效");
      remaining[0] -= key.length() * 2L + 16;
      Object value = source.get(key);
      if (value == null || value instanceof String) {
        remaining[0] -= value == null ? 0 : ((String) value).length() * 2L;
        result.putString(key, (String) value);
      } else if (value instanceof Boolean) result.putBoolean(key, (Boolean) value);
      else if (value instanceof Integer) result.putInt(key, (Integer) value);
      else if (value instanceof Long) result.putLong(key, (Long) value);
      else if (value instanceof Float && Float.isFinite((Float) value))
        result.putFloat(key, (Float) value);
      else if (value instanceof Double && Double.isFinite((Double) value))
        result.putDouble(key, (Double) value);
      else if (value instanceof Bundle)
        result.putBundle(key, copy((Bundle) value, depth + 1, remaining));
      else throw new IllegalArgumentException("播放消息包含非基础值：" + key);
      if (remaining[0] < 0) throw new IllegalArgumentException("播放消息超过大小限制");
    }
    return result;
  }
}
