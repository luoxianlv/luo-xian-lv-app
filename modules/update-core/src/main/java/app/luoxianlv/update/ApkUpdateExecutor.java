package app.luoxianlv.update;

import android.content.Context;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** 普通更新单一事务：认证→宿主基线→补丁→隔离合并→完整 APK 校验。 */
public final class ApkUpdateExecutor {
  public interface UrlResolver {
    String patchUrl(String patchSha256) throws IOException;
  }

  private final File root;
  private final Verifier verifier;
  private final UrlResolver urls;
  private final PatchMerger merger;
  private final ApkInspector inspector;
  private final ResumableDownloader downloader = new ResumableDownloader();

  public ApkUpdateExecutor(Context context, Verifier verifier, UrlResolver urls) {
    this(context, verifier, urls, new IsolatedPatchMerger(context));
  }

  public ApkUpdateExecutor(
      Context context, Verifier verifier, UrlResolver urls, PatchMerger merger) {
    try {
      this.root = new File(context.getFilesDir().getCanonicalFile(), "apk-updates");
    } catch (IOException invalid) {
      throw new IllegalArgumentException("无法确定更新根目录", invalid);
    }
    this.verifier = verifier;
    this.urls = urls;
    this.merger = merger;
    this.inspector = new ApkInspector(context);
  }

  public VerifiedApk prepare(Request request, Cancellation cancellation, Progress progress)
      throws IOException {
    if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("更新必须在后台线程运行");
    int priority = android.os.Process.getThreadPriority(android.os.Process.myTid());
    try {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
      return prepareBackground(request, cancellation, progress);
    } finally {
      cancellation.awaitClosures();
      android.os.Process.setThreadPriority(priority);
    }
  }

  private VerifiedApk prepareBackground(
      Request request, Cancellation cancellation, Progress progress) throws IOException {
    cancellation.check();
    // 签名失败始终传播给调用者，不能进入任何全包回退分支。
    UpdateManifest manifest = authenticateRequest(request);
    ApkInspector.Installed installed = inspector.installed();
    inspector.verifyIdentity(manifest, installed);
    SafeFiles.directory(root);
    File transactionLock = new File(root, "transaction.lock");
    SafeFiles.rejectLink(transactionLock);
    try (RandomAccessFile lockFile = new RandomAccessFile(transactionLock, "rw")) {
      FileLock lock;
      try {
        lock = lockFile.getChannel().tryLock();
      } catch (OverlappingFileLockException busy) {
        throw new IOException("已有普通更新任务运行", busy);
      }
      if (lock == null) throw new IOException("已有普通更新任务运行");
      try (lock) {
        return prepareLocked(request, manifest, installed, cancellation, progress);
      }
    }
  }

