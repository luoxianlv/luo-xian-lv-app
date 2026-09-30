package app.luoxianlv.hot;

import android.os.Looper;
import app.luoxianlv.hot.contract.BusinessFactory;
import app.luoxianlv.hot.contract.NativePage;
import java.util.ArrayList;
import java.util.List;

/** 页面与播放共用的主线程事务。许可、持久化与健康计时由上层 ActivationController 负责。 */
public final class GroupHandover {
  public record Page(PageSwapHost host, String route) {}

  public interface Publication {
    void selectCandidate();

    void restorePrevious();

    default void finish() {}
  }

  public interface Listener {
    void ready(GroupHandover group);

    void failed(GroupHandover group, Throwable failure, boolean contentFailure);
  }

  public enum Phase {
    PREPARING,
    READY,
    TRIAL,
    RESTORING,
    FINISHING,
    FINISHED
  }

  private interface Part {
    boolean valid();

    boolean commit();

    void restore(NativePage.Ready completion);

    void finish();

    default boolean observing() {
      return true;
    }

    default void input(boolean allowed) {}
  }

  private final List<Part> parts = new ArrayList<>();
  private final java.util.Map<PageSwapHost, Part> windows = new java.util.IdentityHashMap<>();
  private final NativeLoader.Prepared source;
  private PlaybackHandover watchedPlayback;
  private Part playbackPart;
  private final Listener listener;
  private final Publication publication;
  private Phase phase = Phase.PREPARING;
  private int expected, prepared;
  private boolean building = true, failureReported;

  private GroupHandover(NativeLoader.Prepared source, Publication publication, Listener listener) {
    this.source = source;
    this.publication = publication;
    this.listener = listener;
  }

  /** 必须在已验证候选且 PREPARING 已落盘之后调用；忙碌时不实例化页面或播放会话。 */
  public static GroupHandover prepare(
      NativeLoader.Prepared source,
      BusinessFactory factory,
      List<Page> pages,
      NativeAccessibilityService playback,
      Publication publication,
      Listener listener) {
    requireMain();
    if (source.manifest != null && !source.manifest.activation.equals("live")) return null;
    if ((pages.isEmpty() && playback == null)
        || pages.stream().anyMatch(page -> !page.host().canStage())
        || (playback != null && (!playback.playbackCanReplace() || playback.playbackRetiring())))
      return null;
    GroupHandover group = new GroupHandover(source, publication, listener);
    group.expected = pages.size() + (playback == null ? 0 : 1);
    try {
      for (Page page : pages) {
        PageSwapHost.Change change =
            page.host().stage(source.page(page.route(), factory), group.pageListener(page.host()));
        StrictJson.require(change != null, "页面在准备边界变为忙碌");
        group.addPage(page.host(), change);
        if (group.failureReported) break;
      }
      if (playback != null && !group.failureReported) {
        PlaybackHandover change =
            playback.preparePlayback(source, factory::playback, group.playbackListener());
        StrictJson.require(change != null, "播放在准备边界变为忙碌");
        group.addPlayback(change);
      }
    } catch (Throwable failure) {
      group.fail(failure, false);
    } finally {
      group.building = false;
      group.notifyReady();
    }
    return group;
  }

  private PageSwapHost.ChangeListener pageListener(PageSwapHost host) {
    return new PageSwapHost.ChangeListener() {
      @Override
      public void ready(PageSwapHost.Change change) {
        partReady();
      }

      @Override
      public void failed(
          PageSwapHost.Change change, String code, Throwable failure, boolean contentFailure) {
        if (code.equals("page_closed") && phase == Phase.TRIAL) {
          parts.remove(windows.remove(host));
          return;
        }
        fail(
            failure == null ? new IllegalStateException("页面切换中断：" + code) : failure,
            contentFailure);
      }
    };
  }

  private PlaybackHandover.Listener playbackListener() {
    return new PlaybackHandover.Listener() {
      @Override
      public void ready(PlaybackHandover change) {
        partReady();
      }

      @Override
      public void failed(PlaybackHandover change, Throwable failure, boolean contentFailure) {
        fail(failure, contentFailure);
      }

      @Override
      public void closed(PlaybackHandover change) {
        if (watchedPlayback != change) return;
        if (phase == Phase.TRIAL) {
          parts.remove(playbackPart);
          watchedPlayback = null;
          playbackPart = null;
        } else topologyChanged();
      }
    };
  }

  private void addPlayback(PlaybackHandover change) {
    watchedPlayback = change;
    playbackPart =
        new Part() {
          @Override
          public boolean valid() {
            return change.valid();
          }

          @Override
          public boolean commit() {
            try {
              return change.commit();
            } catch (Throwable failure) {
              fail(failure, change.rejectedContent());
              throw failure;
            }
          }

          @Override
          public void restore(NativePage.Ready completion) {
            change.rollback(completion);
          }

          @Override
          public void finish() {
            change.finish();
          }

          @Override
          public boolean observing() {
            return change.observing();
          }
        };
    // 播放先交接，再让新页面恢复交互；恢复则反向处理。
    parts.add(0, playbackPart);
  }

  public void playbackOpened(
      NativeAccessibilityService service, NativeLoader.Prepared recovery, BusinessFactory factory) {
    requireMain();
    if (phase == Phase.PREPARING || phase == Phase.READY) {
      topologyChanged();
      return;
    }
    if (phase != Phase.TRIAL || failureReported) return;
    try {
      StrictJson.require(watchedPlayback == null, "试运行已有另一条播放连接");
      addPlayback(service.watchPlayback(recovery, factory::playback, playbackListener()));
    } catch (Throwable failure) {
      fail(failure, false);
    }
  }

