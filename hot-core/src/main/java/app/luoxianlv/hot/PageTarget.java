package app.luoxianlv.hot;

import android.content.Context;
import app.luoxianlv.hot.contract.NativePage;

/** 路由与内容身份绑定；恢复时重新创建对应页面，不能把所有入口降成 main。 */
public interface PageTarget {
  String identity();

  Context context(Context owner);

  NativePage create() throws Exception;
}
