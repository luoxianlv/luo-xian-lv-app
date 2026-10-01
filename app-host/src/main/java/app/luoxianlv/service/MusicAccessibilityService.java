package app.luoxianlv.service;

import android.content.Context;
import app.luoxianlv.host.Bootstrap;
import app.luoxianlv.hot.NativeAccessibilityService;
import app.luoxianlv.hot.contract.NativePlaybackSession;

public final class MusicAccessibilityService extends NativeAccessibilityService {
  private Bootstrap.Source source;

  @Override
  protected AutoCloseable whenPlaybackReady(Runnable ready) {
    return Bootstrap.ready(ready);
  }

  @Override
  protected boolean initialCreation(Runnable create) {
    return Bootstrap.initialCreation(create);
  }

  @Override
  protected NativePlaybackSession createPlaybackSession() {
    source = Bootstrap.source();
    return source.factory.playback();
  }

  @Override
  protected Context playbackContext() {
    Context context = source.prepared.context(this);
    source = null;
    return context;
  }

  @Override
  protected void playbackOpened() {
    Bootstrap.playbackOpened(this);
  }

  @Override
  protected void playbackClosed() {
    Bootstrap.playbackClosed(this);
  }

  @Override
  protected void playbackFailed(Throwable failure) {
    Bootstrap.componentFailed(failure);
  }

  @Override
  protected void playbackUsageChanged() {
    Bootstrap.usageChanged();
  }

  @Override
  protected void foregroundRequested(boolean enabled) {
    if (enabled) PlaybackForegroundService.start(this);
    else PlaybackForegroundService.stop();
  }
}
