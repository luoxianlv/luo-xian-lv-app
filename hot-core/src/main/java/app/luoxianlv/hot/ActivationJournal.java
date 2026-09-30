package app.luoxianlv.hot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 新代码执行前先落盘；故障恢复不依赖候选运行时，也不会回滚用户业务数据。 */
public final class ActivationJournal {
  public enum Phase {
    STABLE,
    PREPARING,
    TRIAL
  }

  public enum ExitReason {
    CRASH,
    ANR,
    USER,
    SYSTEM,
    UNKNOWN
  }

  private static final int MAGIC = 0x4c58484a;
  private static final int MAX_QUARANTINE = 512;
  private final File file;
  private State state;
  private byte[] expectedBytes;

  /** 与稳定选择一起提交；直到持久队列确认接收才移除。 */
  public static final class Outcome {
    public final String attemptId, snapshotId, kind, code;

    Outcome(String attemptId, String snapshotId, String kind, String code) {
      new HealthEvent(attemptId, 1, kind, code);
      StrictJson.require(
          HotManifest.validHash(snapshotId)
              && (kind.equals("healthy")
                  || kind.equals("recovered")
                  || kind.equals("load_failed")
                  || kind.equals("crash")
                  || kind.equals("anr")),
          "激活结果回执无效");
      this.attemptId = attemptId;
      this.snapshotId = snapshotId;
      this.kind = kind;
      this.code = code;
    }
  }

  public static final class State {
    public final String stable, active, candidate, attempt;
    public final String previousStable;
    public final String stableAttempt, previousStableAttempt;
    public final long revision, trustVersion, startedAt;
    public final int processId;
    public final Phase phase;
    public final Set<String> quarantine;
    public final List<Outcome> outcomes;

    State(
        String stable,
        String active,
        String candidate,
        String attempt,
        long revision,
        long trustVersion,
        long startedAt,
        int processId,
        Phase phase,
        Set<String> quarantine) {
      this(
          stable,
          active,
          candidate,
          attempt,
          revision,
          trustVersion,
          startedAt,
          processId,
          phase,
          quarantine,
          "");
    }

    State(
        String stable,
        String active,
        String candidate,
        String attempt,
        long revision,
        long trustVersion,
        long startedAt,
        int processId,
        Phase phase,
        Set<String> quarantine,
        String previousStable) {
      this(
          stable,
          active,
          candidate,
          attempt,
          revision,
          trustVersion,
          startedAt,
          processId,
          phase,
          quarantine,
          previousStable,
          Collections.emptyList(),
          "",
          "");
    }

    private State(
        String stable,
        String active,
        String candidate,
        String attempt,
        long revision,
        long trustVersion,
        long startedAt,
        int processId,
        Phase phase,
        Set<String> quarantine,
        String previousStable,
        List<Outcome> outcomes,
        String stableAttempt,
        String previousStableAttempt) {
      this.stable = stable;
      this.active = active;
      this.candidate = candidate;
      this.attempt = attempt;
      this.revision = revision;
      this.trustVersion = trustVersion;
      this.startedAt = startedAt;
      this.processId = processId;
      this.phase = phase;
      this.quarantine = Collections.unmodifiableSet(new LinkedHashSet<>(quarantine));
      this.previousStable = previousStable;
      this.stableAttempt = stableAttempt;
      this.previousStableAttempt = previousStableAttempt;
      this.outcomes = Collections.unmodifiableList(new ArrayList<>(outcomes));
    }

    State withPrevious(String previous) {
      return new State(
          stable,
          active,
          candidate,
          attempt,
          revision,
          trustVersion,
          startedAt,
          processId,
          phase,
          quarantine,
          previous,
          outcomes,
          stableAttempt,
          previous.isEmpty() ? "" : previousStableAttempt);
    }

    State withOutcomes(List<Outcome> receipts) {
      return new State(
          stable,
          active,
          candidate,
          attempt,
          revision,
          trustVersion,
          startedAt,
          processId,
          phase,
          quarantine,
          previousStable,
          receipts,
          stableAttempt,
          previousStableAttempt);
    }

    State withStableAttempts(String current, String previous) {
      return new State(
          stable,
          active,
          candidate,
          attempt,
          revision,
          trustVersion,
          startedAt,
          processId,
          phase,
          quarantine,
          previousStable,
          outcomes,
          current,
          previous);
    }
  }