  private void addPage(PageSwapHost host, PageSwapHost.Change change) {
    Part part =
        new Part() {
          @Override
          public boolean valid() {
            return change.valid();
          }

          @Override
          public boolean commit() {
            return change.commit();
          }

          @Override
          public void restore(NativePage.Ready completion) {
            change.rollback(completion);
          }

          @Override
          public void finish() {
            change.finish();
          }

          @Override
          public boolean observing() {
            return change.observing();
          }

          @Override
          public void input(boolean allowed) {
            host.groupInput(allowed);
          }
        };
    windows.put(host, part);
    parts.add(part);
  }

  /** 新窗口仍使用当前候选，并共享旧工厂的恢复入口；不会因此提前确认整组健康。 */
  public void pageOpened(Page page, PageTarget recovery) {
    requireMain();
    if (phase == Phase.PREPARING || phase == Phase.READY) {
      topologyChanged();
      return;
    }
    if (phase != Phase.TRIAL || failureReported || windows.containsKey(page.host())) return;
    try {
      addPage(
          page.host(),
          page.host()
              .watchCurrent(
                  source.identity() + "#" + page.route(), recovery, pageListener(page.host())));
    } catch (Throwable failure) {
      fail(failure, false);
    }
  }

  public boolean observing() {
    requireMain();
    return phase == Phase.TRIAL && !failureReported && parts.stream().allMatch(Part::observing);
  }

  private void partReady() {
    requireMain();
    if (phase == Phase.PREPARING) {
      prepared++;
      notifyReady();
    }
  }

  private void notifyReady() {
    if (!building && phase == Phase.PREPARING && !failureReported && prepared == expected) {
      phase = Phase.READY;
      listener.ready(this);
    }
  }

  public Phase phase() {
    requireMain();
    return phase;
  }

  public boolean valid() {
    requireMain();
    return phase == Phase.READY && !failureReported && parts.stream().allMatch(Part::valid);
  }

  /** 上层在 controller.expose 的同一临界区调用；任何失败均由上层统一触发 rollback。 */
  public boolean commit() {
    requireMain();
    if (!valid()) return false;
    phase = Phase.TRIAL;
    try {
      try {
        publication.selectCandidate();
      } catch (Throwable failure) {
        fail(failure, true);
        throw failure;
      }
      for (Part part : parts) if (!part.commit()) throw new IllegalStateException("组件状态在提交期间改变");
      return true;
    } catch (Throwable failure) {
      fail(failure, false);
      throw failure;
    }
  }

  public void rollback(NativePage.Ready completion) {
    requireMain();
    if (phase == Phase.FINISHED) {
      completion.ready();
      return;
    }
    StrictJson.require(phase != Phase.RESTORING, "整组恢复正在进行");
    StrictJson.require(phase != Phase.FINISHING, "健康已确认，旧组件收尾失败不能再恢复部分旧版");
    boolean exposed = phase == Phase.TRIAL;
    phase = Phase.RESTORING;
    parts.forEach(part -> part.input(false));
    List<Throwable> failures = new ArrayList<>();
    if (exposed)
      try {
        publication.restorePrevious();
      } catch (Throwable error) {
        failures.add(error);
      }
    int[] remaining = {parts.size()};
    Runnable complete =
        () -> {
          if (remaining[0] != 0) return;
          phase = Phase.FINISHED;
          if (failures.isEmpty()) parts.forEach(part -> part.input(true));
          parts.clear();
          windows.clear();
          watchedPlayback = null;
          playbackPart = null;
          if (failures.isEmpty()) completion.ready();
          else {
            IllegalStateException failure = new IllegalStateException("部分组件恢复失败");
            failures.forEach(failure::addSuppressed);
            completion.failed(failure);
          }
        };
    List<Part> restoring = new ArrayList<>(parts);
    java.util.Collections.reverse(restoring);
    for (Part part : restoring) {
      var returned = new java.util.concurrent.atomic.AtomicBoolean();
      NativePage.Ready result =
          new NativePage.Ready() {
            @Override
            public void ready() {
              if (returned.compareAndSet(false, true)) {
                remaining[0]--;
                complete.run();
              }
            }

            @Override
            public void failed(Throwable error) {
              if (returned.compareAndSet(false, true)) {
                failures.add(error);
                remaining[0]--;
                complete.run();
              }
            }
          };
      try {
        part.restore(result);
      } catch (Throwable error) {
        result.failed(error);
      }
    }
    if (restoring.isEmpty()) complete.run();
  }

  public void finish() {
    requireMain();
    StrictJson.require(phase == Phase.TRIAL && !failureReported, "整组尚未成功曝光或已经发生故障");
    // 任一释放失败保留错误，由上层阻断后续代际，不将部分收尾伪装成成功。
    phase = Phase.FINISHING;
    try {
      for (Part part : parts) part.finish();
      publication.finish();
      parts.clear();
      windows.clear();
      watchedPlayback = null;
      playbackPart = null;
      phase = Phase.FINISHED;
    } catch (Throwable failure) {
      fail(failure, false);
      throw failure;
    }
  }

  /** 准备期间新增/关闭窗口或系统连接时作废整个快照，不能遗漏新参与者。 */
  public void topologyChanged() {
    requireMain();
    if (phase == Phase.PREPARING || phase == Phase.READY)
      fail(new IllegalStateException("参与组件已改变"), false);
  }

  private void fail(Throwable failure, boolean contentFailure) {
    if (failureReported || phase == Phase.RESTORING || phase == Phase.FINISHED) return;
    failureReported = true;
    new android.os.Handler(Looper.getMainLooper())
        .post(() -> listener.failed(this, failure, contentFailure));
  }

  private static void requireMain() {
    StrictJson.require(Looper.myLooper() == Looper.getMainLooper(), "整组交接必须在主线程执行");
  }
}
