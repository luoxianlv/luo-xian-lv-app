package app.luoxianlv.hot;

import android.os.Bundle;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 状态只复制基础值；拒绝跨代际对象、可执行 Parcelable、循环引用和无界视图状态。 */
public final class PageState {
  private static final int MAX_BYTES = 65536, MAX_ITEMS = 512, MAX_DEPTH = 8;
  private final Set<Bundle> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
  private int bytes, items;

  private PageState() {}

  public static Bundle copy(Bundle source) {
    return new PageState().bundle(source, 0);
  }

  private Bundle bundle(Bundle source, int depth) {
    StrictJson.require(
        source != null && depth <= MAX_DEPTH && visiting.add(source), "页面状态为空、过深或循环引用");
    Bundle result = new Bundle();
    try {
      for (String key : source.keySet()) {
        StrictJson.require(key != null && ++items <= MAX_ITEMS, "页面状态键无效或数量超限");
        charge(16L + key.length() * 2L);
        Object value = source.get(key);
        if (value instanceof String) {
          charge(((String) value).length() * 2L);
          result.putString(key, (String) value);
        } else if (value instanceof Integer) result.putInt(key, (Integer) value);
        else if (value instanceof Long) result.putLong(key, (Long) value);
        else if (value instanceof Boolean) result.putBoolean(key, (Boolean) value);
        else if (value instanceof Double && Double.isFinite((Double) value))
          result.putDouble(key, (Double) value);
        else if (value instanceof Float && Float.isFinite((Float) value))
          result.putFloat(key, (Float) value);
        else if (value instanceof Bundle) result.putBundle(key, bundle((Bundle) value, depth + 1));
        else if (value instanceof byte[]) {
          charge(((byte[]) value).length);
          result.putByteArray(key, ((byte[]) value).clone());
        } else if (value instanceof int[]) {
          charge(((int[]) value).length * 4L);
          result.putIntArray(key, ((int[]) value).clone());
        } else if (value instanceof long[]) {
          charge(((long[]) value).length * 8L);
          result.putLongArray(key, ((long[]) value).clone());
        } else if (value instanceof boolean[]) {
          charge(((boolean[]) value).length);
          result.putBooleanArray(key, ((boolean[]) value).clone());
        } else if (value instanceof String[]) {
          String[] strings = (String[]) value;
          charge(strings.length * 8L);
          for (String s : strings) {
            StrictJson.require(s != null, "页面状态数组含空值");
            charge(s.length() * 2L);
          }
          result.putStringArray(key, strings.clone());
        } else throw new IllegalArgumentException("页面状态只能包含受支持的基础值");
      }
      return result;
    } finally {
      visiting.remove(source);
    }
  }

  private void charge(long count) {
    StrictJson.require(count <= MAX_BYTES - bytes, "页面状态超过 64 KiB");
    bytes += (int) count;
  }
}
