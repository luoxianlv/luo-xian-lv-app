package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import app.luoxianlv.hot.*;
import app.luoxianlv.hot.contract.PracticeBridge;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** 仅本机Debug：普通下载的实际取消/小资源字节观察；代理、投放与ACK由外部driver负责。 */
final class NativeDownloadChecks {
  private NativeDownloadChecks() {}

  static final class Plan {
    final String runId, mode, target, slow, source;
    final Map<String, Long> objects;
    final long missing, prefix;

    Plan(byte[] bytes, String runId) {
      var value =
          StrictJson.object(bytes)
              .only(
                  "schema",
                  "runId",
                  "mode",
                  "targetSnapshotId",
                  "expectedObjects",
                  "expectedMissingBytes",
                  "slowObjectSha",
                  "prefixBytes",
                  "sourceIdentity");
      require(
          value.number("schema") == 1
              && runId.matches("[a-f0-9]{32}")
              && runId.equals(value.string("runId")),
          "下载计划schema/runId无效");
      this.runId = runId;
      mode = value.string("mode");
      target = value.string("targetSnapshotId");
      slow = value.string("slowObjectSha");
      source = value.optionalString("sourceIdentity");
      require(Set.of("cancellation", "delta", "cache-repair", "budget-limit", "budget-retry").contains(mode), "下载检查模式无效");
      require(
          HotManifest.validHash(target)
              && HotManifest.validHash(slow)
              && (source.isEmpty() || source.matches("[a-z0-9:]+")),
          "计划目标或来源身份无效");
      var expected = new LinkedHashMap<String, Long>();
      long total = 0;
      var entries = value.objects("expectedObjects");
      require(!entries.isEmpty() && entries.size() <= 8, "计划需要1至8个小资源对象");
      for (var entry : entries) {
        entry.only("sha256", "size");
        String hash = entry.string("sha256");
        long size = entry.number("size");
        require(
            HotManifest.validHash(hash)
                && size > 0
                && size <= (budgetMode() ? DownloadBudget.LIMIT : 1 << 20)
                && expected.put(hash, size) == null,
            "资源对象重复、过大或格式无效");
        total = Math.addExact(total, size);
      }
      missing = value.number("expectedMissingBytes");
      prefix = value.number("prefixBytes");
      require(
          total == missing && total <= (budgetMode() ? DownloadBudget.LIMIT + 1 : 2 << 20) && expected.containsKey(slow), "缺失资源总量/慢对象不匹配");
      if (mode.equals("cache-repair")) require(expected.size() == 2, "缓存修复必须包含两个独立资源对象");
      if (budgetMode()) require(expected.size() == 2 && total == DownloadBudget.LIMIT + (mode.equals("budget-limit") ? 1 : 0), "预算fixture须为两个资源对象及准确20MiB边界");
      require(
          prefix >= (mode.equals("cancellation") || mode.equals("budget-retry") ? 1 : 0)
              && prefix < expected.get(slow)
              && prefix <= 32768,
          "慢对象前缀无效或已是完整对象");
      objects = Collections.unmodifiableMap(expected);
    }

    boolean budgetMode() { return mode.equals("budget-limit") || mode.equals("budget-retry"); }
  }

  static final class Session {
    final Plan plan;
    final Path directory, control, ack, report;

    private Session(Plan plan, Path directory) {
      this.plan = plan;
      this.directory = directory;
      control = directory.resolve("control.json");
      ack = directory.resolve("ack.json");
      report = directory.resolve("report.json");
    }

    static Session open(Path files, String runId) throws Exception {
      require(runId != null && runId.matches("[a-f0-9]{32}"), "下载runId必须为32位小写hex");
      require(
          !Files.isSymbolicLink(files) && Files.isDirectory(files, LinkOption.NOFOLLOW_LINKS),
          "内部files不可用");
      Path root = files.toRealPath().resolve("native-download-checks"),
          directory = root.resolve(runId);
      require(
          !Files.isSymbolicLink(root)
              && !Files.isSymbolicLink(directory)
              && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS),
          "须先提供本run的普通计划目录");
      Plan plan = new Plan(read(directory.resolve("plan.json"), 65536), runId);
      for (String old : new String[] {"control.json", "ack.json", "report.json"})
        require(
            !Files.exists(directory.resolve(old), LinkOption.NOFOLLOW_LINKS), "本run已有旧握手或回执，拒绝复用");
      Files.createFile(directory.resolve(".started")); // 不复用旧run，不删除旧回执或业务文件。
      return new Session(plan, directory);
    }