  public ActivationJournal(File internalStateDirectory) throws Exception {
    StrictJson.require(
        internalStateDirectory.isDirectory() || internalStateDirectory.mkdirs(), "无法创建内部激活日志目录");
    file = new File(internalStateDirectory, "activation.bin");
    expectedBytes =
        Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ? readState() : null;
    state =
        expectedBytes == null
            ? new State("", "", "", "", 0, 0, 0, 0, Phase.STABLE, Collections.emptySet())
            : decode(expectedBytes);
  }

  public synchronized State state() {
    return state;
  }

  /** 空稳定快照表示使用 APK 内置恢复组合，不等于没有可启动版本。 */
  public synchronized String begin(
      String candidate, long revision, long trustVersion, int processId, long now)
      throws Exception {
    return begin(candidate, UUID.randomUUID().toString(), revision, trustVersion, processId, now);
  }

  /** 在线激活沿用服务端签名许可的尝试编号，保证故障与健康回报对应同一次尝试。 */
  public synchronized String begin(
      String candidate, String attempt, long revision, long trustVersion, int processId, long now)
      throws Exception {
    StrictJson.require(UUID.fromString(attempt).toString().equals(attempt), "激活尝试编号无效");
    StrictJson.require(
        HotManifest.validHash(candidate) && !state.quarantine.contains(candidate), "候选无效或已隔离");
    StrictJson.require(
        state.phase == Phase.STABLE && state.quarantine.size() < MAX_QUARANTINE, "仍有试运行版本或隔离记录已满");
    StrictJson.require(
        revision >= state.revision
            && trustVersion >= state.trustVersion
            && revision > 0
            && trustVersion > 0
            && processId > 0
            && now > 0,
        "激活许可版本或进程身份无效");
    StrictJson.require(!candidate.equals(state.active), "候选已经在使用");
    saveKeepingPrevious(
        new State(
            state.stable,
            state.active,
            candidate,
            attempt,
            revision,
            trustVersion,
            now,
            processId,
            Phase.PREPARING,
            state.quarantine));
    return attempt;
  }

  public synchronized void firstFrame(String attempt) throws Exception {
    requireAttempt(attempt, Phase.PREPARING);
    saveKeepingPrevious(
        new State(
            state.stable,
            state.candidate,
            state.candidate,
            state.attempt,
            state.revision,
            state.trustVersion,
            state.startedAt,
            state.processId,
            Phase.TRIAL,
            state.quarantine));
  }

  public synchronized void healthy(String attempt, long observedActiveMillis) throws Exception {
    requireAttempt(attempt, Phase.TRIAL);
    StrictJson.require(observedActiveMillis >= 60000, "需要至少 60 秒有效前台或演奏观察");
    save(
        new State(
                state.active,
                state.active,
                "",
                "",
                state.revision,
                state.trustVersion,
                0,
                0,
                Phase.STABLE,
                state.quarantine)
            .withPrevious(state.stable)
            .withStableAttempts(state.attempt, state.stableAttempt)
            .withOutcomes(addedOutcome("healthy", "foreground_observed")));
  }

  public synchronized void fail(String attempt, boolean confirmedContentFailure) throws Exception {
    StrictJson.require(state.phase != Phase.STABLE && state.attempt.equals(attempt), "激活尝试已过期");
    recoverCandidate(confirmedContentFailure);
  }

  /** 仅匹配候选进程与时间的系统崩溃/ANR 记录才进入坏包隔离，清后台不记为内容错误。 */
  public synchronized State recover(ExitReason reason, int exitedPid, long exitTime)
      throws Exception {
    if (state.phase == Phase.STABLE) return state;
    boolean confirmed =
        (reason == ExitReason.CRASH || reason == ExitReason.ANR)
            && exitedPid == state.processId
            && exitTime >= state.startedAt;
    recoverCandidate(confirmed);
    return state;
  }

