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
  protected NativePlaybackSession createPlaybackSession() {
    source = Bootstrap.source();
    return source.factory.playback();
  }

  @Override
  protected Context playbackContext() {
    return source.prepared.context(this);
  }

  @Override
  protected void foregroundRequested(boolean enabled) {
    if (enabled) PlaybackForegroundService.start(this);
    else PlaybackForegroundService.stop();
  }
}
