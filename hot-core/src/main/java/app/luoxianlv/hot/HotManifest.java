package app.luoxianlv.hot;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 完整快照的不可变声明；是否能即时替换还由激活安全点与运行时身份决定。 */
public final class HotManifest {
  public static final long MAX_EXPANDED = 512L << 20;
  public static final int MAX_OBJECTS = 5000;
  private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
  private static final Pattern ID = Pattern.compile("[a-z][a-z0-9._-]{0,95}");
  private static final Pattern APP =
      Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
  private static final Pattern CLASS =
      Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
  private static final Pattern UTC =
      Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-5][0-9](\\.[0-9]{1,9})?Z");
  public final String snapshotId;
  public final String applicationId;
  public final String environment;
  public final String label;
  public final String runtimeAbi;
  public final String activation;
  public final long hostMin, hostMax, stateCurrent, stateMin, stateMax;
  public final List<Artifact> artifacts;
  public final Map<String, Long> objects;
  public final Artifact runtime, business;

  public static final class Artifact {
    public final String id, role, sha256, entryClass, mount;
    public final long size;
    public final List<String> requires;

    Artifact(StrictJson.Obj value) {
      value.only("id", "role", "sha256", "size", "requires", "entryClass", "mount", "build");
      id = value.string("id");
      role = value.string("role");
      sha256 = value.string("sha256");
      size = value.number("size");
      entryClass = value.optionalString("entryClass");
      mount = value.optionalString("mount");
      requires = value.strings("requires");
      StrictJson.require(
          validId(id) && validHash(sha256) && size > 0 && size <= MAX_EXPANDED, "产物身份或大小无效");
      if (role.equals("runtime"))
        StrictJson.require(
            requires.isEmpty() && entryClass.isEmpty() && mount.isEmpty(), "运行时依赖或入口无效");
      else if (role.equals("business"))
        StrictJson.require(CLASS.matcher(entryClass).matches() && mount.isEmpty(), "业务入口无效");
      else if (role.equals("resources") || role.equals("config"))
        StrictJson.require(validId(mount) && entryClass.isEmpty(), "资源挂载点无效");
      else throw new IllegalArgumentException("产物角色不支持");
      if (value.has("build")) {
        StrictJson.Obj build =
            value
                .object("build")
                .only(
                    "commit",
                    "toolchain",
                    "r8",
                    "resourceShrink",
                    "exportFingerprint",
                    "mappingHash");
        build.optionalString("commit");
        build.optionalString("toolchain");
        build.optionalString("exportFingerprint");
        if (build.has("r8")) build.bool("r8");
        if (build.has("resourceShrink")) build.bool("resourceShrink");
        StrictJson.require(
            !build.has("mappingHash")
                || build.string("mappingHash").isEmpty()
                || validHash(build.string("mappingHash")),
            "mapping 哈希无效");
      }
    }
  }

  public HotManifest(byte[] raw) throws Exception {
    StrictJson.Obj value =
        StrictJson.object(raw)
            .only(
                "schema",
                "kind",
                "applicationId",
                "environment",
                "label",
                "createdAt",
                "hostContract",
                "runtimeAbi",
                "activation",
                "stateSchema",
                "artifacts",
                "notes");
    StrictJson.require(
        value.number("schema") == 1 && value.string("kind").equals("hot"), "只支持 v1 热更清单");
    snapshotId = HotSignatures.hash(raw);
    applicationId = value.string("applicationId");
    environment = value.string("environment");
    StrictJson.require(validScope(applicationId, environment), "应用或环境无效");
    label = value.string("label");
    runtimeAbi = value.string("runtimeAbi");
    activation = value.string("activation");
    StrictJson.require(
        !label.isEmpty()
            && utf8Size(label) <= 200
            && utf8Size(value.optionalString("notes")) <= 4000,
        "版本名或说明过长");
    utc(value.string("createdAt"));
    StrictJson.require(
        validId(runtimeAbi) && (activation.equals("live") || activation.equals("restart")),
        "运行时 ABI 或生效方式无效");
    StrictJson.Obj host = value.object("hostContract").only("min", "max");
    hostMin = host.number("min");
    hostMax = host.number("max");
    range(hostMin, hostMax);
    StrictJson.Obj state = value.object("stateSchema").only("current", "readable");
    StrictJson.Obj readable = state.object("readable").only("min", "max");
    stateCurrent = state.number("current");
    stateMin = readable.number("min");
    stateMax = readable.number("max");
    range(stateMin, stateMax);
    StrictJson.require(stateCurrent >= stateMin && stateCurrent <= stateMax, "状态格式不能读取自身版本");
    List<StrictJson.Obj> entries = value.objects("artifacts");
    StrictJson.require(entries.size() >= 2 && entries.size() <= MAX_OBJECTS, "产物数量无效");
    List<Artifact> result = new ArrayList<>();
    Map<String, Artifact> byId = new LinkedHashMap<>();
    Map<String, Long> sizes = new LinkedHashMap<>();
    Set<String> mounts = new HashSet<>();
    Artifact foundRuntime = null, foundBusiness = null;
    long total = 0;
    for (StrictJson.Obj entry : entries) {
      Artifact artifact = new Artifact(entry);
      result.add(artifact);
      StrictJson.require(byId.put(artifact.id, artifact) == null, "产物 ID 重复");
      Long prior = sizes.put(artifact.sha256, artifact.size);
      if (prior == null) total += artifact.size;
      else StrictJson.require(prior == artifact.size, "同一对象大小声明冲突");
      StrictJson.require(total <= MAX_EXPANDED, "快照展开大小超限");
      if (!artifact.mount.isEmpty()) StrictJson.require(mounts.add(artifact.mount), "资源挂载点重复");
      if (artifact.role.equals("runtime")) {
        StrictJson.require(foundRuntime == null, "运行时重复");
        foundRuntime = artifact;
      }
      if (artifact.role.equals("business")) {
        StrictJson.require(foundBusiness == null, "核心业务重复");
        foundBusiness = artifact;
      }
    }
    StrictJson.require(foundRuntime != null && foundBusiness != null, "必须有一个运行时和一个核心业务");
    runtime = foundRuntime;
    business = foundBusiness;
    StrictJson.require(business.requires.contains(runtime.id), "核心业务必须显式依赖运行时");
    validateGraph(byId);
    artifacts = Collections.unmodifiableList(result);
    objects = Collections.unmodifiableMap(sizes);
  }

  private static void validateGraph(Map<String, Artifact> artifacts) {
    Map<String, Integer> remaining = new HashMap<>();
    Map<String, List<String>> dependents = new HashMap<>();
    ArrayDeque<String> ready = new ArrayDeque<>();
    for (Artifact artifact : artifacts.values()) {
      Set<String> seen = new HashSet<>();
      for (String dependency : artifact.requires) {
        StrictJson.require(artifacts.containsKey(dependency) && seen.add(dependency), "产物依赖缺失或重复");
        dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(artifact.id);
      }
      remaining.put(artifact.id, seen.size());
      if (seen.isEmpty()) ready.add(artifact.id);
    }
    int processed = 0;
    while (!ready.isEmpty()) {
      String id = ready.remove();
      processed++;
      for (String child : dependents.getOrDefault(id, Collections.emptyList())) {
        int count = remaining.get(child) - 1;
        remaining.put(child, count);
        if (count == 0) ready.add(child);
      }
    }
    StrictJson.require(processed == artifacts.size(), "产物依赖存在循环");
  }

  public void compatible(String app, String env, long host, Set<String> mounts) {
    StrictJson.require(applicationId.equals(app) && environment.equals(env), "应用或环境不兼容");
    StrictJson.require(host >= hostMin && host <= hostMax, "宿主契约不兼容");
    for (Artifact artifact : artifacts)
      if (!artifact.mount.isEmpty())
        StrictJson.require(mounts.contains(artifact.mount), "宿主不支持挂载点: " + artifact.mount);
  }

  static void range(long min, long max) {
    StrictJson.require(min >= 1 && max >= min && max <= Integer.MAX_VALUE, "兼容区间无效");
  }

  static int utf8Size(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  public static boolean validHash(String text) {
    return HASH.matcher(text).matches();
  }

  public static boolean validId(String text) {
    return ID.matcher(text).matches();
  }

  public static boolean validScope(String app, String env) {
    return APP.matcher(app).matches() && (env.equals("test") || env.equals("production"));
  }

  static Instant utc(String value) {
    StrictJson.require(UTC.matcher(value).matches(), "时间必须使用 UTC RFC3339");
    return Instant.parse(value);
  }
}
