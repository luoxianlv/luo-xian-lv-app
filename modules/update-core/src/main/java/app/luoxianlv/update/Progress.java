package app.luoxianlv.update;

@FunctionalInterface
public interface Progress {
  /** completed/total 表示当前步骤，downloaded 是本次任务实际收到的网络字节。 */
  void onProgress(String phase, long completed, long total, long downloaded, String detail);

  Progress NONE = (phase, completed, total, downloaded, detail) -> {};
}
