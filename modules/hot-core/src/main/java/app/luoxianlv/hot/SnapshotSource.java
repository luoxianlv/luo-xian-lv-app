package app.luoxianlv.hot;

import java.io.OutputStream;
import java.util.Set;

/** 仅更新核心内部实现；候选在复制完成之前没有活动指针。 */
interface SnapshotSource {
  SignedSnapshot metadata();

  Set<String> included();

  String baseline();

  void copyObject(String hash, OutputStream destination) throws Exception;
}
