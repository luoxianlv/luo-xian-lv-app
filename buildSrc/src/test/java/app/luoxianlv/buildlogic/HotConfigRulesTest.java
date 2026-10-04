package app.luoxianlv.buildlogic;

import static org.junit.Assert.*;

import app.luoxianlv.hot.HostConfigRules;
import app.luoxianlv.hot.HotSignatures;
import app.luoxianlv.hot.StrictJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 以设备同源规则预检实际变体，错误配置不能生成或保留可打包资产。 */
public final class HotConfigRulesTest {
  private static final String APP = "app.luoxianlv";
  private static final String DEBUG_APP = APP + ".debug";
  private static final String HASH = "a".repeat(64);
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void validVariantsRetainOriginalBytesAndDefaults() throws Exception {
    String debug =
        config(DEBUG_APP, "test", "http://127.0.0.1:8787", ",\"testHealthReports\":true");
    var accepted = HostConfigRules.validate(bytes(debug), DEBUG_APP, true);
    assertTrue(accepted.automatic);
    assertTrue(accepted.testHealthReports);
    assertEquals("root", accepted.root.purpose);
    assertEquals(java.util.Set.of("scores"), accepted.mounts);
    assertThrows(UnsupportedOperationException.class, () -> accepted.mounts.add("another"));
    var production =
        HostConfigRules.validate(
            bytes(
                config(
                    APP, "production", "https://updates.example.invalid/", ",\"automatic\":false")),
            APP,
            false);
    assertFalse(production.automatic);
    assertFalse(production.testHealthReports);
    Path source = temporary.newFile("public-config.json").toPath();
    Path target = temporary.getRoot().toPath().resolve("assets/hot/config.json");
    byte[] raw = bytes(" \n" + debug + "\r\n");
    Files.write(source, raw);
    CopyHotConfig.validateAndCopy(source, target, DEBUG_APP, true);
    assertArrayEquals(raw, Files.readAllBytes(target));
    try (var siblings = Files.list(target.getParent())) {
      assertEquals(1, siblings.count());
    }
  }

  @Test
  public void identityAndTestFacilitiesUseActualVariantInputs() throws Exception {
    rejected(config(DEBUG_APP, "test", "http://127.0.0.1:8787", ""), APP, false);
    rejected(config(APP, "test", "http://127.0.0.1:8787", ""), APP, false);
    rejected(
        config(APP, "test", "https://updates.example.invalid", ",\"testHealthReports\":true"),
        APP,
        false);
    rejected(
        config(APP, "production", "https://updates.example.invalid", ",\"testHealthReports\":true"),
        APP,
        true);
    rejected(config(APP, "production", "http://127.0.0.1:8787", ""), APP, true);
    // 变体名没有参与规则：自定义 debuggable 包可测试，非 debuggable 的 .debug 包仍拒绝 HTTP。
    HostConfigRules.validate(bytes(config(APP, "test", "http://127.0.0.1:8787", "")), APP, true);
    rejected(config(DEBUG_APP, "test", "http://127.0.0.1:8787", ""), DEBUG_APP, false);
  }

  @Test
  public void originRulesRejectCredentialsPathsAndUntrustedHttp() throws Exception {
    for (String origin :
        new String[] {
          "http://localhost:8787",
          "http://10.0.2.2:8787",
          "http://192.168.1.2:8787",
          "https://user:password@example.invalid",
          "https://example.invalid?key=value",
          "https://example.invalid/#fragment",
          "https://example.invalid/path",
          "https://example.invalid/%2e",
          "file:///tmp/source",
          "https:/missing-host",
          "https://example.invalid/[bad"
        }) {
      rejected(config(DEBUG_APP, "test", origin, ""), DEBUG_APP, true);
    }
  }

  @Test
  public void rootIdentityPurposeAndRealCurveAreVerified() throws Exception {
    String valid = config(APP, "production", "https://updates.example.invalid", "");
    var key = fixture();
    for (String root :
        new String[] {
          root("content", key.string("algorithm"), key.string("keyId"), key.string("spki")),
          root("activation", key.string("algorithm"), key.string("keyId"), key.string("spki")),
          root("root", "ecdsa-p384-sha256", key.string("keyId"), key.string("spki")),
          root("root", key.string("algorithm"), HASH, key.string("spki")),
          root("root", key.string("algorithm"), key.string("keyId"), "not-base64")
        }) {
      rejected(valid.replace(root(), root), APP, false);
    }
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp384r1"));
    byte[] p384 = generator.generateKeyPair().getPublic().getEncoded();
    String wrongCurve =
        root(
            "root",
            HotSignatures.ALGORITHM,
            HotSignatures.hash(p384),
            Base64.getEncoder().encodeToString(p384));
    rejected(valid.replace(root(), wrongCurve), APP, false);
    rejected(valid.replace("\"root\":{", "\"root\":{\"extra\":true,"), APP, false);
  }

