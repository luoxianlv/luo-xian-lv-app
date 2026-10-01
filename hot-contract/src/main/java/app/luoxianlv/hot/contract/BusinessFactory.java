package app.luoxianlv.hot.contract;

import android.content.Context;

/** 完整业务包的唯一入口；系统组件名由宿主固定，内部页面和实现可随业务包更新。 */
public interface BusinessFactory {
  /** 宿主在创建任何业务对象前绑定一次本代只读范围。 */
  default void bindResources(OfficialResources resources) {}

  NativePage page(String route);

  NativePlaybackSession playback();

  ProcessHooks process(Context context);

  ForegroundPolicy foreground(Context context);
}
