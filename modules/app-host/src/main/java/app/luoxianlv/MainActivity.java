package app.luoxianlv;

import android.os.Bundle;
import android.view.View;
import app.luoxianlv.host.BusinessActivity;
import app.luoxianlv.host.StartupSplash;

public final class MainActivity extends BusinessActivity {
  @Override
  protected String route() {
    return "main";
  }

  @Override
  protected View createStartupView(Bundle state) {
    return StartupSplash.wanted(getIntent(), state)
        ? new StartupSplash(this)
        : super.createStartupView(state);
  }

  @Override
  protected boolean startupCoversPage(View startup) {
    return startup instanceof StartupSplash && ((StartupSplash) startup).pageReady();
  }
}