    void request(String phase, long at, long expires, String source) throws Exception {
      require(
          Set.of("arm", "request_captured", "cancelled", "unmeter", "resumed_captured", "complete", "failed").contains(phase),
          "下载控制阶段无效");
      require(source != null && source.matches("[a-z0-9:]+"), "下载来源身份不可编码");
      String content =
          "{\"schema\":1,\"runId\":\""
              + plan.runId
              + "\",\"mode\":\""
              + plan.mode
              + "\",\"phase\":\""
              + phase
              + "\",\"targetSnapshotId\":\""
              + plan.target
              + "\",\"slowObjectSha\":\""
              + plan.slow
              + "\",\"expectedMissingBytes\":"
              + plan.missing
              + ",\"sourceIdentity\":\""
              + source
              + "\",\"requestedElapsedMs\":"
              + at
              + ",\"deadlineElapsedMs\":"
              + expires
              + "}";
      write(control, content.getBytes(StandardCharsets.UTF_8));
    }

    boolean acknowledged(String phase) throws Exception {
      require(Set.of("armed", "released", "unmetered").contains(phase), "下载ACK阶段无效");
      safe();
      if (!Files.exists(ack, LinkOption.NOFOLLOW_LINKS)) return false;
      var value = StrictJson.object(read(ack, 4096)).only("schema", "runId", "phase");
      require(
          value.number("schema") == 1
              && Set.of("armed", "released", "unmetered").contains(value.string("phase")),
          "下载ACK格式无效");
      return value.string("runId").equals(plan.runId) && value.string("phase").equals(phase);
    }