  private VerifiedApk prepareLocked(
      Request request,
      UpdateManifest manifest,
      ApkInspector.Installed installed,
      Cancellation cancellation,
      Progress progress)
      throws IOException {
    File task = new File(root, manifest.target.sha256);
    SafeFiles.directory(task);
    ApkCacheCleaner.collect(root, manifest.target.sha256, previousActiveTarget());
    AtomicLong downloaded = new AtomicLong(loadBytes(task));
    try {
      saveRequest(request);
      AtomicLong persisted = new AtomicLong(downloaded.get());
      Progress report =
          (phase, completed, total, ignored, detail) -> {
            if (downloaded.get() - persisted.get() >= 1024 * 1024) {
              try {
                saveBytes(task, downloaded.get());
                persisted.set(downloaded.get());
              } catch (IOException failure) {
                /* 请求仍可按摘要安全恢复 */
              }
            }
            progress.onProgress(phase, completed, total, downloaded.get(), detail);
          };
      File ready = new File(task, "verified.apk");
      SafeFiles.rejectLink(ready);
      if (ready.isFile()) {
        try {
          inspector.verifyTarget(ready, manifest, installed, cancellation);
          inspector.ensureUnchanged(installed);
          return new VerifiedApk(ready, manifest.target, downloaded.get(), false, "复用已验证更新");
        } catch (Cancellation.CancelledException cancelled) {
          throw cancelled;
        } catch (IOException invalid) {
          Files.deleteIfExists(ready.toPath());
        }
      }
      String fallback = "没有适用的增量补丁";
      UpdateManifest.Delta selected = null;
      if (installed.universal) {
        for (UpdateManifest.Delta delta : manifest.deltas) {
          if (delta.base.versionCode != installed.versionCode
              || delta.base.size != installed.source.length()) continue;
          if (!UpdateManifest.ALGORITHM.equals(delta.patch.algorithm)
              || !UpdateManifest.worthwhile(manifest.target.size, delta.patch.size)) continue;
          report.onProgress("baseline", 0, delta.base.size, downloaded.get(), "正在验证已安装版本");
          try {
            ArtifactVerifier.verify(
                installed.source, delta.base.size, delta.base.sha256, cancellation);
            selected = delta;
            break;
          } catch (Cancellation.CancelledException cancelled) {
            throw cancelled;
          } catch (IOException wrongBaseline) {
            fallback = "已安装文件不匹配，改为下载完整包";
          }
        }
      } else {
        fallback = "拆分安装需要下载完整包";
      }
      File output = new File(task, "reconstructed.tmp");
      SafeFiles.rejectLink(output);
      if (selected != null) {
        try {
          if (task.getUsableSpace() < manifest.target.size + selected.patch.size + 4L * 1024 * 1024)
            throw new IOException("可用空间不足");
          UpdateManifest.Patch patch = selected.patch;
          File diff = new File(task, patch.sha256 + ".hpatch.part");
          downloader.download(
              () -> urls.patchUrl(patch.sha256),
              diff,
              patch.size,
              patch.sha256,
              cancellation,
              report,
              downloaded);
          cancellation.check();
          // 合并每次重新创建输出，任何进程退出都不会接着写入未验证的旧结果。
          Files.deleteIfExists(output.toPath());
          report.onProgress("merging", 0, manifest.target.size, downloaded.get(), "正在合成完整更新");
          merger.merge(installed.source, diff, output, manifest.target.size, cancellation);
          inspector.verifyTarget(output, manifest, installed, cancellation);
          inspector.ensureUnchanged(installed);
          commit(output, ready, cancellation);
          saveBytes(task, downloaded.get());
          return new VerifiedApk(ready, manifest.target, downloaded.get(), true, "");
        } catch (Cancellation.CancelledException cancelled) {
          throw cancelled;
        } catch (IOException failedDelta) {
          cancellation.check();
          Files.deleteIfExists(output.toPath());
          fallback = "增量更新失败，改为下载完整包";
        }
      }
      report.onProgress("fallback", 0, manifest.target.size, downloaded.get(), fallback);
      File full = new File(task, "full.apk.part");
      IOException last = null;
      for (String url : request.fullUrls) {
        try {
          downloader.download(
              () -> url,
              full,
              manifest.target.size,
              manifest.target.sha256,
              cancellation,
              report,
              downloaded);
          inspector.verifyTarget(full, manifest, installed, cancellation);
          inspector.ensureUnchanged(installed);
          commit(full, ready, cancellation);
          saveBytes(task, downloaded.get());
          return new VerifiedApk(ready, manifest.target, downloaded.get(), false, fallback);
        } catch (Cancellation.CancelledException cancelled) {
          throw cancelled;
        } catch (IOException failure) {
          cancellation.check();
          last = failure;
        }
      }
      saveBytes(task, downloaded.get());
      throw last != null ? last : new IOException("缺少完整包下载地址");
    } finally {
      try {
        saveBytes(task, downloaded.get());
      } catch (IOException unavailable) {
        /* 不覆盖原取消或失败原因 */
      }
    }
  }

  public VerifiedApk resume(Cancellation cancellation, Progress progress) throws IOException {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IllegalStateException("恢复更新必须在后台线程运行");
    VerifiedPending pending = pending();
    if (pending == null) throw new IOException("没有可恢复的更新任务");
    return prepare(pending.request, cancellation, progress);
  }

  /** 后台认证查询，不下载、不自动恢复；非法签名仍直接拒绝。 */
  public VerifiedPending pending() throws IOException {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IllegalStateException("查询待恢复更新必须在后台线程运行");
    Request request = readPendingRequest();
    if (request == null) return null;
    UpdateManifest manifest = authenticateRequest(request);
    ApkInspector.Installed installed = inspector.installed();
    if (manifest.target.versionCode <= installed.versionCode) return null;
    inspector.verifyIdentity(manifest, installed);
    return new VerifiedPending(
        request, manifest.target, loadBytes(new File(root, manifest.target.sha256)));
  }

  private UpdateManifest authenticateRequest(Request request) {
    UpdateManifest manifest = UpdateManifest.authenticate(request.delivery, verifier);
    if (manifest.target.versionCode != request.outerVersionCode
        || manifest.target.size != request.outerSize
        || !manifest.target.sha256.equals(request.outerSha256))
      throw new SecurityException("外层全包身份与签名说明不匹配");
    return manifest;
  }

