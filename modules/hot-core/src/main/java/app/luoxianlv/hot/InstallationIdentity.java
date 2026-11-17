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
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/** 安装凭据仅保存在应用内部目录；不依赖登录账号，不导出到诊断或公共下载区。 */
public final class InstallationIdentity {
  public final String id, applicationId, environment;
  final String secret;

  private InstallationIdentity(String id, String app, String environment, String secret) {
    StrictJson.require(
        UUID.fromString(id).toString().equals(id)
            && HotManifest.validScope(app, environment)
            && HotManifest.validHash(secret),
        "安装身份格式无效");
    this.id = id;
    applicationId = app;
    this.environment = environment;
    this.secret = secret;
  }

  String credential() {
    return id + "." + secret;
  }

  /** 凭据仅写入宿主已验证的请求，不通过业务桥返回长期秘密。 */
  public void authorize(java.net.HttpURLConnection connection, String header) {
    if (!header.equals("Authorization") && !header.equals("X-Installation-Authorization"))
      throw new IllegalArgumentException("安装授权请求头无效");
    connection.setRequestProperty(header,
        header.equals("Authorization") ? "Bearer " + credential() : credential());
  }

  public byte[] registration(long hostContract, String hostIdentity, long versionCode) {
    StrictJson.require(hostContract > 0 && hostContract <= Integer.MAX_VALUE
        && versionCode > 0 && versionCode <= Integer.MAX_VALUE
        && HotManifest.validHash(hostIdentity), "安装登记资料无效");
    return JsonWire.encode(JsonWire.fields("installationId", id, "secret", secret,
        "applicationId", applicationId, "environment", environment,
        "hostContract", hostContract, "hostIdentity", hostIdentity,
        "appVersionCode", versionCode, "deltaCapability", HotByteDeltaPlan.ALGORITHM));
  }

  public static InstallationIdentity open(File directory, String app, String environment)
      throws Exception {
    StrictJson.require(
        HotManifest.validScope(app, environment)
            && !Files.isSymbolicLink(directory.toPath())
            && (directory.isDirectory() || directory.mkdirs()),
        "安装身份目录或范围无效");
    File file = new File(directory, "installation.bin");
    try (FileChannel channel =
            FileChannel.open(
                new File(directory, "installation.lock").toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock()) {
      StrictJson.require(lock != null, "另一个控制器正在登记安装身份");
      if (Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        StrictJson.require(
            file.isFile() && file.length() <= 1024 && !Files.isSymbolicLink(file.toPath()),
            "安装身份损坏，不能悄悄重置灰度分组");
        byte[] raw = Files.readAllBytes(file.toPath());
        StrictJson.require(raw.length >= 40 && raw.length <= 1024, "安装记录大小无效");
        byte[] body = Arrays.copyOf(raw, raw.length - 32);
        StrictJson.require(
            MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(body),
                Arrays.copyOfRange(raw, raw.length - 32, raw.length)),
            "安装记录校验失败");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
          StrictJson.require(input.readInt() == 0x4c584849 && input.readInt() == 1, "安装记录版本无效");
          InstallationIdentity saved =
              new InstallationIdentity(
                  input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF());
          StrictJson.require(
              input.available() == 0
                  && saved.applicationId.equals(app)
                  && saved.environment.equals(environment),
              "安装记录不属于当前应用或环境");
          return saved;
        }
      }
      byte[] secret = new byte[32];
      new SecureRandom().nextBytes(secret);
      InstallationIdentity created =
          new InstallationIdentity(
              UUID.randomUUID().toString(), app, environment, HotSignatures.hex(secret));
      Arrays.fill(secret, (byte) 0);
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(body)) {
        output.writeInt(0x4c584849);
        output.writeInt(1);
        output.writeUTF(created.id);
        output.writeUTF(app);
        output.writeUTF(environment);
        output.writeUTF(created.secret);
      }
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray());
      body.write(digest);
      File temp = File.createTempFile("installation-", ".part", directory);
      try {
        ContentStore.writeSynced(temp.toPath(), body.toByteArray());
        ContentStore.replaceSynced(temp, file);
      } finally {
        Files.deleteIfExists(temp.toPath());
      }
      return created;
    }
  }
}
