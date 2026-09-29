package app.luoxianlv.service;

import app.luoxianlv.business.playback.PlaybackSession;
import app.luoxianlv.hot.NativeAccessibilityService;
import app.luoxianlv.hot.contract.NativePlaybackSession;

/** 保留系统已授权的无障碍组件名称，播放业务由独立会话持有。 */
public final class MusicAccessibilityService extends NativeAccessibilityService {
  @Override
  protected NativePlaybackSession createPlaybackSession() {
    return new PlaybackSession();
  }

  @Override
  protected void foregroundRequested(boolean enabled) {
    if (enabled) PlaybackForegroundService.start(this);
    else PlaybackForegroundService.stop();
  }
}
