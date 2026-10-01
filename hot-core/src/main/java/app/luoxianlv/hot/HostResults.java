package app.luoxianlv.hot;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import app.luoxianlv.hot.contract.NativePage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** 系统结果跨重建按稳定业务键投递；只保存有界 URI、权限等基础值，不携带第三方对象。 */
final class HostResults {
  static final String PERMISSIONS = "androidx.activity.result.contract.extra.PERMISSIONS";
  static final String GRANTS = "androidx.activity.result.contract.extra.PERMISSION_GRANT_RESULTS";
  private static final int FIRST = 0x6000, LAST = 0xfffe, LIMIT = 32;
  private static final int URI_FLAGS =
      Intent.FLAG_GRANT_READ_URI_PERMISSION
          | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
          | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
          | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
  private final Activity activity;
  private final Map<Integer, Request> requests = new LinkedHashMap<>();
  private final Map<String, Bundle> pending = new LinkedHashMap<>();
  private int next = FIRST;

  private static final class Request {
    final String key;
    final boolean permission;

    Request(String key, boolean permission) {
      this.key = key;
      this.permission = permission;
    }
  }

  HostResults(Activity activity, Bundle state) {
    this.activity = activity;
    if (state == null) return;
    Bundle checked = PageState.copy(state);
    int[] codes = checked.getIntArray("codes");
    String[] keys = checked.getStringArray("keys");
    boolean[] permissions = checked.getBooleanArray("permissions");
    StrictJson.require(
        codes != null
            && keys != null
            && permissions != null
            && codes.length == keys.length
            && codes.length == permissions.length
            && codes.length <= LIMIT,
        "系统请求恢复结构无效");
    HashSet<String> seen = new HashSet<>();
    for (int i = 0; i < codes.length; i++) {
      checkKey(keys[i]);
      StrictJson.require(
          codes[i] >= FIRST
              && codes[i] <= LAST
              && !requests.containsKey(codes[i])
              && seen.add(keys[i]),
          "系统请求恢复序号重复或无效");
      requests.put(codes[i], new Request(keys[i], permissions[i]));
    }
    next = checked.getInt("next", FIRST);
    StrictJson.require(next >= FIRST && next <= LAST, "系统请求恢复游标无效");
    Bundle buffered = checked.getBundle("pending");
    if (buffered != null) {
      StrictJson.require(buffered.size() + requests.size() <= LIMIT, "系统请求恢复数量过多");
      for (String key : buffered.keySet()) {
        checkKey(key);
        StrictJson.require(seen.add(key), "系统请求恢复业务键重复");
        Bundle value = buffered.getBundle(key);
        validateResult(value);
        pending.put(key, value);
      }
    }
  }

  private int request(String key, boolean permission) {
    checkKey(key);
    StrictJson.require(
        requests.size() + pending.size() < LIMIT
            && !pending.containsKey(key)
            && requests.values().stream().noneMatch(value -> value.key.equals(key)),
        "该系统请求尚未结束或数量过多");
    // 序号环回前跳过仍在途的请求，不因长期运行而耗尽整个窗口。
    while (requests.containsKey(next)) next = following(next);
    int code = next;
    next = following(next);
    requests.put(code, new Request(key, permission));
    return code;
  }

  private static int following(int code) {
    return code == LAST ? FIRST : code + 1;
  }

  void launch(String key, Intent intent, Bundle options) {
    int code = request(key, false);
    try {
      activity.startActivityForResult(intent, code, options);
    } catch (RuntimeException failure) {
      requests.remove(code);
      throw failure;
    }
  }

  void permissions(String key, String[] permissions) {
    checkPermissions(permissions, null, false);
    int code = request(key, true);
    try {
      activity.requestPermissions(permissions.clone(), code);
    } catch (RuntimeException failure) {
      requests.remove(code);
      throw failure;
    }
  }

  void accept(int code, int resultCode, Intent data, NativePage page) {
    Request request = requests.get(code);
    if (request == null || request.permission) return;
    requests.remove(code);
    Bundle result = new Bundle();
    result.putInt("code", resultCode);
    result.putBoolean("data", data != null);
    try {
      if (data != null) {
        if (data.getData() != null) result.putString("uri", data.getData().toString());
        if (data.getType() != null) result.putString("type", data.getType());
        result.putInt("flags", data.getFlags() & URI_FLAGS);
        ClipData clip = data.getClipData();
        if (clip != null) {
          StrictJson.require(clip.getItemCount() <= LIMIT, "选择文件数量过多");
          ArrayList<String> uris = new ArrayList<>();
          for (int i = 0; i < clip.getItemCount(); i++)
            if (clip.getItemAt(i).getUri() != null) uris.add(clip.getItemAt(i).getUri().toString());
          result.putStringArray("uris", uris.toArray(new String[0]));
        }
      }
      buffer(request.key, result);
    } catch (RuntimeException failure) {
      invalid(request.key, page, failure);
    }
    deliver(request.key, page);
  }