  @Test
  public void strictJsonAndConfigurationBoundsFailClosed() throws Exception {
    String valid = config(APP, "production", "https://updates.example.invalid", "");
    for (String invalid :
        new String[] {
          "\ufeff" + valid,
          valid + "{}",
          valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
          valid.replace(
              "\"applicationId\":", "\"applic\\u0061tionId\":\"wrong\",\"applicationId\":"),
          valid.replace("\"purpose\":\"root\"", "\"purpose\":\"root\",\"purpose\":\"root\""),
          valid.replace("\"schema\":1", "\"schema\":2"),
          valid.replace("\"hostContract\":1", "\"hostContract\":1.0"),
          valid.replace("\"hostContract\":1", "\"hostContract\":0"),
          valid.replace("\"hostContract\":1", "\"hostContract\":2147483648"),
          valid.replace("\"production\"", "\"other\""),
          valid.replace(HASH, "A".repeat(64)),
          valid.replace("[\"scores\"]", "[\"scores\",\"scores\"]"),
          valid.replace("[\"scores\"]", "[\"../scores\"]"),
          valid.replace("\"root\":", "\"undeclared\":true,\"root\":"),
          config(APP, "production", "https://updates.example.invalid", ",\"automatic\":null"),
          config(APP, "production", "https://updates.example.invalid", ",\"automatic\":\"true\"")
        }) {
      rejected(invalid, APP, false);
    }
    assertThrows(
        Exception.class,
        () -> HostConfigRules.validate(new byte[] {(byte) 0xc3, 0x28}, APP, false));
    assertThrows(Exception.class, () -> HostConfigRules.validate(new byte[0], APP, false));
    byte[] atLimit = bytes(valid + " ".repeat(HostConfigRules.MAX_BYTES - bytes(valid).length));
    HostConfigRules.validate(atLimit, APP, false);
    assertThrows(
        Exception.class,
        () ->
            HostConfigRules.validate(
                java.util.Arrays.copyOf(atLimit, atLimit.length + 1), APP, false));
  }

  @Test
  public void copyFailureRemovesStaleAssetAndSanitizesDiagnostics() throws Exception {
    Path source = temporary.newFile("public-config.json").toPath();
    Path target = temporary.getRoot().toPath().resolve("assets/hot/config.json");
    Files.createDirectories(target.getParent());
    String sentinel = "private-value-must-not-appear";
    for (String invalid :
        new String[] {
          config(APP, "production", "https://user:" + sentinel + "@example.invalid/[bad", ""),
          config(APP, "production", "https://example.invalid", ",\"" + sentinel + "\":true")
        }) {
      Files.write(source, bytes(invalid));
      Files.writeString(target, "stale-config");
      var failure =
          assertThrows(
              Exception.class, () -> CopyHotConfig.validateAndCopy(source, target, APP, false));
      assertFalse(failure.toString().contains(sentinel));
      assertNull(failure.getCause());
      assertFalse(Files.exists(target));
      try (var siblings = Files.list(target.getParent())) {
        assertEquals(0, siblings.count());
      }
    }
    Files.write(source, new byte[HostConfigRules.MAX_BYTES + 1]);
    Files.writeString(target, "stale-config");
    assertThrows(Exception.class, () -> CopyHotConfig.validateAndCopy(source, target, APP, false));
    assertFalse(Files.exists(target));
    byte[] original = bytes(config(APP, "production", "https://example.invalid", ""));
    Files.write(source, original);
    assertThrows(Exception.class, () -> CopyHotConfig.validateAndCopy(source, source, APP, false));
    assertArrayEquals(original, Files.readAllBytes(source));
  }

  @Test
  public void generatedConfigBindsActualSdkWithoutChangingAuthority() throws Exception {
    Path source = temporary.newFile("sdk-config.json").toPath();
    Path sdk = temporary.newFile("contract.jar").toPath();
    Path target = temporary.getRoot().toPath().resolve("generated/hot/config.json");
    byte[] original = bytes(config(APP, "production", "https://updates.example.invalid", ""));
    Files.write(source, original);
    Files.write(sdk, bytes("actual-sdk-contract"));
    CopyHotConfig.validateAndCopy(source, target, APP, false, sdk);
    var generated = HostConfigRules.validate(Files.readAllBytes(target), APP, false);
    assertEquals(HotSignatures.hash(Files.readAllBytes(sdk)), generated.fingerprint);
    assertEquals(fixture().string("keyId"), generated.root.id);
    assertEquals("production", generated.environment);
    assertArrayEquals(original, Files.readAllBytes(source));
    assertThrows(
        Exception.class,
        () ->
            CopyHotConfig.validateAndCopy(
                source, target, APP, false, sdk.resolveSibling("missing")));
    assertFalse(Files.exists(target));
  }

  private static void rejected(String raw, String applicationId, boolean debuggable) {
    assertThrows(
        Exception.class, () -> HostConfigRules.validate(bytes(raw), applicationId, debuggable));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String config(String app, String environment, String origin, String extra)
      throws Exception {
    return "{\"schema\":1,\"applicationId\":"
        + quote(app)
        + ",\"environment\":"
        + quote(environment)
        + ",\"hostContract\":1,\"fingerprint\":"
        + quote(HASH)
        + ",\"origin\":"
        + quote(origin)
        + ",\"root\":"
        + root()
        + ",\"mounts\":[\"scores\"]"
        + extra
        + "}";
  }

  private static StrictJson.Obj fixture() throws Exception {
    try (var input =
        HotConfigRulesTest.class.getResourceAsStream("/protocol-v1/root.public.json")) {
      assertNotNull(input);
      return StrictJson.object(input.readAllBytes());
    }
  }

  private static String root() throws Exception {
    var key = fixture();
    return root(
        key.string("purpose"), key.string("algorithm"), key.string("keyId"), key.string("spki"));
  }

  private static String root(String purpose, String algorithm, String id, String spki) {
    return "{\"schema\":1,\"algorithm\":"
        + quote(algorithm)
        + ",\"keyId\":"
        + quote(id)
        + ",\"purpose\":"
        + quote(purpose)
        + ",\"spki\":"
        + quote(spki)
        + "}";
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
