package app.luoxianlv.hot.contract;

import android.os.Bundle;

/** 页面与播放服务只交换基础值，不跨业务加载器传递 Song、协程或界面对象。 */
public interface PlaybackPort {
  Bundle query(String kind);

  void command(String action, Bundle arguments);
}