  void acceptPermissions(int code, String[] permissions, int[] grants, NativePage page) {
    Request request = requests.get(code);
    if (request == null || !request.permission) return;
    requests.remove(code);
    try {
      checkPermissions(permissions, grants, true);
      Bundle result = new Bundle();
      result.putInt(
          "code", permissions.length == 0 ? Activity.RESULT_CANCELED : Activity.RESULT_OK);
      result.putBoolean("data", true);
      result.putStringArray("permissions", permissions.clone());
      result.putIntArray("grants", grants.clone());
      buffer(request.key, result);
    } catch (RuntimeException failure) {
      invalid(request.key, page, failure);
    }
    deliver(request.key, page);
  }

  private void buffer(String key, Bundle result) {
    validateResult(result);
    pending.put(key, result);
    try {
      save();
    } // 同时校验全部在途状态的 64 KiB 总预算。
    catch (RuntimeException failure) {
      pending.remove(key);
      throw failure;
    }
  }

  private void invalid(String key, NativePage page, Throwable failure) {
    Bundle cancelled = new Bundle();
    cancelled.putInt("code", Activity.RESULT_CANCELED);
    cancelled.putBoolean("data", false);
    pending.put(key, cancelled);
    android.util.Log.w("原生宿主", "系统选择结果超出允许范围，已取消本次操作");
    if (page != null) page.hostWarning("invalid_system_result", failure);
  }

  void deliver(String key, NativePage page) {
    checkKey(key);
    Bundle saved = pending.remove(key);
    if (saved == null) return;
    boolean consumed = false;
    try {
      if (page == null) return;
      Intent intent = null;
      if (saved.getBoolean("data")) {
        intent = new Intent();
        String uri = saved.getString("uri");
        intent.setDataAndType(uri == null ? null : Uri.parse(uri), saved.getString("type"));
        intent.setFlags(saved.getInt("flags"));
        String[] uris = saved.getStringArray("uris");
        if (uris != null && uris.length > 0) {
          ClipData clip = ClipData.newRawUri("文件", Uri.parse(uris[0]));
          for (int i = 1; i < uris.length; i++) clip.addItem(new ClipData.Item(Uri.parse(uris[i])));
          intent.setClipData(clip);
        }
        if (saved.containsKey("permissions")) {
          intent.putExtra(PERMISSIONS, saved.getStringArray("permissions"));
          intent.putExtra(GRANTS, saved.getIntArray("grants"));
        }
      }
      consumed = page.result(key, saved.getInt("code"), intent);
    } finally {
      if (!consumed) pending.put(key, saved);
    }
  }

  boolean busy() {
    return !requests.isEmpty() || !pending.isEmpty();
  }

  /** 新内容恢复时清除失效代际的缓冲；当前代际尚未注册的结果仍保留。 */
  void deliverAll(NativePage page) {
    for (String key : new ArrayList<>(pending.keySet())) deliver(key, page);
  }

  Bundle save() {
    Bundle state = new Bundle();
    int[] codes = new int[requests.size()];
    String[] keys = new String[requests.size()];
    boolean[] permissions = new boolean[requests.size()];
    int i = 0;
    for (Map.Entry<Integer, Request> entry : requests.entrySet()) {
      codes[i] = entry.getKey();
      keys[i] = entry.getValue().key;
      permissions[i++] = entry.getValue().permission;
    }
    state.putIntArray("codes", codes);
    state.putStringArray("keys", keys);
    state.putBooleanArray("permissions", permissions);
    state.putInt("next", next);
    Bundle values = new Bundle();
    for (Map.Entry<String, Bundle> entry : pending.entrySet())
      values.putBundle(entry.getKey(), entry.getValue());
    state.putBundle("pending", values);
    return PageState.copy(state);
  }

  private static void validateResult(Bundle value) {
    StrictJson.require(
        value != null
            && value.get("code") instanceof Integer
            && value.get("data") instanceof Boolean,
        "系统结果恢复结构无效");
    String uri = value.getString("uri"), type = value.getString("type");
    StrictJson.require(
        (uri == null || uri.length() <= 8192) && (type == null || type.length() <= 256),
        "系统结果文本过长");
    String[] uris = value.getStringArray("uris");
    if (uris != null) {
      StrictJson.require(uris.length <= LIMIT, "选择文件数量过多");
      for (String item : uris) StrictJson.require(item != null && item.length() <= 8192, "文件地址过长");
    }
    if (value.containsKey("permissions"))
      checkPermissions(value.getStringArray("permissions"), value.getIntArray("grants"), true);
    PageState.copy(value);
  }

  private static void checkPermissions(String[] permissions, int[] grants, boolean result) {
    StrictJson.require(
        permissions != null
            && permissions.length <= LIMIT
            && (result
                ? grants != null && grants.length == permissions.length
                : permissions.length > 0),
        "权限请求大小无效");
    HashSet<String> seen = new HashSet<>();
    for (String item : permissions)
      StrictJson.require(
          item != null && !item.isEmpty() && item.length() <= 256 && seen.add(item), "权限请求名称无效");
    if (grants != null)
      for (int grant : grants) StrictJson.require(grant == 0 || grant == -1, "权限结果值无效");
  }

  private static void checkKey(String key) {
    // 外层包含 32 位会话标识；业务自身的 96 字符限制由页面入口校验。
    StrictJson.require(key != null && key.matches("[a-z][a-z0-9._-]{0,159}"), "系统结果业务键无效");
  }
}
