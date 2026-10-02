package app.luoxianlv.hot;

import android.os.Bundle;

/** 页面会话的持久化格式；同一内容重建时保留系统结果代际，内容改变时清空状态。 */
final class PageSessionState {
  final Bundle page;
  final String generation;

  private PageSessionState(Bundle page, String generation) {
    this.page = page;
    this.generation = generation;
  }

  static PageSessionState restore(Bundle saved, String identity) {
    StrictJson.require(
        identity != null && !identity.isEmpty() && identity.length() <= 1024, "页面内容身份无效");
    if (!saved.containsKey("session.version")) return new PageSessionState(saved, null);
    StrictJson.require(saved.getInt("session.version") == 1, "页面会话版本不支持");
    if (!identity.equals(saved.getString("session.identity")))
      return new PageSessionState(new Bundle(), null);
    String generation = saved.getString("session.generation");
    StrictJson.require(generation != null && generation.matches("[0-9a-f]{32}"), "页面会话身份无效");
    Bundle page = saved.getBundle("session.page");
    StrictJson.require(page != null, "页面会话状态缺失");
    return new PageSessionState(page, generation);
  }

  static Bundle save(String identity, String generation, Bundle page) {
    Bundle state = new Bundle();
    state.putInt("session.version", 1);
    state.putString("session.identity", identity);
    state.putString("session.generation", generation);
    state.putBundle("session.page", page);
    return PageState.copy(state);
  }
}
