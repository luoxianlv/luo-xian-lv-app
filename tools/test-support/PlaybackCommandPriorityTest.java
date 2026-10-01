package app.luoxianlv.hot;

import static org.junit.Assert.*;

import android.os.Handler;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import sun.misc.Unsafe;

/** 独立runner执行真实Binding.command；平台loop与业务输入是替身，不调用Android服务。 */
public final class PlaybackCommandPriorityTest {
  public static final class Service extends NativeAccessibilityService {
    List<String> events;
    boolean preparing;

    @Override
    protected app.luoxianlv.hot.contract.NativePlaybackSession createPlaybackSession() {
      return null;
    }

    @Override
    protected void foregroundRequested(boolean enabled) {}

    @Override
    protected void playbackUsageChanged() {
      assertTrue("必须先执行业务状态变更", preparing);
      events.add("priority");
    }
  }

  private static void set(Class<?> type, Object owner, String name, Object value) throws Exception {
    var field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static Service service() throws Exception {
    var field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    Service service = (Service) ((Unsafe) field.get(null)).allocateInstance(Service.class);
    service.events = new ArrayList<>();
    set(
        NativeAccessibilityService.class,
        service,
        "main",
        new Handler(android.os.Looper.getMainLooper()));
    return service;
  }

  @Before
  public void reset() throws Exception {
    Handler.class.getMethod("reset").invoke(null);
    android.os.Looper.class.getMethod("setMain", boolean.class).invoke(null, true);
  }

  private NativeAccessibilityService.Binding binding(Service service) throws Exception {
    var binding = service.new Binding();
    binding.enabled = true;
    binding.session =
        (app.luoxianlv.hot.contract.NativePlaybackSession)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {app.luoxianlv.hot.contract.NativePlaybackSession.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("command")) {
                    service.events.add("command");
                    service.preparing = true;
                  }
                  return null;
                });
    set(NativeAccessibilityService.class, service, "binding", binding);
    set(NativeAccessibilityService.class, service, "connected", true);
    return binding;
  }

  @Test
  public void immediateCommandReportsPriorityAfterStateMutationWithoutPolling() throws Exception {
    var service = service();
    var binding = binding(service);
    binding.command("play", null);
    assertEquals(List.of("command", "priority"), service.events);
  }

  @Test
  public void queuedCommandReportsOnlyWhenActuallyExecutedOnMain() throws Exception {
    var service = service();
    var binding = binding(service);
    android.os.Looper.class.getMethod("setMain", boolean.class).invoke(null, false);
    binding.command("play", null);
    assertTrue(service.events.isEmpty());
    android.os.Looper.class.getMethod("setMain", boolean.class).invoke(null, true);
    Handler.class.getMethod("drain").invoke(null);
    assertEquals(List.of("command", "priority"), service.events);
  }

  @Test
  public void retiredQueuedCommandCannotNotifyNewGeneration() throws Exception {
    var service = service();
    var binding = binding(service);
    android.os.Looper.class.getMethod("setMain", boolean.class).invoke(null, false);
    binding.command("play", null);
    binding.activationEpoch++;
    android.os.Looper.class.getMethod("setMain", boolean.class).invoke(null, true);
    Handler.class.getMethod("drain").invoke(null);
    assertTrue(service.events.isEmpty());
  }

  @Test
  public void disabledBindingDoesNotRunBusinessOrPriorityCallback() throws Exception {
    var service = service();
    var binding = binding(service);
    binding.enabled = false;
    binding.command("play", null);
    assertTrue(service.events.isEmpty());
  }
}
