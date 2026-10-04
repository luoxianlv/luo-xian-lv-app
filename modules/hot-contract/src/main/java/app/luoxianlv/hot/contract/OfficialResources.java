package app.luoxianlv.hot.contract;

import java.io.IOException;
import java.io.InputStream;

/** 按完整快照绑定的只读官方资源；不暴露用户项目目录或写接口。 */
public interface OfficialResources {
  String SERVICE = "app.luoxianlv.hot.official-resources";
  String identity();
  boolean mounted(String mount);
  String kind(String mount);
  InputStream open(String mount, String relative) throws IOException;
}
