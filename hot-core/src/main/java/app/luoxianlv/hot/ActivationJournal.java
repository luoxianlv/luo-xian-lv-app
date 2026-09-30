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
import java.util.Collections;
import java.util.LinkedHashSet;
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

  public static final class State {
    public final String stable, active, candidate, attempt;
    public final String previousStable;
    public final long revision, trustVersion, startedAt;
    public final int processId;
    public final Phase phase;
    public final Set<String> quarantine;

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
          previous);
    }
  }

  public ActivationJournal(File internalStateDirectory) throws Exception {
    StrictJson.require(
        internalStateDirectory.isDirectory() || internalStateDirectory.mkdirs(), "无法创建内部激活日志目录");
    file = new File(internalStateDirectory, "activation.bin");
    state =
        file.exists()
            ? decode(readState())
            : new State("", "", "", "", 0, 0, 0, 0, Phase.STABLE, Collections.emptySet());
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
            .withPrevious(state.stable));
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
    saveKeepingPrevious(
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
            quarantine));
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

  synchronized void revertStable(
      HotManifest manifest,
      ContentQuarantine contentQuarantine,
      long hostContract,
      boolean confirmed)
      throws Exception {
    StrictJson.require(
        state.phase == Phase.STABLE && state.stable.equals(manifest.snapshotId), "故障报告不属于当前稳定内容");
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
    String fallback = quarantined.contains(state.previousStable) ? "" : state.previousStable;
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
            quarantined));
  }

  private void saveKeepingPrevious(State next) throws Exception {
    save(
        next.withPrevious(
            next.quarantine.contains(state.previousStable) ? "" : state.previousStable));
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
      if (file.exists())
        StrictJson.require(
            java.util.Arrays.equals(readState(), encode(state)), "激活日志已被其他控制器更新，拒绝覆盖");
      File temporary = File.createTempFile("activation-", ".part", file.getParentFile());
      try {
        ContentStore.writeSynced(temporary.toPath(), bytes);
        ContentStore.replaceSynced(temporary, file);
        state = next;
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
      out.writeInt(2);
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
      StrictJson.require(input.readInt() == MAGIC && input.readInt() == 2, "激活日志格式不支持");
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
          previousStable);
    }
  }
}
