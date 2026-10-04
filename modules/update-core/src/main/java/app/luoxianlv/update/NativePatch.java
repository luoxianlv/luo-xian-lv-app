package app.luoxianlv.update;

final class NativePatch {
  static {
    System.loadLibrary("lxupdate_patch");
  }

  private NativePatch() {}

  static native int merge(
      int baseFd, int patchFd, int outputFd, long targetSize, long timeoutMillis, long jobToken);

  static native void cancel(long jobToken);

  static native void finish(long jobToken);
}
