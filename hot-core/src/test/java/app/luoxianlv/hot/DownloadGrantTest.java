package app.luoxianlv.hot;

import static org.junit.Assert.*;

import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DownloadGrantTest {
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();
  private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

  @Test public void eachConnectionGetsFreshGrantAndNeverSendsInstallationCredentialToObjectOrigin() throws Exception {
    SignedSnapshot target = target();
    String hash = target.manifest.business.sha256;
    int size = target.manifest.objects.get(hash).intValue();
    var identity = InstallationIdentity.open(temporary.newFolder(), "app.luoxianlv.debug", "test");
    AtomicInteger grants = new AtomicInteger(), requests = new AtomicInteger();
    AtomicReference<String> directAuthorization = new AtomicReference<>(), grantAuthorization = new AtomicReference<>();
    try (LoopbackHttp object = new LoopbackHttp(request -> {
      directAuthorization.set(request.headers.get("authorization"));
      if (requests.incrementAndGet() == 1) return LoopbackHttp.reply(403, "", new byte[0]);
      assertEquals("bytes=1-", request.headers.get("range"));
      return LoopbackHttp.reply(206, "Content-Range: bytes 1-" + (size - 1) + "/" + size + "\r\n", new byte[size - 1]);
    }); LoopbackHttp api = new LoopbackHttp(request -> {
      if (request.path.endsWith("/installations")) return registered(request);
      grantAuthorization.set(request.headers.get("authorization"));
      assertEquals(target.manifest.snapshotId, StrictJson.object(request.body).string("snapshotId"));
      return reply(grant(hash, size, "oss", object.origin().resolve("/object?x-oss-signature=public-secret-sentinel-" + grants.incrementAndGet()).toString()));
    })) {
      var client = new HotApiClient(api.origin(), identity, 1, "a".repeat(64), true);
      HttpObjectSource source = client.object(target, hash);
      assertEquals(0, grants.get());
      var expired = assertThrows(HttpObjectSource.Failure.class, () -> source.open(0));
      assertEquals(403, expired.status);
      assertFalse(trace(expired).contains("public-secret-sentinel"));
      try (var range = source.open(1)) { assertEquals(size - 1, range.body.readAllBytes().length); }
      assertEquals(2, grants.get());
      assertNull(directAuthorization.get());
      assertEquals("Bearer " + identity.credential(), grantAuthorization.get());
    }
  }

  @Test public void localGrantKeepsCredentialOnlyOnExactSameOriginObjectPath() throws Exception {
    SignedSnapshot target = target(); String hash = target.manifest.business.sha256;
    int size = target.manifest.objects.get(hash).intValue();
    var identity = InstallationIdentity.open(temporary.newFolder(), "app.luoxianlv.debug", "test");
    AtomicInteger actualDownloads = new AtomicInteger();
    try (LoopbackHttp api = new LoopbackHttp(request -> {
      if (request.path.endsWith("/installations")) return registered(request);
      assertEquals("Bearer " + identity.credential(), request.headers.get("authorization"));
      String path = "/api/hot/v2/objects/" + hash + "?snapshotId=" + target.manifest.snapshotId;
      if (request.path.endsWith("/grant")) return reply(grant(hash, size, "local-test", path));
      assertEquals(path, request.path); actualDownloads.incrementAndGet();
      return LoopbackHttp.reply(200, "", new byte[size]);
    })) {
      var source = new HotApiClient(api.origin(), identity, 1, "a".repeat(64), true).object(target, hash);
      try (var result = source.open(0)) { assertEquals(size, result.body.readAllBytes().length); }
      assertEquals(1, actualDownloads.get());
    }
  }

  @Test public void invalidGrantNeverConnectsAndDiagnosticsContainNoSignedUrl() throws Exception {
    SignedSnapshot target = target(); String hash = target.manifest.business.sha256;
    int size = target.manifest.objects.get(hash).intValue();
    for (int mutation = 0; mutation < 8; mutation++) {
      final int change = mutation;
      var identity = InstallationIdentity.open(temporary.newFolder(), "app.luoxianlv.debug", "test");
      try (LoopbackHttp api = new LoopbackHttp(request -> {
        if (request.path.endsWith("/installations")) return registered(request);
        Map<String,Object> value = grant(hash,size,"oss","https://objects.example.invalid/object?sig=public-secret-sentinel");
        switch(change) {
          case 0: value.put("method","PUT"); break;
          case 1: value.put("sha256","b".repeat(64)); break;
          case 2: value.put("size",size+1L); break;
          case 3: value.put("expiresAt",NOW.getEpochSecond()); break;
          case 4: value.put("url","https://user:public-secret-sentinel@example.invalid/[bad"); break;
          case 5: value.put("headers",Map.of("authorization","public-secret-sentinel")); break;
          case 6: value.put("transport","local-test"); break;
          case 7: value.put("transport","unknown"); break;
          default: throw new AssertionError();
        }
        return reply(value);
      })) {
        var source = new HotApiClient(api.origin(),identity,1,"a".repeat(64),true).object(target,hash);
        Exception failure = assertThrows(Exception.class, () -> source.open(0));
        assertFalse(trace(failure).contains("public-secret-sentinel"));
      }
    }
  }
  private static Map<String,Object> grant(String hash,long size,String transport,String url) {
    return JsonWire.fields("transport",transport,"method","GET","url",url,"headers",Collections.emptyMap(),
        "sha256",hash,"size",size,"expiresAt",NOW.getEpochSecond()+120);
  }
  private static byte[] registered(LoopbackHttp.Request request) throws Exception {
    String id = StrictJson.object(request.body).string("installationId");
    return reply(JsonWire.fields("installationId",id,"credentialFormat","Bearer <installationId>.<secret>","scope","installation"));
  }
  private static byte[] reply(Map<String,?> value) throws Exception {
    return LoopbackHttp.reply(200,"X-Lxhot-Time: " + NOW.getEpochSecond() + "\r\n",JsonWire.encode(value));
  }
  private static String trace(Throwable failure) {StringWriter text=new StringWriter();failure.printStackTrace(new PrintWriter(text));return text.toString();}
  private SignedSnapshot target() throws Exception {
    byte[] root;
    try(InputStream input=getClass().getResourceAsStream("/protocol-v1/root.public.json")){root=input.readAllBytes();}
    File file=temporary.newFile();
    try(InputStream input=getClass().getResourceAsStream("/protocol-v1/target.lxhp")){Files.write(file.toPath(),input.readAllBytes());}
    try(HotPackage archive=new HotPackage(file,new HotPackage.Policy(new HotSignatures.PublicKey(StrictJson.object(root)),
        "app.luoxianlv.debug","test",1,1,NOW,Collections.emptySet(),null,null))){return archive.metadata();}
  }
}
