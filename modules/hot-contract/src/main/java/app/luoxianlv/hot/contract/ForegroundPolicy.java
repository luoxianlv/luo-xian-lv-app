package app.luoxianlv.hot.contract;

/** 仅在宿主已登记前台后调用；实现不能等待下载、谱面解析或其他耗时准备。 */
public interface ForegroundPolicy {
  boolean shouldRun();

  void stopPlayback();
}