    void writeReport(JSONObject value) throws Exception {
      write(report, value.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    void failed(byte[] diagnostic, long at, long expires, String source) throws Exception {
      write(report, diagnostic); // 先保存安全根因，再让外部driver看到failed。
      request("failed", at, expires, source);
    }

    private void safe() {
      require(
          !Files.isSymbolicLink(directory.getParent())
              && !Files.isSymbolicLink(directory)
              && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS),
          "下载握手目录已改变");
    }

    private void write(Path target, byte[] bytes) throws Exception {
      safe();
      require(bytes.length <= (1 << 20), "下载回执过大");
      Path temporary = Files.createTempFile(directory, ".native-download-", ".tmp");
      try {
        Files.write(temporary, bytes);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
  }

  static JSONObject run(Instrumentation runner, Activity home, String runId) throws Exception {
    Context target = guard(runner, home);
    var deadline = new NativeNetworkChecks.Deadline(SystemClock::elapsedRealtime);
    Session session = Session.open(target.getFilesDir().toPath(), runId);
    Plan plan = session.plan;
    var startup = Bootstrap.startupState();
    Object updates = field(Bootstrap.class, null, "updates");
    Object client = field(updates.getClass(), updates, "client");
    ObjectDownloader downloads = (ObjectDownloader) field(client.getClass(), client, "downloads");
    DownloadBudget budget = (DownloadBudget) field(client.getClass(), client, "budget");
    Bootstrap.Source original = Bootstrap.source();
    String originalId = original.prepared.identity();
    require(plan.source.isEmpty() || plan.source.equals(originalId), "计划来源不是实际当前组合");
    require(!originalId.equals(plan.target) && !PracticeBridge.active(), "目标已启用或已有演练场，拒绝接管");
    Selection initialSelection = new Selection(startup.journal.state());
    require(
        initialSelection.stable.equals(originalId)
            && initialSelection.phase == ActivationJournal.Phase.STABLE,
        "字节验收必须从真实稳定热更来源开始");
    for (var expected : plan.objects.entrySet()) {
      if (plan.mode.equals("cache-repair") || plan.budgetMode()) require(
          !Files.exists(startup.store.objectFile(expected.getKey()).toPath(), LinkOption.NOFOLLOW_LINKS),
          "专用测试对象已存在，保留旧故障字节并拒绝重新seed");
      require(
          !startup.store.containsVerified(expected.getKey(), expected.getValue()), "计划对象已有内部准确副本");
      require(
          !Files.exists(downloads.partial(expected.getKey()).toPath(), LinkOption.NOFOLLOW_LINKS),
          "计划对象已有旧断点，不能冒充首次缺失");
    }
    Activity stage = null;
    JSONObject result = null;
    Throwable failure = null;
    Frame observed = null;
    try {
      SignedSnapshot fixture = null;
      JSONObject cacheFault = null;
      if (plan.mode.equals("cache-repair") || plan.budgetMode()) {
        fixture = fixture(session, startup, original);
        if (plan.mode.equals("cache-repair")) cacheFault = seedCacheFault(session, startup.store, fixture);
      }
      Frame initial =
          waitFrame(
              runner,
              updates,
              original,
              plan.target,
              false,
              deadline,
              45000,
              frame -> frame.quiet() && (!plan.budgetMode() || frame.metered) && mainCondition(runner, home::hasWindowFocus),
              "普通入口没有稳定在线空闲状态");
      observed = initial;
      if (plan.budgetMode()) require(initial.metered, "预算验收必须来自系统实际计费网络");
      session.request("arm", now(), deadline.expires, originalId);
      status(runner, "下载握手：files/native-download-checks/" + runId + "/control.json");
      awaitAck(session, "armed", deadline, 45000);
      JSONObject budgetEvidence = new JSONObject();
      if (plan.mode.equals("budget-limit")) {
        Frame deferred = deferred(runner, updates, original, plan, initial, deadline);
        observed = deferred;
        require(budget.used(fixture.manifest.contentId) == 0, "整候选拒绝之前已预留对象流量");
        initialSelection.requireSameSelection(new Selection(startup.journal.state()));
        budgetEvidence.put("wholeCandidateDeferred", deferred.json()).put("usedBeforeUnmetered", 0)
            .put("objectConnectionsBeforeUnmetered", deferred.last.objectRequests());
        require(deferred.last.objectBytes() == 0, "整候选超限仍读取了对象正文");
        for (var entry : plan.objects.entrySet()) require(!Files.exists(downloads.partial(entry.getKey()).toPath(), LinkOption.NOFOLLOW_LINKS)
            && !startup.store.containsVerified(entry.getKey(), entry.getValue()), "整候选超限仍写入了对象或断点");
        result = new JSONObject().put("passed", true).put("runId", runId).put("mode", plan.mode)
            .put("pid", android.os.Process.myPid()).put("initialSourceIdentity", originalId)
            .put("targetSnapshotId", plan.target).put("expectedMissingBytes", plan.missing)
            .put("objectReadBytes", 0).put("objectConnectionAttempts", 0).put("budget", budgetEvidence)
            .put("systemMeteredNetworkTested", true).put("action", new JSONObject().put("selectionUnchanged", true))
            .put("productionTouched", false).put("clockInjected", false).put("updateStateInjected", false)
            .put("explicitCheckCalled", false).put("explicitActivationCalled", false).put("healthInjected", false);
      }
      if (!plan.mode.equals("budget-limit")) {
      UpdateCancellation[] captured = new UpdateCancellation[1];
      Frame inflight =
          waitFrame(
              runner,
              updates,
              original,
              plan.target,
              false,
              deadline,
              90000,
              frame -> {
                Path partial = downloads.partial(plan.slow).toPath();
                if (frame.current == null
                    || frame.current.objectRequests() == 0
                    || !frame.busy
                    || !frame.inFlight) return false;
                if (!Files.exists(partial, LinkOption.NOFOLLOW_LINKS)) return false;
                require(
                    !Files.isSymbolicLink(partial)
                        && Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS),
                    "慢对象断点类型异常");
                if (Files.size(partial) != plan.prefix || frame.current.objectBytes() < plan.prefix)
                  return false;
                captured[0] = frame.current;
                return true;
              },
              "没有观察到普通入口的真实慢对象下载");
      observed = inflight;
      UpdateCancellation token = captured[0];
      require(token != null && !token.isCancelled(), "捕获到的操作已经取消");
      byte[] prefix = read(downloads.partial(plan.slow).toPath(), 32768);
      require(prefix.length == plan.prefix, "慢对象前缀在捕获期间改变");
      if (cacheFault != null) {
        byte[] damaged = read(session.directory.resolve("damaged.bin"), 1 << 20);
        require(Arrays.equals(damaged, read(startup.store.objectFile(plan.slow).toPath(), 1 << 20)), "损坏缓存在网络补取前已被移除或改变");
        cacheFault.put("damagedBytesPresentDuringNetworkRead", true);
      }
      session.request("request_captured", now(), deadline.expires, originalId);
      JSONObject action = new JSONObject();
      long resumedRequests = 0;
      long totalObjectReadBytes = -1;
      if (plan.mode.equals("cancellation") || plan.mode.equals("budget-retry")) {
        long launched = now();
        stage =
            runner.startActivitySync(
                new Intent()
                    .setClassName(target, "app.luoxianlv.ui.practice.PracticeActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        require(stage != null, "实际演练场没有启动");
        boolean priorityObserved = false, lifecycleObserved = false;
        long priorityAt = -1, cancelledAt = -1;
        Frame stopped = null;
        long until = now() + deadline.remaining(30000);
        while (now() < until) {
          Frame frame = frame(runner, updates, original, plan.target, false);
          observed = frame;
          if (frame.priority && PracticeBridge.active() && !PracticeBridge.ready()) {
            priorityObserved = true;
            if (priorityAt < 0) priorityAt = frame.at;
          }
          if (!frame.active) lifecycleObserved = true;
          if (token.isCancelled() && cancelledAt < 0) cancelledAt = frame.at;
          if (token.isCancelled()
              && frame.current != token
              && frame.last == token
              && !frame.busy
              && !frame.inFlight) {
            stopped = frame;
            break;
          }
          SystemClock.sleep(20);
        }
        require(stopped != null && stopped.failures == inflight.failures, "操作未自然取消结束或错误计为失败退避");
        require(priorityObserved || lifecycleObserved, "未观察到实际演练场准备或生命周期变化");
        require(
            Arrays.equals(prefix, read(downloads.partial(plan.slow).toPath(), 32768)), "取消未保留准确前缀");
        Selection after = new Selection(startup.journal.state());
        initialSelection.requireSameSelection(after);
        long requests = token.objectRequests(), bytes = token.objectBytes();
        session.request("cancelled", now(), deadline.expires, originalId);
        awaitAck(session, "released", deadline, 30000);
        require(
            token.objectRequests() == requests && token.objectBytes() == bytes, "取消后的旧操作仍在读取或重试");
        action
            .put("practiceLaunchedElapsedMs", launched)
            .put("priorityObserved", priorityObserved)
            .put("priorityObservedElapsedMs", priorityAt)
            .put("lifecycleUnavailableObserved", lifecycleObserved)
            .put("cancelledObservedElapsedMs", cancelledAt)
            .put("purePriorityCauseProven", false)
            .put("stopped", stopped.json())
            .put("prefixBytesRetained", prefix.length)
            .put("prefixSha256", HotSignatures.hash(prefix))
            .put("selectionUnchanged", true)
            .put("journalBefore", initialSelection.json())
            .put("journalAfter", after.json());
        if (plan.mode.equals("budget-retry")) {
          long spent = budget.used(fixture.manifest.contentId);
          require(spent >= DownloadBudget.RESERVATION && plan.missing - prefix.length > DownloadBudget.LIMIT - spent,
              "实际中断预留没有使整候选重试超过剩余额度");
          Activity closing = stage;
          main(runner, closing::finish);
          long closeUntil = now() + deadline.remaining(15000);
          while ((!closing.isDestroyed() || PracticeBridge.active()) && now() < closeUntil) SystemClock.sleep(50);
          require(closing.isDestroyed() && !PracticeBridge.active(), "预算重试前自己的演练场未退出");
          stage = null;
          restoreHome(runner, target, home, closeUntil);
          Frame retried = deferred(runner, updates, original, plan, stopped, deadline);
          observed = retried;
          require(budget.used(fixture.manifest.contentId) == spent
              && Arrays.equals(prefix, read(downloads.partial(plan.slow).toPath(), 32768)), "重试重新准入未保留账本或原前缀");
          initialSelection.requireSameSelection(new Selection(startup.journal.state()));
          budgetEvidence.put("exact20MiBInitiallyAdmitted", inflight.metered)
              .put("usedAfterCancellation", spent).put("retryDeferred", retried.json())
              .put("retryObjectConnections", retried.last.objectRequests());
          session.request("unmeter", now(), deadline.expires, originalId);
          awaitAck(session, "unmetered", deadline, 30000);
          Frame resumed = waitFrame(runner, updates, original, plan.target, false, deadline, 90000,
              value -> !value.metered && value.current != null && value.current != token
                  && value.current.objectRequests() > 0 && value.busy, "非计费网络未自然重试同一候选");
          UpdateCancellation resumedToken = resumed.current;
          session.request("resumed_captured", now(), deadline.expires, originalId);
          awaitAck(session, "released", deadline, 30000);
          Frame stable = healthy(runner, updates, original, plan, startup, deadline);
          observed = stable;
          require(token.objectBytes() + resumedToken.objectBytes() == plan.missing
              && budget.used(fixture.manifest.contentId) == spent, "重试实际读取量或持久预留变化错误");
          action.put("naturalActivationObserved", true).put("exactMissingBytesObserved", true)
              .put("naturalHealthyJournalObserved", true).put("stable", stable.json());
          budgetEvidence.put("unmeteredResume", resumed.json()).put("finalUsed", spent)
              .put("resumedObjectReadBytes", resumedToken.objectBytes());
          resumedRequests = resumedToken.objectRequests();
          totalObjectReadBytes = token.objectBytes() + resumedToken.objectBytes();
        }
      } else {
        awaitAck(session, "released", deadline, 30000);
        Frame finished =
            waitFrame(
                runner,
                updates,
                original,
                plan.target,
                true,
                deadline,
                90000,
                frame ->
                    frame.identity.equals(plan.target)
                        && frame.current != token
                        && !token.isCancelled(),
                "目标没有通过普通入口自然启用，不能凭下载完成冒充激活");
        observed = finished;
        require(token.objectBytes() == plan.missing, "实际成功读取字节与本次缺对象总量不一致");
        require(token.objectRequests() >= plan.objects.size(), "对象请求数少于真实缺对象数");
        var manifest = Bootstrap.source().prepared.manifest;
        require(manifest != null && manifest.snapshotId.equals(plan.target), "实际来源不是指定签名快照");
        require(
            manifest.runtime.sha256.equals(original.prepared.manifest.runtime.sha256)
                && manifest.business.sha256.equals(original.prepared.manifest.business.sha256),
            "小资源delta不能夹带新运行时或业务APK");
        for (var expected : plan.objects.entrySet()) {
          require(
              expected.getValue().equals(manifest.objects.get(expected.getKey()))
                  && startup.store.containsVerified(expected.getKey(), expected.getValue()),
              "目标对象归属、大小或内部hash验证失败");
        }
        Frame stable = healthy(runner, updates, original, plan, startup, deadline);
        observed = stable;
        main(
            runner,
            () -> require(home.hasWindowFocus() && Bootstrap.foregroundInUse(), "健康确认后没有实际前台首页"));
        action
            .put("finished", finished.json())
            .put("stable", stable.json())
            .put("naturalActivationObserved", true)
            .put("exactMissingBytesObserved", true)
            .put("naturalHealthyJournalObserved", true)
            .put("elapsedSinceActivationObservedMs", stable.at - finished.at);
      }
      result =
          new JSONObject()
              .put("passed", true)
              .put("runId", runId)
              .put("mode", plan.mode)
              .put("pid", android.os.Process.myPid())
              .put("initialSourceIdentity", originalId)
              .put("targetSnapshotId", plan.target)
              .put("expectedMissingBytes", plan.missing)
              .put("objectReadBytes", token.objectBytes())
              .put("objectConnectionAttempts", token.objectRequests() + resumedRequests)
              .put("objectBytesAreBudgetReservation", false)
              .put("serverRequestCountProven", false)
              .put("initial", initial.json())
              .put("captured", inflight.json())
              .put("action", action)
              .put("productionTouched", false)
              .put("clockInjected", false)
              .put("updateStateInjected", false)
              .put("explicitCheckCalled", false)
              .put("explicitActivationCalled", false)
              .put("healthInjected", false);
      if (cacheFault != null) {
        String missingHash = cacheFault.getString("missingObjectSha");
        require(HotSignatures.hash(read(session.directory.resolve("original-damaged.bin"), 1 << 20)).equals(plan.slow)
            && HotSignatures.hash(read(session.directory.resolve("missing.bin"), 1 << 20)).equals(missingHash)
            && HotSignatures.hash(read(session.directory.resolve("damaged.bin"), 1 << 20)).equals(cacheFault.getString("damagedBytesSha")),
            "本轮原始/损坏备份字节没有完整保留");
        String metadataHash = HotSignatures.hash(read(new File(startup.store.snapshot(plan.target).directory, "manifest.json").toPath(), StrictJson.MAX_BYTES));
        require(metadataHash.equals(cacheFault.getString("initialSnapshotMetadataSha")), "本轮原目标清单字节改变");
        result.put("cacheRepair", cacheFault.put("targetSnapshotMetadataHashUnchanged", true).put("faultBackupsPreserved", true));
      }
      if (plan.budgetMode()) result.put("budget", budgetEvidence).put("systemMeteredNetworkTested", true);
      if (plan.mode.equals("budget-retry")) result.put("objectReadBytes", totalObjectReadBytes);
      }
    } catch (Throwable invalid) {
      failure = invalid;
    } finally {
      try {
        long cleanupUntil = now() + 15000;
        if (stage != null) {
          Activity closing = stage;
          main(
              runner,
              () -> {
                if (!closing.isDestroyed()) closing.finish();
              });
          while ((!closing.isDestroyed() || PracticeBridge.active()) && now() < cleanupUntil)
            SystemClock.sleep(50);
          require(closing.isDestroyed() && !PracticeBridge.active(), "自己的演练场未关闭");
        }
        restoreHome(runner, target, home, cleanupUntil);
        deadline.complete();
        if (failure == null) {
          require(result != null, "下载观察缺少回执");
          if (plan.mode.equals("cancellation") || plan.mode.equals("budget-limit")) {
            require(Bootstrap.source() == original, "收尾时原来源已改变");
            initialSelection.requireSameSelection(new Selection(startup.journal.state()));
          }
          result.put("homeRestored", true).put("stageClosed", true);
          session.writeReport(result);
          session.request("complete", now(), deadline.expires, originalId);
        }
      } catch (Throwable closing) {
        if (failure == null) failure = closing;
        else failure.addSuppressed(closing);
      }
      if (failure != null) {
        try {
          JSONObject diagnostic =
              new JSONObject()
                  .put("schema", 1)
                  .put("passed", false)
                  .put("runId", runId)
                  .put("mode", plan.mode)
                  .put("targetSnapshotId", plan.target)
                  .put("initialSourceIdentity", originalId)
                  .put("failureChain", new org.json.JSONArray(safeFailureChain(failure)))
                  .put("journalBefore", initialSelection.json());
          // 已完成的观察含实际elapsed时间；不另调主线程，避免诊断再次阻塞。
          if (observed != null) diagnostic.put("lastCompletedObservation", observed.json());
          try {
            diagnostic.put("journalAfter", new Selection(startup.journal.state()).json());
          } catch (Throwable unavailable) {
            diagnostic.put("journalAfterUnavailable", true);
          }
          session.failed(
              diagnostic.toString(2).getBytes(StandardCharsets.UTF_8),
              now(),
              deadline.expires,
              originalId);
        } catch (Throwable reporting) {
          failure.addSuppressed(reporting);
        }
      }
    }
    if (failure != null) throw new AssertionError("实际下载取消/字节验收未完成", failure);
    return result;
  }

  private static final class Selection {
    final String stable, active, candidate, attempt;
    final long revision, trust;
    final ActivationJournal.Phase phase;
    final Set<String> quarantine;

    Selection(ActivationJournal.State state) {
      stable = state.stable;
      active = state.active;
      candidate = state.candidate;
      attempt = state.attempt;
      revision = state.revision;
      trust = state.trustVersion;
      phase = state.phase;
      quarantine = state.quarantine;
    }

    void requireSameSelection(Selection after) {
      require(
          stable.equals(after.stable)
              && active.equals(after.active)
              && candidate.equals(after.candidate)
              && attempt.equals(after.attempt)
              && phase == after.phase
              && quarantine.equals(after.quarantine),
          "取消改变执行选择或隔离记录");
      require(after.revision >= revision && after.trust >= trust, "取消降低渠道/信任下限");
    }

    JSONObject json() throws Exception {
      return new JSONObject()
          .put("stable", stable)
          .put("active", active)
          .put("candidate", candidate)
          .put("attempt", attempt)
          .put("phase", phase.name())
          .put("revision", revision)
          .put("trustVersion", trust)
          .put("quarantineCount", quarantine.size());
    }
  }

  private static SignedSnapshot fixture(Session session, HostStartup startup, Bootstrap.Source original) throws Exception {
    Path path = session.directory.resolve("candidate.lxhp");
    require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        && Files.size(path) <= (64L << 20), "需要本run普通完整签名fixture，至多64MiB");
    var authority = startup.trust.current();
    var policy = new HotPackage.Policy(startup.config.root, "app.luoxianlv.debug", "test",
        startup.config.hostContract, Math.max(1, startup.journal.state().trustVersion), java.time.Instant.now(),
        startup.config.mounts, authority == null ? null : authority.document(), authority == null ? null : authority.signature());
    try (var archive = new HotPackage(path.toFile(), policy)) {
      var manifest = archive.manifest;
      require(manifest.snapshotId.equals(session.plan.target) && archive.baseline().isEmpty()
          && archive.included.equals(manifest.objects.keySet()), "fixture不是本轮签名完整目标");
      var prior = original.prepared.manifest;
      require(prior != null && prior.runtime.sha256.equals(manifest.runtime.sha256)
          && prior.business.sha256.equals(manifest.business.sha256), "fixture不能更换当前运行时或业务");
      var extra = new LinkedHashSet<String>();
      for (var artifact : manifest.artifacts) if (!prior.objects.containsKey(artifact.sha256)) {
        require(artifact.role.equals("resources") || artifact.role.equals("config"), "fixture新增对象不是官方资源");
        extra.add(artifact.sha256);
      }
      require(extra.equals(session.plan.objects.keySet()) && extra.iterator().next().equals(session.plan.slow),
          "本轮缺失对象须准确覆盖目标新资源，慢对象须首先下载");
      for (var entry : session.plan.objects.entrySet()) require(entry.getValue().equals(manifest.objects.get(entry.getKey())), "fixture对象大小与计划不符");
      if (session.plan.mode.equals("cache-repair")) startup.store.prepare(archive); // 仅本轮未运行目标的准备，不激活。
      return archive.metadata();
    }
  }

  private static JSONObject seedCacheFault(Session session, ContentStore store, SignedSnapshot fixture) throws Exception {
    String damagedHash = session.plan.slow;
    String missingHash = session.plan.objects.keySet().stream().filter(hash -> !hash.equals(damagedHash)).findFirst().orElseThrow();
    Path damaged = store.objectFile(damagedHash).toPath(), missing = store.objectFile(missingHash).toPath();
    byte[] original = read(damaged, 1 << 20), bad = original.clone();
    require(original.length > 0 && HotSignatures.hash(original).equals(damagedHash), "fixture原资源字节不匹配");
    bad[0] ^= 1;
    Files.move(missing, session.directory.resolve("missing.bin"), StandardCopyOption.ATOMIC_MOVE);
    Files.move(damaged, session.directory.resolve("original-damaged.bin"), StandardCopyOption.ATOMIC_MOVE);
    Files.write(session.directory.resolve("damaged.bin"), bad, StandardOpenOption.CREATE_NEW);
    Files.write(damaged, bad, StandardOpenOption.CREATE_NEW);
    require(damaged.toFile().setReadOnly() && !store.containsVerified(damagedHash, original.length)
        && !store.containsVerified(missingHash, session.plan.objects.get(missingHash)), "未形成真实只读坏缓存/缺失对象");
    require(store.snapshot(fixture.manifest.snapshotId).manifest.snapshotId.equals(session.plan.target), "损坏操作改变目标清单");
    return new JSONObject().put("damagedObjectSha", damagedHash).put("missingObjectSha", missingHash)
        .put("damagedBytesSha", HotSignatures.hash(bad)).put("fixtureSeededWithoutActivation", true)
        .put("snapshotMetadataPreserved", true).put("ownNewObjectsOnly", true).put("initialSnapshotMetadataSha",
            HotSignatures.hash(read(new File(store.snapshot(session.plan.target).directory, "manifest.json").toPath(), StrictJson.MAX_BYTES)));
  }

  private static Frame deferred(Instrumentation runner, Object updates, Bootstrap.Source original, Plan plan,
      Frame before, NativeNetworkChecks.Deadline deadline) throws Exception {
    return waitFrame(runner, updates, original, plan.target, false, deadline, 90000,
        value -> value.metered && value.quiet() && value.lastStart > before.lastStart && value.last != null
            && value.last != before.last && value.last.objectRequests() == 0 && value.failures == 0,
        "实际计费网络下没有自然整候选Deferred/重试重新准入");
  }

  private static Frame healthy(Instrumentation runner, Object updates, Bootstrap.Source original, Plan plan,
      HostStartup startup, NativeNetworkChecks.Deadline deadline) throws Exception {
    waitFrame(runner, updates, original, plan.target, true, deadline, 90000,
        value -> value.identity.equals(plan.target) && value.observing, "目标没有进入实际自然观察窗口");
    AtomicReference<HealthWindow> window = new AtomicReference<>();
    main(runner, () -> {
      Object group = field(updates.getClass(), updates, "group");
      require(group != null, "实际资源试运行已经结束，缺少健康窗口证据");
      Object ticket = field(group.getClass(), group, "ticket");
      window.set((HealthWindow) field(ticket.getClass(), ticket, "health"));
    });
    Frame stable = waitFrame(runner, updates, original, plan.target, true, deadline, 90000,
        value -> value.identity.equals(plan.target) && value.quiet()
            && startup.journal.state().phase == ActivationJournal.Phase.STABLE
            && startup.journal.state().stable.equals(plan.target), "自然健康观察未完成，不能在TRIAL结束仪器");
    require(window.get().observedMillis() >= 60000, "资源候选没有60秒实际有效健康时间");
    startup.store.verifySnapshotObjects(startup.store.snapshot(plan.target));
    stable.observedActiveMillis = window.get().observedMillis();
    return stable;
  }

  private static final class Frame {
    long at, lastStart, due;
    int failures;
    boolean active, online, priority, busy, inFlight, pending, observing, metered;
    long observedActiveMillis;
    String identity;
    UpdateCancellation current, last;

    boolean quiet() {
      return active
          && online
          && !priority
          && !busy
          && !inFlight
          && !pending
          && !observing
          && failures == 0;
    }

    JSONObject json() throws Exception {
      return new JSONObject()
          .put("elapsedMs", at)
          .put("lastStartMs", lastStart)
          .put("nextDueMs", due)
          .put("failures", failures)
          .put("active", active)
          .put("online", online)
          .put("priorityWork", priority)
          .put("busy", busy)
          .put("inFlight", inFlight)
          .put("pending", pending)
          .put("observing", observing)
          .put("systemMetered", metered).put("observedActiveMillis", observedActiveMillis)
          .put("sourceIdentity", identity);
    }
  }

  private interface Predicate {
    boolean check(Frame frame) throws Exception;
  }

  private static Frame waitFrame(
      Instrumentation runner,
      Object updates,
      Bootstrap.Source source,
      String target,
      boolean allowTarget,
      NativeNetworkChecks.Deadline deadline,
      long maximum,
      Predicate predicate,
      String error)
      throws Exception {
    long until = now() + deadline.remaining(maximum);
    while (now() < until) {
      deadline.check();
      Frame frame = frame(runner, updates, source, target, allowTarget);
      if (predicate.check(frame)) return frame;
      SystemClock.sleep(20);
    }
    throw new CheckFailure(error);
  }

  private static Frame frame(
      Instrumentation runner,
      Object updates,
      Bootstrap.Source original,
      String target,
      boolean allowTarget)
      throws Exception {
    AtomicReference<Frame> result = new AtomicReference<>();
    main(
        runner,
        () -> {
          Bootstrap.Source source = Bootstrap.source();
          String identity = source.prepared.identity();
          require(source == original || allowTarget && identity.equals(target), "观察期间出现其他业务来源");
          require(
              !Bootstrap.businessStopped()
                  && !(Boolean) field(updates.getClass(), updates, "blocked"),
              "宿主安全门禁已停用业务");
          Object schedule = field(updates.getClass(), updates, "schedule");
          Frame value = new Frame();
          synchronized (schedule) {
            value.at = now();
            value.lastStart = (Long) field(schedule.getClass(), schedule, "lastStart");
            value.due = (Long) field(schedule.getClass(), schedule, "due");
            value.failures = (Integer) field(schedule.getClass(), schedule, "failures");
            value.inFlight = (Boolean) field(schedule.getClass(), schedule, "inFlight");
          }
          value.active = (Boolean) field(updates.getClass(), updates, "active");
          value.online = (Boolean) field(updates.getClass(), updates, "online");
          value.priority = (Boolean) field(updates.getClass(), updates, "priorityWork");
          value.busy = (Boolean) field(updates.getClass(), updates, "busy");
          value.pending = field(updates.getClass(), updates, "pending") != null;
          value.observing =
              field(updates.getClass(), updates, "group") != null
                  || field(updates.getClass(), updates, "coldTicket") != null;
          value.current =
              (UpdateCancellation) field(updates.getClass(), updates, "requestCancellation");
          value.last = (UpdateCancellation) field(updates.getClass(), updates, "lastRequest");
          value.identity = identity;
          var connectivity = runner.getTargetContext().getSystemService(android.net.ConnectivityManager.class);
          value.metered = connectivity == null || connectivity.isActiveNetworkMetered();
          result.set(value);
        });
    return result.get();
  }

  private static void awaitAck(
      Session session, String phase, NativeNetworkChecks.Deadline deadline, long maximum)
      throws Exception {
    long until = now() + deadline.remaining(maximum);
    while (now() < until) {
      deadline.check();
      if (session.acknowledged(phase)) return;
      SystemClock.sleep(50);
    }
    throw new CheckFailure("未收到本run下载ACK：" + phase);
  }

  private interface Action {
    void run() throws Exception;
  }

  private interface Condition {
    boolean test() throws Exception;
  }

  private static boolean mainCondition(Instrumentation runner, Condition condition) throws Exception {
    var result = new java.util.concurrent.atomic.AtomicBoolean();
    main(runner, () -> result.set(condition.test()));
    return result.get();
  }

  private static void main(Instrumentation runner, Action action) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runner.runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    if (failure.get() != null) throw new CheckFailure("下载主线程观察失败", failure.get());
  }

  private static Object field(Class<?> type, Object owner, String name) throws Exception {
    Field value = type.getDeclaredField(name);
    value.setAccessible(true);
    return value.get(owner);
  }

  private static long now() {
    return SystemClock.elapsedRealtime();
  }

  private static byte[] read(Path path, int limit) throws Exception {
    require(
        !Files.isSymbolicLink(path)
            && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && Files.size(path) <= limit,
        "计划/ACK/前缀类型或大小无效");
    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      var bytes = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      for (int count; (count = input.read(buffer)) != -1; ) {
        require(count > 0 && bytes.size() + count <= limit, "计划/ACK/前缀读取超限");
        bytes.write(buffer, 0, count);
      }
      return bytes.toByteArray();
    }
  }

