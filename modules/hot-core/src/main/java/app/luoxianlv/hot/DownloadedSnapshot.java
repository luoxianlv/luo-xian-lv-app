package app.luoxianlv.hot;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 外部暂存不因先前验过就可信；每次进入内部只读库时重新流式验签名哈希。 */
public final class DownloadedSnapshot implements SnapshotSource {
  private final SignedSnapshot metadata;
  private final Map<String, File> files;

  public DownloadedSnapshot(SignedSnapshot metadata, Map<String, File> files) {
    StrictJson.require(
        metadata.manifest.objects.keySet().containsAll(files.keySet()), "下载含清单之外的对象");
    this.metadata = metadata;
    this.files = Collections.unmodifiableMap(new HashMap<>(files));
  }

  @Override
  public SignedSnapshot metadata() {
    return metadata;
  }

  @Override
  public Set<String> included() {
    return files.keySet();
  }

  @Override
  public String baseline() {
    return "";
  }

  @Override
  public void copyObject(String hash, OutputStream destination) throws Exception {
    File file = files.get(hash);
    Long size = metadata.manifest.objects.get(hash);
    StrictJson.require(
        file != null
            && size != null
            && file.isFile()
            && file.length() == size
            && !Files.isSymbolicLink(file.toPath()),
        "下载暂存缺失、越界或已改变");
    try (InputStream input = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
      ContentStore.copyVerified(input, destination, hash, size);
    }
  }
}
