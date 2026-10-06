package app.luoxianlv.input;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/** app_process 入口只启动 shell 输入服务；宿主租约失效时归还触屏并退出。 */
public final class InputHelperMain {
  private InputHelperMain() { }

  public static void main(String[] arguments) {
    if (Process.myUid() != 2000 || arguments.length != 3
        || !arguments[0].matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")
        || !arguments[1].matches("[0-9a-f]{64}") || !arguments[2].matches("[0-9]{5,10}")) {
      Log.e("落弦律输入助手", "助手启动失败：阶段=入口身份；类型=SecurityException");
      System.exit(1);
      return;
    }
    InputUserService service = null;
    String stage = "初始化系统上下文";
    try {
      if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
      Class<?> activityThread = Class.forName("android.app.ActivityThread");
      Method systemMain = activityThread.getDeclaredMethod("systemMain");
      Object thread = systemMain.invoke(null);
      Context system = (Context) activityThread.getDeclaredMethod("getSystemContext").invoke(thread);
      stage = "创建 shell 上下文";
      Context shellPackage = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
      if (shellPackage.getApplicationInfo().uid != Process.myUid())
        throw new SecurityException("系统 shell 身份不匹配");
      // createPackageContext 继承调用者的 AppOps 归属；正式 AppContext 才按真实 LoadedApk 建立归属。
      stage = "构造 shell 调用上下文";
      Class<?> contextImpl = Class.forName("android.app.ContextImpl");
      Field packageInfo = contextImpl.getDeclaredField("mPackageInfo");
      packageInfo.setAccessible(true);
      Object shellApk = packageInfo.get(shellPackage);
      Method appContext = contextImpl.getDeclaredMethod("createAppContext", activityThread, Class.forName("android.app.LoadedApk"));
      appContext.setAccessible(true);
      Context shell = (Context) appContext.invoke(null, thread, shellApk);
      int expectedUid = Integer.parseInt(arguments[2]);
      if (expectedUid % 100000 < 10000) throw new SecurityException("宿主身份无效");
      int userId = expectedUid / 100000;
      // shell 的归属始终是 com.android.shell；只切换查询用户，不加载宿主业务代码。
      Context targetUser = shell;
      stage = "切换安装用户";
      if (userId != 0) {
        Method asUser = Context.class.getDeclaredMethod("createContextAsUser", UserHandle.class, int.class);
        targetUser = (Context) asUser.invoke(shell, UserHandle.getUserHandleForUid(expectedUid), Context.CONTEXT_IGNORE_SECURITY);
      }
      stage = "查询宿主安装身份";
      ApplicationInfo owner = targetUser.getPackageManager().getApplicationInfo(arguments[0], 0);
      if (owner.uid != expectedUid || owner.sourceDir == null || owner.nativeLibraryDir == null)
        throw new SecurityException("宿主安装信息无效");
      stage = "核对宿主安装包";
      String classPath = System.getenv("CLASSPATH");
      if (classPath == null || !new File(classPath).getCanonicalFile().equals(new File(owner.sourceDir).getCanonicalFile()))
        throw new SecurityException("助手未从宿主安装包启动");
      stage = "加载触控内核";
      TouchEngine.initialize(owner.nativeLibraryDir);
      stage = "创建输入服务";
      service = new InputUserService(owner.uid);
      Bundle extras = new Bundle();
      extras.putBinder("service", service.asBinder());
      stage = "核对 shell 上下文包名";
      if (!"com.android.shell".equals(shell.getPackageName())) throw new SecurityException("助手上下文归属不匹配");
      if (Build.VERSION.SDK_INT >= 31) {
        stage = "核对 shell 调用身份";
        if (shell.getAttributionSource().getUid() != Process.myUid()) throw new SecurityException("助手调用身份不匹配");
        stage = "核对 shell 调用包名";
        if (!"com.android.shell".equals(shell.getAttributionSource().getPackageName()))
          throw new SecurityException("助手调用归属不匹配");
      }
      Log.i("落弦律输入助手", "助手启动：阶段=shell 调用归属已核对");
      stage = "交付宿主连接";
      Bundle reply = deliverToHost(shell, arguments[0] + ".input.bridge", userId, arguments[1], extras);
      stage = "接收宿主租约";
      IBinder lease = reply == null ? null : reply.getBinder("lease");
      if (lease == null) throw new SecurityException("宿主未授予输入租约");
      Handler handler = new Handler(Looper.getMainLooper());
      InputUserService running = service;
      AtomicBoolean stopping = new AtomicBoolean();
      Runnable stop = () -> {
        if (!stopping.compareAndSet(false, true)) return;
        running.shutdownFromHelper();
        handler.postDelayed(() -> System.exit(0), 1200);
      };
      lease.linkToDeath(stop::run, 0);
      stage = "监测宿主租约";
      handler.post(new Runnable() {
        @Override public void run() {
          if (stopping.get()) return;
          boolean alive = false;
          Parcel data = Parcel.obtain(), result = Parcel.obtain();
          try {
            if (lease.transact(IBinder.FIRST_CALL_TRANSACTION, data, result, 0)) {
              result.readException();
              alive = result.readInt() == 1;
            }
          } catch (Exception ignored) { }
          finally { data.recycle(); result.recycle(); }
          if (!alive) stop.run(); else handler.postDelayed(this, 1000);
        }
      });
      Looper.loop();
    } catch (Throwable failure) {
      Log.e("落弦律输入助手", "助手启动失败：阶段=" + stage + "；类型=" + safeFailureType(failure));
      if (service != null) service.shutdownFromHelper();
      // 不输出启动参数、令牌或本机凭据。
      System.err.println("输入助手启动失败");
      System.exit(1);
    }
  }

