package app.luoxianlv.input;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/** shell 向宿主交付 Binder 的一次性入口；DUMP 权限、UID 与随机令牌三重检查。 */
public final class InputBridgeProvider extends ContentProvider {
  @Override public boolean onCreate() { return true; }

  @Override public Bundle call(String method, String argument, Bundle extras) {
    Log.i("落弦律无线桥接", "连接交付：阶段=进入受保护入口");
    if (Binder.getCallingUid() != 2000 || getContext() == null) {
      rejected("调用身份校验", "SecurityException");
      throw new SecurityException("仅允许本机无线调试助手");
    }
    try { getContext().enforceCallingPermission("android.permission.DUMP", "缺少无线调试身份"); }
    catch (SecurityException failure) { rejected("系统授权校验", "SecurityException"); throw failure; }
    if (!"connect".equals(method) || extras == null) {
      rejected("交付协议校验", "IllegalArgumentException");
      throw new IllegalArgumentException("输入助手请求无效");
    }
    IBinder binder = extras.getBinder("service");
    if (binder == null || !binder.isBinderAlive()) {
      rejected("助手 Binder 校验", "IllegalArgumentException");
      throw new IllegalArgumentException("输入助手未就绪");
    }
    WirelessAdbBackend backend = WirelessAdbBackend.current();
    if (backend == null) {
      rejected("宿主连接状态校验", "SecurityException");
      throw new SecurityException("无线连接未发起");
    }
    Bundle response = new Bundle();
    try { response.putBinder("lease", backend.offerHelper(argument, IInputService.Stub.asInterface(binder))); }
    catch (SecurityException failure) { rejected("一次性租约校验", "SecurityException"); throw failure; }
    Log.i("落弦律无线桥接", "连接交付：阶段=租约已授予");
    return response;
  }

  private static void rejected(String stage, String type) {
    Log.e("落弦律无线桥接", "连接交付被拒：阶段=" + stage + "；类型=" + type);
  }

  static final class Lease extends Binder {
    private volatile boolean alive = true;
    void revoke() { alive = false; }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
      if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
      if (Binder.getCallingUid() != 2000) throw new SecurityException("租约调用者无效");
      reply.writeNoException();
      reply.writeInt(alive ? 1 : 0);
      return true;
    }
  }

  @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
  @Override public String getType(Uri uri) { return null; }
  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
  @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
  @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
