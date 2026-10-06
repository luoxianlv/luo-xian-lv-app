package app.luoxianlv.input;

/** Shizuku 官方支持 shell 和 root；连接的助手还必须与所选服务身份一致。 */
final class InputIdentity {
  static boolean privileged(int uid) { return uid == 2000 || uid == 0; }
  static boolean matches(int expected, int actual) { return privileged(expected) && expected == actual; }
  private InputIdentity() {}
}
