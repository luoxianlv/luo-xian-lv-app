package app.luoxianlv.runtime.probe;

/** 仅显式 Debug 验收包提供，证明新共享运行时确实含有基线没有的类。 */
public final class NewRuntimeMarker {
  private NewRuntimeMarker() {}

  public static String identity() {
    return "new-shared-runtime";
  }
}
