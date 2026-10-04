package app.luoxianlv.hot;

import app.luoxianlv.hot.contract.NativePage;

/** 对同版本重建的进程内句柄加身份检查；不同快照或 ClassLoader 只关闭，不转交。 */
final class RetainedPage implements NativePage.Retained {
  private final String identity;
  private final Class<?> pageType;
  private NativePage.Retained value;

  RetainedPage(String identity, Class<?> pageType, NativePage.Retained value) {
    this.identity = identity;
    this.pageType = pageType;
    this.value = value;
  }

  void restore(String expectedIdentity, NativePage page) {
    NativePage.Retained retained = value;
    if (retained == null) return;
    value = null;
    if (!identity.equals(expectedIdentity) || pageType != page.getClass()) {
      retained.close();
      return;
    }
    try {
      page.restoreRetained(retained);
    } catch (RuntimeException | Error failure) {
      try {
        retained.close();
      } catch (Throwable cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  @Override
  public void close() {
    NativePage.Retained retained = value;
    value = null;
    if (retained != null) retained.close();
  }
}
