package app.luoxianlv.hot;

import app.luoxianlv.hot.contract.NativePage;

/** 进程内重建句柄不能跨内容或类型使用，失败与未采用的句柄必须只关闭一次。 */
final class RetainedPageChecks {
  static void run() {
    Counter adopted = new Counter();
    Receiver same = new Receiver();
    RetainedPage state = new RetainedPage("a", Receiver.class, adopted);
    state.restore("a", same);
    state.close();
    check(same.value == adopted && adopted.closed == 0, "同版本句柄被过早清理");
    same.close();
    check(adopted.closed == 1, "接收者未释放句柄");

    for (boolean changedContent : new boolean[] {true, false}) {
      Counter rejected = new Counter();
      Receiver target = changedContent ? new Receiver() : new OtherReceiver();
      RetainedPage wrong = new RetainedPage("a", Receiver.class, rejected);
      wrong.restore(changedContent ? "b" : "a", target);
      wrong.close();
      check(target.value == null && rejected.closed == 1, "不兼容重建句柄被采用或重复关闭");
    }
    Counter failed = new Counter();
    Receiver broken = new Receiver();
    broken.reject = true;
    RetainedPage invalid = new RetainedPage("a", Receiver.class, failed);
    try {
      invalid.restore("a", broken);
      throw new AssertionError("恢复错误未抛出");
    } catch (IllegalStateException expected) {
    }
    invalid.close();
    check(failed.closed == 1, "恢复失败泄漏了句柄");
  }

  private static class Receiver extends PageSwapChecks.TrackingPage {
    NativePage.Retained value;
    boolean reject;

    @Override
    public void restoreRetained(NativePage.Retained state) {
      if (reject) throw new IllegalStateException("测试注入的恢复失败");
      value = state;
    }

    @Override
    public void close() {
      if (value != null) {
        value.close();
        value = null;
      }
    }
  }

  private static final class OtherReceiver extends Receiver {}

  private static final class Counter implements NativePage.Retained {
    int closed;

    @Override
    public void close() {
      closed++;
    }
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
