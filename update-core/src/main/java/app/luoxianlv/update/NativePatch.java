package app.luoxianlv.update;

final class NativePatch {
  static { System.loadLibrary("lxupdate_patch"); }
  private NativePatch() {}
  static native int merge(int baseFd, int patchFd, int outputFd, long targetSize, long timeoutMillis);
  static native void cancel();
}
