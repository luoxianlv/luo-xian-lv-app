package app.luoxianlv.hot;

import app.luoxianlv.update.ArtifactVerifier;
import app.luoxianlv.update.Cancellation;
import app.luoxianlv.update.PatchMerger;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 只决定已经认证的对象传输方式；重建后的完整字节仍进入原有对象库。 */
public final class HotObjectDeltaResolver {
  private static final ScheduledExecutorService CANCELLATION =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "hot-delta-cancel");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
          });
  private final HotApiClient api;
  private final HotSignatures.PublicKey root;
  private final ContentStore store;
  private final TrustStore trust;
  private final ActivationJournal journal;
  private final UpdateClient.LocalObjects installed;
  private final PatchMerger merger;
  private final File directory;

  public HotObjectDeltaResolver(
      HotApiClient api,
      HotSignatures.PublicKey root,
      ContentStore store,
      TrustStore trust,
      ActivationJournal journal,
      UpdateClient.LocalObjects installed,
      PatchMerger merger,
      File internalDirectory)
      throws Exception {
    StrictJson.require(
        !Files.isSymbolicLink(internalDirectory.toPath())
            && (internalDirectory.isDirectory() || internalDirectory.mkdirs()),
        "字节差分暂存目录无效");
    this.api = Objects.requireNonNull(api);
    this.root = Objects.requireNonNull(root);
    this.store = Objects.requireNonNull(store);
    this.trust = Objects.requireNonNull(trust);
    this.journal = Objects.requireNonNull(journal);
    this.installed = Objects.requireNonNull(installed);
    this.merger = Objects.requireNonNull(merger);
    directory = internalDirectory.getCanonicalFile();
    api.enableByteDeltas();
  }

  Session plan(
      SignedSnapshot candidate, Set<String> missing, UpdateCancellation cancellation, Instant now)
      throws Exception {
    Session session = new Session(candidate);
    try {
      cancellation.check();
      if (missing.isEmpty() || api.appVersionCode <= 0) return session;
      Map<String, Long> bases = new LinkedHashMap<>(installed.baselines());
      var state = journal.state();
      if (state.phase == ActivationJournal.Phase.STABLE && !state.stable.isEmpty()) {
        try {
          ContentStore.Snapshot stable = store.snapshot(state.stable);
          if (stable.manifest.targetVersionCode == api.appVersionCode
              && stable.manifest.applicationId.equals(api.installation.applicationId)
              && stable.manifest.environment.equals(api.installation.environment)) {
            session.baselineLease = store.pin(stable);
            store.verifySnapshotObjects(stable);
            CompiledContract.require(
                api.hostIdentity, store.objectFile(stable.manifest.business.sha256));
            session.stable = stable;
            bases.putAll(stable.manifest.objects);
          }
        } catch (Exception unavailableBaseline) {
          cancellation.check();
          session.releaseBaseline(); // 当前基线缺失或宿主不匹配时改用完整对象。
        }
      }
      for (var base : bases.entrySet())
        StrictJson.require(
            HotManifest.validHash(base.getKey())
                && base.getValue() > 0
                && base.getValue() <= HotManifest.MAX_EXPANDED,
            "宿主基线列表无效");
      StrictJson.Obj reply;
      try {
        reply = api.byteDeltas(candidate, bases.keySet(), cancellation);
      } catch (IOException unavailableEndpoint) {
        cancellation.check();
        return session;
      }
      if (reply == null) return session;
      // 签名说明错误直接拒绝候选，不能通过完整对象下载绕过认证。
      HotByteDeltaPlan plan =
          new HotByteDeltaPlan(
              reply.object("byteDeltaV1"), candidate, api, trust, journal, root, now);
      Map<String, String> sources = new LinkedHashMap<>();
      Set<String> signedPatches = new HashSet<>();
      for (var choices : plan.targets.values())
        for (var delta : choices) signedPatches.add(delta.patchHash);
      var entries = reply.objects("sources");
      StrictJson.require(entries.size() <= 1024, "字节补丁来源数量超限");
      for (var source : entries) {
        source.only("patchSha256", "url");
        String hash = source.string("patchSha256");
        StrictJson.require(
            signedPatches.contains(hash) && sources.put(hash, source.string("url")) == null,
            "字节补丁来源不属于已签名说明或重复");
      }
      for (String target : missing) {
        List<HotByteDeltaPlan.Delta> choices =
            plan.targets.getOrDefault(target, Collections.emptyList());
        for (var delta : choices) {
          if (!Long.valueOf(delta.baseSize).equals(bases.get(delta.baseHash))
              || !sources.containsKey(delta.patchHash)) continue;
          File base =
              session.stable != null
                      && Long.valueOf(delta.baseSize)
                          .equals(session.stable.manifest.objects.get(delta.baseHash))
                  ? store.objectFile(delta.baseHash)
                  : installed.find(delta.baseHash, delta.baseSize);
          if (base == null) continue;
          // 预留下载预算前先确认同源接口与准确补丁身份。
          HttpObjectSource source =
              api.bytePatch(
                  candidate.manifest.snapshotId,
                  delta.patchHash,
                  delta.patchSize,
                  sources.get(delta.patchHash));
          session.selected.put(target, new Selection(delta, base, source));
          break;
        }
      }
      return session;
    } catch (Exception | Error failure) {
      try {
        session.close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private record Selection(HotByteDeltaPlan.Delta delta, File base, HttpObjectSource source) {}

  public final class Session implements AutoCloseable {
    private final SignedSnapshot candidate;
    private final Map<String, Selection> selected = new LinkedHashMap<>();
    private final Set<File> outputs = new HashSet<>();
    private final FileChannel channel;
    private final FileLock lock;
    private AutoCloseable baselineLease;
    private ContentStore.Snapshot stable;
    private boolean closed;

    Session(SignedSnapshot candidate) throws Exception {
      this.candidate = candidate;
      File path = new File(directory, "transport.lock");
      StrictJson.require(!Files.isSymbolicLink(path.toPath()), "字节差分锁不能包含链接");
      channel =
          FileChannel.open(path.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock owned = null;
      try {
        owned = channel.tryLock();
        StrictJson.require(owned != null, "另一个字节差分任务正在准备");
      } catch (Exception failure) {
        channel.close();
        throw failure;
      }
      lock = owned;
    }

    Set<String> patchHashes() {
      Set<String> hashes = new HashSet<>();
      for (var selection : selected.values()) hashes.add(selection.delta.patchHash);
      return hashes;
    }

    void projectNetwork(Map<String, Long> network) {
      for (var entry : selected.entrySet()) {
        network.remove(entry.getKey());
        network.put(entry.getValue().delta.patchHash, entry.getValue().delta.patchSize);
      }
    }

    List<PreparationSpace.Demand> spaceDemands(ObjectDownloader downloads) {
      List<PreparationSpace.Demand> result = new ArrayList<>();
      Set<String> patches = new HashSet<>();
      for (var selection : selected.values()) {
        // 原空间预算已经覆盖完整对象回退；再合并实际重建卷和补丁卷的需求。
        result.add(new PreparationSpace.Demand(directory, selection.delta.targetSize));
        if (patches.add(selection.delta.patchHash))
          result.add(new PreparationSpace.Demand(downloads.directory(), selection.delta.patchSize));
      }
      return result;
    }

    File reconstruct(
        String target,
        ObjectDownloader downloads,
        BooleanSupplier metered,
        UpdateCancellation cancellation,
        LongSupplier remaining)
        throws Exception {
      Selection choice = selected.get(target);
      if (choice == null) return null;
      Cancellation shared = new Cancellation();
      var poll =
          CANCELLATION.scheduleWithFixedDelay(
              () -> {
                if (cancellation.getAsBoolean()) shared.cancel();
              },
              0,
              50,
              TimeUnit.MILLISECONDS);
      File output = new File(directory, target + ".target");
      try (var owned = cancellation.register(shared::cancel)) {
        cancellation.check();
        StrictJson.require(
            !Files.isSymbolicLink(output.toPath()) && (!output.exists() || output.isFile()),
            "字节合并输出类型异常");
        outputs.add(output);
        var delta = choice.delta;
        if (output.isFile()) {
          try {
            ArtifactVerifier.verify(output, delta.targetSize, target, shared);
            return output;
          } catch (Cancellation.CancelledException cancelled) {
            throw cancelled;
          } catch (IOException unfinishedMerge) {
            Files.delete(output.toPath());
          }
        }
        StrictJson.require(!Files.isSymbolicLink(choice.base.toPath()), "字节补丁基线不能包含链接");
        ArtifactVerifier.verify(choice.base, delta.baseSize, delta.baseHash, shared);
        File patch =
            downloads.download(
                candidate.manifest.contentId,
                delta.patchHash,
                delta.patchSize,
                choice.source,
                metered,
                cancellation,
                remaining);
        ArtifactVerifier.verify(patch, delta.patchSize, delta.patchHash, shared);
        cancellation.check();
        merger.merge(choice.base, patch, output, delta.targetSize, shared);
        ArtifactVerifier.verify(output, delta.targetSize, delta.targetHash, shared);
        cancellation.check();
        return output;
      } catch (Cancellation.CancelledException cancelled) {
        cancellation.cancel();
        throw new UpdateCancellation.Cancelled();
      } catch (Exception failedPatch) {
        cancellation.check();
        if (failedPatch instanceof DownloadBudget.Deferred
            || failedPatch instanceof PreparationSpace.Deferred) throw failedPatch;
        if (outputs.contains(output) && output.isFile() && !Files.isSymbolicLink(output.toPath()))
          Files.deleteIfExists(output.toPath());
        return null;
      } finally {
        poll.cancel(false);
        // 包括未进入隔离合并就失败的分支；异步取消钩子也须在本事务租约内完成。
        if (cancellation.getAsBoolean()) shared.cancel();
        shared.awaitClosures();
      }
    }

    void fallback(String target, Map<String, Long> network) {
      Selection removed = selected.remove(target);
      if (removed != null) {
        network.remove(removed.delta.patchHash);
        network.put(target, removed.delta.targetSize);
        restorePatchReservations(network);
      }
    }

    void completed(String target, Map<String, Long> network) {
      Selection removed = selected.remove(target);
      if (removed != null) network.remove(removed.delta.patchHash);
      restorePatchReservations(network);
    }

    private void restorePatchReservations(Map<String, Long> network) {
      for (Selection pending : selected.values())
        network.put(pending.delta.patchHash, pending.delta.patchSize);
    }

    private void releaseBaseline() throws Exception {
      stable = null;
      if (baselineLease != null) {
        baselineLease.close();
        baselineLease = null;
      }
    }

    @Override
    public void close() throws Exception {
      if (closed) return;
      closed = true;
      Exception failure = null;
      for (File output : outputs)
        try {
          if (output.isFile() && !Files.isSymbolicLink(output.toPath()))
            Files.deleteIfExists(output.toPath());
        } catch (Exception cleanup) {
          failure = cleanup;
        }
      try {
        releaseBaseline();
      } catch (Exception cleanup) {
        failure = cleanup;
      }
      try {
        lock.release();
      } finally {
        channel.close();
      }
      if (failure != null) throw failure;
    }
  }
}