  public synchronized void observeVersions(long revision, long trustVersion) throws Exception {
    StrictJson.require(
        revision >= state.revision && trustVersion >= state.trustVersion, "拒绝过时的发布决定或信任列表");
    if (revision == state.revision && trustVersion == state.trustVersion) return;
    if (state.phase == Phase.PREPARING
        && (revision > state.revision || trustVersion > state.trustVersion)) {
      // 尚未展示的新代码不能拿已失效许可继续进入首帧；需要按新决定重新准备。
      saveKeepingPrevious(
          new State(
              state.stable,
              state.stable,
              "",
              "",
              revision,
              trustVersion,
              0,
              0,
              Phase.STABLE,
              state.quarantine));
      return;
    }
    saveKeepingPrevious(
        new State(
            state.stable,
            state.active,
            state.candidate,
            state.attempt,
            revision,
            trustVersion,
            state.startedAt,
            state.processId,
            state.phase,
            state.quarantine));
  }

  private void recoverCandidate(boolean confirmed) throws Exception {
    Set<String> quarantine = new LinkedHashSet<>(state.quarantine);
    if (confirmed) quarantine.add(state.candidate);
    List<Outcome> outcomes =
        addedOutcome(
            confirmed ? "load_failed" : "recovered",
            confirmed ? "recorded_candidate_failed" : "whole_group_restored");
    save(
        new State(
                state.stable,
                state.stable,
                "",
                "",
                state.revision,
                state.trustVersion,
                0,
                0,
                Phase.STABLE,
                quarantine)
            .withPrevious(quarantine.contains(state.previousStable) ? "" : state.previousStable)
            .withStableAttempts(
                state.stableAttempt,
                quarantine.contains(state.previousStable) ? "" : state.previousStableAttempt)
            .withOutcomes(outcomes));
  }

  private void requireAttempt(String attempt, Phase phase) {
    StrictJson.require(state.phase == phase && state.attempt.equals(attempt), "激活回调已经过期");
  }

  /** 启动观察通过后仍可回退；调用方必须已有明确属于当前模块的故障证据。 */
  public synchronized void stableContentFailed(
      HotManifest manifest, ContentQuarantine contentQuarantine, long hostContract)
      throws Exception {
    revertStable(manifest, contentQuarantine, hostContract, true);
  }

  /** 系统退出证据必须由宿主先匹配当前内容、进程与执行时间，不能仅凭上次有崩溃回退。 */
  public synchronized void stableProcessFailed(
      HotManifest manifest,
      ContentQuarantine contentQuarantine,
      long hostContract,
      ExitReason reason)
      throws Exception {
    StrictJson.require(reason == ExitReason.CRASH || reason == ExitReason.ANR, "不是内容进程故障");
    requireStable(manifest);
    contentQuarantine.isolate(manifest, hostContract);
    Set<String> quarantined = new LinkedHashSet<>(state.quarantine);
    quarantined.add(state.stable);
    restoreStable(
        quarantined, reason == ExitReason.CRASH ? "crash" : "anr", "stable_process_failed");
  }

  private void requireStable(HotManifest manifest) {
    StrictJson.require(
        state.phase == Phase.STABLE && state.stable.equals(manifest.snapshotId), "故障报告不属于当前稳定内容");
  }

  synchronized void revertStable(
      HotManifest manifest,
      ContentQuarantine contentQuarantine,
      long hostContract,
      boolean confirmed)
      throws Exception {
    requireStable(manifest);
    if (confirmed) contentQuarantine.isolate(manifest, hostContract);
    Set<String> quarantined = new LinkedHashSet<>(state.quarantine);
    if (confirmed) quarantined.add(state.stable);
    restoreStable(quarantined);
  }

  /** 缓存缺失、验签失败或 APK 契约变化只改变选择，不把原内容判成坏代码。 */
  public synchronized void unavailableStable(String expected) throws Exception {
    StrictJson.require(
        state.phase == Phase.STABLE
            && state.stable.equals(expected)
            && HotManifest.validHash(expected),
        "恢复请求不属于当前稳定版本");
    restoreStable(state.quarantine);
  }

  private void restoreStable(Set<String> quarantined) throws Exception {
    restoreStable(
        quarantined,
        quarantined.contains(state.stable) ? "load_failed" : "recovered",
        quarantined.contains(state.stable) ? "business_failed" : "whole_group_restored");
  }

