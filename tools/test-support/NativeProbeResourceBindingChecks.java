package app.luoxianlv.host;

import app.luoxianlv.hot.contract.BusinessFactory;
import app.luoxianlv.hot.contract.OfficialResources;
import app.luoxianlv.hot.probe.HotProbeFactory;
import java.io.InputStream;

/** 使用实际已编译AppBusinessFactory/OfficialRendererGate，不用假的delegate代替绑定。 */
public final class NativeProbeResourceBindingChecks {
  public static void main(String[] args) throws Exception {
    Class<?> gate = Class.forName("app.luoxianlv.business.OfficialRendererGate");
    var source = gate.getDeclaredField("source"); source.setAccessible(true);
    Object instance = gate.getField("INSTANCE").get(null);
    Object owner = java.lang.reflect.Modifier.isStatic(source.getModifiers()) ? null : instance;
    if (source.get(owner) != null) throw new AssertionError("test JVM did not start with an unbound Gate");
    OfficialResources resources = new OfficialResources() {
      public String identity() { return "test-probe-binding"; }
      public boolean mounted(String mount) { return mount.equals("wallpaperengine"); }
      public String kind(String mount) { return mounted(mount) ? "resources" : ""; }
      public InputStream open(String mount, String relative) { throw new AssertionError("binding must not open files/render"); }
    };
    BusinessFactory factory = new HotProbeFactory();
    factory.bindResources(resources);
    if (source.get(owner) != resources || !Boolean.TRUE.equals(gate.getMethod("required").invoke(instance)))
      throw new AssertionError("Actual AppBusinessFactory did not bind the OfficialRendererGate resource scope");
    factory.bindResources(resources); // same-generation binding is idempotent, never another scope.
    System.out.println("PASS: actual HotProbeFactory delegates binding to compiled AppBusinessFactory/OfficialRendererGate");
  }
}