  private Request readPendingRequest() throws IOException {
    File request = new File(root, "active-request.json");
    SafeFiles.rejectLink(request);
    if (!request.exists()) return null;
    if (!request.isFile() || request.length() > 256 * 1024) throw new IOException("更新恢复记录损坏");
    try {
      return Request.fromJson(
          new JSONObject(new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8)));
    } catch (org.json.JSONException malformed) {
      throw new IOException("更新恢复记录损坏", malformed);
    }
  }

  private void saveRequest(Request request) throws IOException {
    try {
      atomicWrite(new File(root, "active-request.json"), request.toJson().toString());
    } catch (org.json.JSONException invalid) {
      throw new IOException("更新请求无法保存", invalid);
    }
  }

  private String previousActiveTarget() throws IOException {
    File active = new File(root, "active-request.json");
    SafeFiles.rejectLink(active);
    if (!active.isFile() || active.length() > 256 * 1024) return null;
    try {
      String value =
          new JSONObject(new String(Files.readAllBytes(active.toPath()), StandardCharsets.UTF_8))
              .getString("sha256");
      return value.matches("[0-9a-f]{64}") ? value : null;
    } catch (org.json.JSONException damaged) {
      return null;
    }
  }

  private static void commit(File output, File ready, Cancellation cancellation)
      throws IOException {
    cancellation.check();
    try (RandomAccessFile file = new RandomAccessFile(output, "rw")) {
      file.getFD().sync();
    }
    Files.move(
        output.toPath(),
        ready.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
    SafeFiles.syncDirectory(ready.getParentFile());
  }

  private static long loadBytes(File task) {
    try {
      File file = new File(task, "received.txt");
      SafeFiles.rejectLink(file);
      if (file.length() > 32) return 0;
      return Math.max(
          0, Long.parseLong(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
    } catch (Exception ignored) {
      return 0;
    }
  }

  private static void saveBytes(File task, long bytes) throws IOException {
    atomicWrite(new File(task, "received.txt"), Long.toString(bytes));
  }

  private static void atomicWrite(File target, String text) throws IOException {
    File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
    SafeFiles.rejectLink(target);
    SafeFiles.rejectLink(temporary);
    try (FileOutputStream output = new FileOutputStream(temporary)) {
      output.write(text.getBytes(StandardCharsets.UTF_8));
      output.getFD().sync();
    }
    Files.move(
        temporary.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
    SafeFiles.syncDirectory(target.getParentFile());
  }

  public static final class Request {
    public final SignedDelivery delivery;
    public final List<String> fullUrls;
    public final long outerVersionCode, outerSize;
    public final String outerSha256;

    public Request(
        SignedDelivery delivery,
        List<String> fullUrls,
        long outerVersionCode,
        String outerSha256,
        long outerSize) {
      if (fullUrls.size() > 8) throw new IllegalArgumentException("更新源过多");
      this.delivery = delivery;
      this.fullUrls = java.util.Collections.unmodifiableList(new ArrayList<>(fullUrls));
      this.outerVersionCode = outerVersionCode;
      this.outerSha256 = outerSha256;
      this.outerSize = outerSize;
    }

    JSONObject toJson() throws org.json.JSONException {
      return new JSONObject()
          .put("delivery", delivery.toJson())
          .put("fullUrls", new JSONArray(fullUrls))
          .put("versionCode", outerVersionCode)
          .put("sha256", outerSha256)
          .put("size", outerSize);
    }

    static Request fromJson(JSONObject object) throws org.json.JSONException {
      List<String> urls = new ArrayList<>();
      JSONArray list = object.getJSONArray("fullUrls");
      if (list.length() > 8) throw new org.json.JSONException("更新源过多");
      for (int i = 0; i < list.length(); i++) urls.add(list.getString(i));
      return new Request(
          SignedDelivery.fromJson(object.getJSONObject("delivery")),
          urls,
          object.getLong("versionCode"),
          object.getString("sha256"),
          object.getLong("size"));
    }
  }

  public static final class VerifiedApk {
    public final File file;
    public final UpdateManifest.Target target;
    public final long downloadedBytes;
    public final boolean usedDelta;
    public final String fallbackReason;

    VerifiedApk(
        File file,
        UpdateManifest.Target target,
        long downloadedBytes,
        boolean usedDelta,
        String fallbackReason) {
      this.file = file;
      this.target = target;
      this.downloadedBytes = downloadedBytes;
      this.usedDelta = usedDelta;
      this.fallbackReason = fallbackReason;
    }
  }

  public static final class VerifiedPending {
    public final Request request;
    public final UpdateManifest.Target target;
    public final long downloadedBytes;

    VerifiedPending(Request request, UpdateManifest.Target target, long downloadedBytes) {
      this.request = request;
      this.target = target;
      this.downloadedBytes = downloadedBytes;
    }
  }
}
