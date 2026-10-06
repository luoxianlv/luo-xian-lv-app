package app.luoxianlv.input;

/** 两种授权通道启动同一个已安装 APK；变量始终按 shell 字面量引用。 */
final class ShellHelperCommand {
  private ShellHelperCommand() {}

  static String build(String apk, String library, String pkg, int uid, String nonce,
      String suffix, boolean detached) {
    if (apk == null || !apk.startsWith("/") || library == null || !library.startsWith("/")
        || pkg == null || !pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")
        || uid % 100000 < 10000 || nonce == null || !nonce.matches("[0-9a-f]{64}")
        || !suffix.matches("[a-z_]+")) throw new IllegalArgumentException("助手启动参数无效");
    String process = "CLASSPATH=" + quote(apk) + " LD_LIBRARY_PATH=" + quote(library)
        + (detached ? " /system/bin/toybox nohup /system/bin/toybox setsid" : " exec")
        + " /system/bin/app_process /system/bin --nice-name=" + quote(pkg + ":" + suffix)
        + " app.luoxianlv.input.InputHelperMain " + quote(pkg) + " " + quote(nonce)
        + " " + quote(Integer.toString(uid));
    return detached ? "if ! /system/bin/toybox nohup /system/bin/toybox true >/dev/null 2>&1"
        + " || ! /system/bin/toybox setsid /system/bin/toybox true >/dev/null 2>&1;"
        + " then echo LXL_BOOTSTRAP_UNSUPPORTED; exit 1; fi; trap '' HUP; "
        + process + " </dev/null >/dev/null 2>&1 &" : process + "\n";
  }

  static String quote(String value) {
    if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("启动路径无效");
    return "'" + value.replace("'", "'\\''") + "'";
  }
}