  private void restoreStable(Set<String> quarantined, String kind, String code) throws Exception {
    String fallback = quarantined.contains(state.previousStable) ? "" : state.previousStable;
    List<Outcome> outcomes = new ArrayList<>();
    for (var outcome : state.outcomes)
      outcomes.add(
          outcome.snapshotId.equals(state.stable)
              ? new Outcome(outcome.attemptId, outcome.snapshotId, kind, code)
              : outcome);
    // 健康回执即使早已送达，后来真实发生的故障仍沿用原许可尝试，不能凭空生成服务端未知编号。
    if (!state.stableAttempt.isEmpty()
        && outcomes.stream().noneMatch(value -> value.attemptId.equals(state.stableAttempt))) {
      StrictJson.require(outcomes.size() < 128, "待保存的激活结果过多，保留未确认记录");
      outcomes.add(new Outcome(state.stableAttempt, state.stable, kind, code));
    }
    save(
        new State(
                fallback,
                fallback,
                "",
                "",
                state.revision,
                state.trustVersion,
                0,
                0,
                Phase.STABLE,
                quarantined)
            .withStableAttempts(fallback.isEmpty() ? "" : state.previousStableAttempt, "")
            .withOutcomes(outcomes));
  }

  private List<Outcome> addedOutcome(String kind, String code) {
    StrictJson.require(state.outcomes.size() < 128, "待保存的激活结果过多，保留未确认记录");
    var outcomes = new ArrayList<>(state.outcomes);
    outcomes.add(new Outcome(state.attempt, state.candidate, kind, code));
    return outcomes;
  }

  /** 队列先完成原子保存；精确删除相同回执，迟到确认不能清除随后改写的故障结果。 */
  public synchronized boolean outcomeQueued(Outcome sent) throws Exception {
    var outcomes = new ArrayList<>(state.outcomes);
    boolean removed =
        outcomes.removeIf(
            value ->
                value.attemptId.equals(sent.attemptId)
                    && value.snapshotId.equals(sent.snapshotId)
                    && value.kind.equals(sent.kind)
                    && value.code.equals(sent.code));
    if (removed) save(state.withOutcomes(outcomes));
    return removed;
  }

  private void saveKeepingPrevious(State next) throws Exception {
    save(
        next.withPrevious(
                next.quarantine.contains(state.previousStable) ? "" : state.previousStable)
            .withStableAttempts(
                state.stableAttempt,
                next.quarantine.contains(state.previousStable) ? "" : state.previousStableAttempt)
            .withOutcomes(state.outcomes));
  }