  private static String safeFailureType(Throwable failure) {
    StringBuilder type = new StringBuilder(failure.getClass().getSimpleName());
    for (int depth = 0; failure.getCause() != null && depth < 2; depth++) {
      failure = failure.getCause();
      type.append('/').append(failure.getClass().getSimpleName());
    }
    return type.toString();
  }

  /** 与系统 content 命令一致：shell 外部访问不依赖 ActivityThread 被注册为应用进程。 */
  private static Bundle deliverToHost(Context callingContext, String authority, int userId,
      String nonce, Bundle extras) throws Exception {
    Class<?> managerClass = Class.forName("android.app.IActivityManager");
    Object manager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);
    Method acquire = managerClass.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class);
    Method release = managerClass.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class);
    IBinder token = new Binder();
    Object holder = null;
    Exception primaryFailure = null;
    String stage = "获取外部桥接入口";
    try {
      holder = acquire.invoke(manager, authority, userId, token, "luoxianlv-input");
      if (holder == null) throw new IllegalStateException("宿主连接入口不可用");
      Object provider = Class.forName("android.app.ContentProviderHolder").getField("provider").get(holder);
      if (provider == null) throw new IllegalStateException("宿主连接入口未就绪");
      Log.i("落弦律输入助手", "助手启动：阶段=已获得外部桥接入口");
      stage = "调用外部桥接入口";
      Class<?> providerClass = Class.forName("android.content.IContentProvider");
      if (Build.VERSION.SDK_INT >= 31) {
        Method call = providerClass.getMethod("call", Class.forName("android.content.AttributionSource"),
            String.class, String.class, String.class, Bundle.class);
        return (Bundle) call.invoke(provider, callingContext.getAttributionSource(), authority, "connect", nonce, extras);
      }
      Method call = providerClass.getMethod("call", String.class, String.class, String.class,
          String.class, String.class, Bundle.class);
      return (Bundle) call.invoke(provider, callingContext.getPackageName(), null, authority, "connect", nonce, extras);
    } catch (Exception failure) {
      primaryFailure = failure;
      Log.e("落弦律输入助手", "外部交付失败：阶段=" + stage + "；类型=" + safeFailureType(failure));
      throw failure;
    } finally {
      if (holder != null) {
        try { release.invoke(manager, authority, token, userId); }
        catch (Exception failure) {
          Log.e("落弦律输入助手", "外部交付失败：阶段=释放外部桥接入口；类型=" + safeFailureType(failure));
          if (primaryFailure != null) primaryFailure.addSuppressed(failure);
          else throw failure;
        }
      }
    }
  }
}
