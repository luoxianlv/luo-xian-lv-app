package app.luoxianlv.hot;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import app.luoxianlv.hot.contract.NativePage;

/** 真正使用 Android Intent/Bundle，验证请求重建、延迟消费和外部结果隔离。 */
final class HostResultsChecks {
  static void run() {
    Recorder activity = new Recorder();
    ResultPage page = new ResultPage();
    HostResults results = new HostResults(activity, null);
    results.launch("midi.import", new Intent(Intent.ACTION_GET_CONTENT), null);
    int code = activity.code;
    rejects(() -> results.launch("midi.import", new Intent(), null));
    HostResults restored = new HostResults(activity, results.save());
    Uri uri = Uri.parse("content://test/中文.mid");
    Intent file =
        new Intent()
            .setDataAndType(uri, "audio/midi")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("foreign", new Intent("untrusted"));
    file.setClipData(ClipData.newRawUri("谱子", uri));
    restored.accept(code, Activity.RESULT_OK, file, page);
    check(restored.busy(), "未注册回调的结果丢失");
    HostResults buffered = new HostResults(activity, restored.save());
    page.ready = true;
    buffered.deliver("midi.import", page);
    check(!buffered.busy() && page.count == 1, "重建后的延迟结果未消费");
    check(uri.equals(page.data.getData()) && "audio/midi".equals(page.data.getType()), "文件信息改变");
    check(page.data.getFlags() == Intent.FLAG_GRANT_READ_URI_PERMISSION, "意外转发第三方跳转标志");
    check(page.data.getExtras() == null && page.data.getClipData().getItemCount() == 1, "外部对象未隔离");
    buffered.deliver("midi.import", page);
    check(page.count == 1, "重复投递了已消费结果");

    buffered.launch("midi.import", new Intent(), null);
    buffered.accept(activity.code, Activity.RESULT_CANCELED, null, page);
    check(
        page.data == null && page.resultCode == Activity.RESULT_CANCELED && !buffered.busy(),
        "取消语义改变");
    buffered.launch("midi.import", new Intent(), null);
    buffered.accept(
        activity.code,
        Activity.RESULT_OK,
        new Intent().setData(Uri.parse("content://test/" + "x".repeat(8193))),
        page);
    check(page.resultCode == Activity.RESULT_CANCELED && !buffered.busy(), "超长外部数据未安全取消");

    // 回调中重新打开同一选择器也是一个新请求，不应被旧的结果缓冲挡住。
    page.followup = () -> buffered.launch("midi.import", new Intent(), null);
    buffered.launch("midi.import", new Intent(), null);
    buffered.accept(activity.code, Activity.RESULT_CANCELED, null, page);
    check(buffered.busy(), "重入回调的新请求丢失");
    page.followup = null;
    buffered.accept(activity.code, Activity.RESULT_CANCELED, null, page);
    check(!buffered.busy(), "新请求未完成");

    Bundle permissionState = emptyState();
    permissionState.putIntArray("codes", new int[] {0x6000});
    permissionState.putStringArray("keys", new String[] {"notifications"});
    permissionState.putBooleanArray("permissions", new boolean[] {true});
    HostResults permissions = new HostResults(activity, permissionState);
    permissions.accept(0x6000, Activity.RESULT_OK, new Intent(), page);
    check(permissions.busy(), "请求类型不匹配却消费了权限结果");
    permissions.acceptPermissions(
        0x6000, new String[] {"android.permission.POST_NOTIFICATIONS"}, new int[] {-1}, page);
    check(
        !permissions.busy() && page.data.getIntArrayExtra(HostResults.GRANTS)[0] == -1,
        "权限结果协议不匹配");

    Bundle invalid = permissionState.deepCopy();
    invalid.putIntArray("codes", new int[] {1});
    rejects(() -> new HostResults(activity, invalid));
    Bundle duplicate = emptyState();
    duplicate.putIntArray("codes", new int[] {0x6000, 0x6001});
    duplicate.putStringArray("keys", new String[] {"same", "same"});
    duplicate.putBooleanArray("permissions", new boolean[] {false, false});
    rejects(() -> new HostResults(activity, duplicate));

    Bundle wrapping = emptyState();
    wrapping.putInt("next", 0xfffe);
    HostResults wrap = new HostResults(activity, wrapping);
    wrap.launch("last", new Intent(), null);
    check(activity.code == 0xfffe, "请求序号未保留");
    wrap.launch("first", new Intent(), null);
    check(activity.code == 0x6000, "请求序号未环回");
  }

  private static Bundle emptyState() {
    return new HostResults(new Recorder(), null).save();
  }

  private static void rejects(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new AssertionError("非法系统状态未拒绝");
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static final class Recorder extends Activity {
    int code;

    @Override
    public void startActivityForResult(Intent intent, int code, Bundle options) {
      this.code = code;
    }
  }

  private static final class ResultPage implements NativePage {
    boolean ready;
    int count, resultCode;
    Intent data;
    Runnable followup;

    @Override
    public boolean result(String key, int resultCode, Intent data) {
      if (!ready) return false;
      this.resultCode = resultCode;
      this.data = data;
      count++;
      if (followup != null) followup.run();
      return true;
    }

    @Override
    public View create(
        Context context, Bundle state, Bundle hostState, Events events, Ready ready) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Bundle save() {
      return new Bundle();
    }

    @Override
    public void updateHostState(Bundle state) {}

    @Override
    public void lifecycle(int state) {}

    @Override
    public void close() {}
  }
}