  private void save(State next) throws Exception {
    byte[] bytes = encode(next);
    try (FileChannel channel =
            FileChannel.open(
                new File(file.getParentFile(), "activation.lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "另一个控制器正在修改激活日志");
      if (Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        StrictJson.require(
            expectedBytes != null && java.util.Arrays.equals(readState(), expectedBytes),
            "激活日志已被其他控制器更新，拒绝覆盖");
      else StrictJson.require(expectedBytes == null, "激活日志已丢失，拒绝覆盖版本下限");
      File temporary = File.createTempFile("activation-", ".part", file.getParentFile());
      try {
        ContentStore.writeSynced(temporary.toPath(), bytes);
        ContentStore.replaceSynced(temporary, file);
        state = next;
        expectedBytes = bytes;
      } finally {
        Files.deleteIfExists(temporary.toPath());
      }
    }
  }

  private byte[] readState() throws Exception {
    StrictJson.require(
        file.isFile() && file.length() <= 65536 && !Files.isSymbolicLink(file.toPath()),
        "激活日志大小或类型无效");
    try (java.io.InputStream input = Files.newInputStream(file.toPath())) {
      return HotPackage.read(input, 65536);
    }
  }

  private static byte[] encode(State state) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(buffer)) {
      out.writeInt(MAGIC);
      out.writeInt(4);
      out.writeUTF(state.stable);
      out.writeUTF(state.active);
      out.writeUTF(state.candidate);
      out.writeUTF(state.attempt);
      out.writeLong(state.revision);
      out.writeLong(state.trustVersion);
      out.writeLong(state.startedAt);
      out.writeInt(state.processId);
      out.writeUTF(state.phase.name());
      out.writeInt(state.quarantine.size());
      for (String id : state.quarantine) out.writeUTF(id);
      out.writeUTF(state.previousStable);
      out.writeInt(state.outcomes.size());
      for (var outcome : state.outcomes) {
        out.writeUTF(outcome.attemptId);
        out.writeUTF(outcome.snapshotId);
        out.writeUTF(outcome.kind);
        out.writeUTF(outcome.code);
      }
      out.writeUTF(state.stableAttempt);
      out.writeUTF(state.previousStableAttempt);
    }
    byte[] body = buffer.toByteArray();
    buffer.write(MessageDigest.getInstance("SHA-256").digest(body));
    return buffer.toByteArray();
  }

  private static State decode(byte[] raw) throws Exception {
    StrictJson.require(raw.length >= 40 && raw.length <= 65536, "激活日志大小无效，需使用 APK 恢复入口");
    int length = raw.length - 32;
    byte[] body = java.util.Arrays.copyOf(raw, length),
        digest = java.util.Arrays.copyOfRange(raw, length, raw.length);
    StrictJson.require(
        MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), digest),
        "激活日志损坏，不能重置防回退版本");
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      StrictJson.require(input.readInt() == MAGIC, "激活日志格式不支持");
      int format = input.readInt();
      StrictJson.require(format == 2 || format == 3 || format == 4, "激活日志格式不支持");
      String stable = input.readUTF(),
          active = input.readUTF(),
          candidate = input.readUTF(),
          attempt = input.readUTF();
      long revision = input.readLong(), trust = input.readLong(), started = input.readLong();
      int pid = input.readInt();
      Phase phase = Phase.valueOf(input.readUTF());
      int size = input.readInt();
      StrictJson.require(
          size >= 0 && size <= MAX_QUARANTINE && revision >= 0 && trust >= 0, "激活日志状态无效");
      Set<String> quarantine = new LinkedHashSet<>();
      for (int i = 0; i < size; i++) {
        String id = input.readUTF();
        StrictJson.require(HotManifest.validHash(id) && quarantine.add(id), "隔离内容身份无效");
      }
      String previousStable = input.readUTF();
      List<Outcome> outcomes = new ArrayList<>();
      if (format >= 3) {
        int count = input.readInt();
        StrictJson.require(count >= 0 && count <= 128, "激活结果数量无效");
        Set<String> attempts = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
          Outcome outcome =
              new Outcome(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF());
          StrictJson.require(attempts.add(outcome.attemptId), "激活结果重复");
          outcomes.add(outcome);
        }
      }
      String stableAttempt = format >= 4 ? input.readUTF() : "";
      String previousAttempt = format >= 4 ? input.readUTF() : "";
      if (format < 4) {
        for (var outcome : outcomes) {
          if (outcome.kind.equals("healthy") && outcome.snapshotId.equals(stable))
            stableAttempt = outcome.attemptId;
          if (outcome.kind.equals("healthy") && outcome.snapshotId.equals(previousStable))
            previousAttempt = outcome.attemptId;
        }
      }
      for (String id : new String[] {stableAttempt, previousAttempt})
        StrictJson.require(id.isEmpty() || UUID.fromString(id).toString().equals(id), "稳定尝试编号无效");
      StrictJson.require(
          (!stable.isEmpty() || stableAttempt.isEmpty())
              && (!previousStable.isEmpty() || previousAttempt.isEmpty()),
          "稳定尝试与快照不一致");
      StrictJson.require(
          (previousStable.isEmpty() || HotManifest.validHash(previousStable))
              && !quarantine.contains(previousStable),
          "恢复副本身份无效");
      StrictJson.require(input.available() == 0, "激活日志含尾随内容");
      StrictJson.require(
          (stable.isEmpty() || HotManifest.validHash(stable))
              && (active.isEmpty() || HotManifest.validHash(active)),
          "活动快照身份无效");
      if (phase == Phase.STABLE)
        StrictJson.require(
            candidate.isEmpty()
                && attempt.isEmpty()
                && active.equals(stable)
                && pid == 0
                && started == 0,
            "稳定日志状态不一致");
      else {
        StrictJson.require(
            HotManifest.validHash(candidate)
                && pid > 0
                && started > 0
                && !quarantine.contains(candidate),
            "候选日志状态不一致");
        UUID.fromString(attempt);
        StrictJson.require(
            phase == Phase.PREPARING ? active.equals(stable) : active.equals(candidate),
            "候选指针状态不一致");
      }
      return new State(
              stable,
              active,
              candidate,
              attempt,
              revision,
              trust,
              started,
              pid,
              phase,
              quarantine,
              previousStable)
          .withStableAttempts(stableAttempt, previousAttempt)
          .withOutcomes(outcomes);
    }
  }
}