  private static Context guard(Instrumentation runner, Activity home) {
    Context target = runner.getTargetContext();
    var state = Bootstrap.startupState();
    require(
        home != null
            && target.getPackageName().equals("app.luoxianlv.debug")
            && (target.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && Looper.myLooper() != Looper.getMainLooper(),
        "下载仪器仅允许本机Debug工作线程");
    require(
        state != null
            && state.config.automatic
            && state.config.environment.equals("test")
            && state.config.origin.toString().equals("http://127.0.0.1:18472"),
        "下载仪器仅允许显式本机test自动更新");
    return target;
  }

  private static void restoreHome(Instrumentation runner, Context target, Activity home, long until)
      throws Exception {
    main(
        runner,
        () -> {
          require(!home.isDestroyed() && !home.isFinishing(), "原首页已结束");
          if (!home.hasWindowFocus())
            target.startActivity(
                new Intent()
                    .setClassName(target, "app.luoxianlv.MainActivity")
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        });
    while (now() < until) {
      if (mainCondition(runner, () -> home.hasWindowFocus() && Bootstrap.foregroundInUse())) return;
      SystemClock.sleep(50);
    }
    throw new CheckFailure("未恢复原首页焦点");
  }

  private static void status(Instrumentation runner, String message) {
    Bundle value = new Bundle();
    value.putString("stream", message + "\n");
    runner.sendStatus(Activity.RESULT_OK, value);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new CheckFailure(message);
  }

  /** 只保留helper自身固定断言；网络/反射/文件异常正文可能带URL或路径，统一隐藏。 */
  static final class CheckFailure extends AssertionError {
    CheckFailure(String message) {
      super(message);
    }

    CheckFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }

  static List<Map<String, Object>> safeFailureChain(Throwable failure) {
    var rows = new ArrayList<Map<String, Object>>();
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    addFailure(failure, "root", rows, seen);
    return rows;
  }

  private static void addFailure(
      Throwable failure,
      String relation,
      List<Map<String, Object>> rows,
      Set<Throwable> seen) {
    if (failure == null || rows.size() >= 24 || !seen.add(failure)) return;
    rows.add(
        Map.of(
            "relation", relation,
            "type", failure.getClass().getName(),
            "message",
                failure instanceof CheckFailure
                    ? Objects.toString(failure.getMessage(), "")
                    : "外部异常详情已隐藏"));
    addFailure(failure.getCause(), "cause", rows, seen);
    for (Throwable suppressed : failure.getSuppressed())
      addFailure(suppressed, "suppressed", rows, seen);
  }
}
