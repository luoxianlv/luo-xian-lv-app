package app.luoxianlv.input;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ShellHelperCommandTest {
  private static final String NONCE = "a".repeat(64);

  @Test public void bothChannelsUseTheInstalledHelperAndLiteralPaths() {
    String shell = ShellHelperCommand.build("/data/app/a'`x`$(id)/base.apk", "/data/app/lib",
        "app.luoxianlv.debug", 10123, NONCE, "touch_shell", false);
    assertTrue(shell.contains("'\\''`x`$(id)/base.apk'"));
    assertTrue(shell.contains(" exec /system/bin/app_process"));
    assertFalse(shell.contains("nohup"));
    String wireless = ShellHelperCommand.build("/data/app/base.apk", "/data/app/lib",
        "app.luoxianlv.debug", 10123, NONCE, "wireless_shell", true);
    assertTrue(wireless.contains("nohup /system/bin/toybox setsid"));
    assertTrue(wireless.endsWith("</dev/null >/dev/null 2>&1 &"));
    assertTrue(shell.contains("app.luoxianlv.input.InputHelperMain"));
    assertTrue(wireless.contains("app.luoxianlv.input.InputHelperMain"));
  }

  @Test public void malformedIdentityCannotBecomeShellSyntax() {
    assertThrows(IllegalArgumentException.class, () -> ShellHelperCommand.build("/base", "/lib",
        "app.luoxianlv;id", 10123, NONCE, "touch_shell", false));
    assertThrows(IllegalArgumentException.class, () -> ShellHelperCommand.build("/base", "/lib",
        "app.luoxianlv", 2000, NONCE, "touch_shell", false));
    assertThrows(IllegalArgumentException.class, () -> ShellHelperCommand.build("/base", "/lib",
        "app.luoxianlv", 10123, NONCE + "'", "touch_shell", false));
  }
}
