package app.luoxianlv.host;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Looper;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONObject;

/** 通过生产简谱另存入口新增真实曲目；内部 FastKV 曲库不能冒充外部谱子文件。 */
final class NativeUserScoreFixture {
  private static final String TITLE_PREFIX = "原生热更验收谱 · ";
  private static final String SCORE =
      "tempo=96 unit=1\n1:1 2:0.5 3:0.5 0:0.5 [4:1] (#5:0.5) 6:1 i:1\n";
  private static final Set<String> SAVED_FIELDS =
      Set.of(
          "id", "title", "score", "bpm", "source", "remoteId", "contentVersion", "coreVersion",
          "needsFix");

  private NativeUserScoreFixture() {}

  /** 工作线程调用；只新增真实歌曲，恢复原 selected 键，新增曲目保留给后续热更验收。 */
  static JSONObject prepare(Instrumentation runner, Activity home) throws Exception {
    Access access = Access.open(runner, home);
    Map<String, ?> originalPrefs = new TreeMap<>(access.prefs.getAll());
    boolean hadSelection = originalPrefs.containsKey("selected");
    Object originalSelection = originalPrefs.get("selected");
    require(!hadSelection || originalSelection instanceof String, "原选曲偏好类型无效");
    String originalSettings = settingsHash(originalPrefs);
    JSONArray oldSongs = userSongs(access, null);
    String oldSongsHash = hash(canonical(oldSongs));
    validateSavedRecords(access); // 生产 write 会重新序列化；先拒绝可能丢失的旧记录和未知字段。
    Path report = new File(access.target.getFilesDir(), "native-user-score-fixture-report.json").toPath();
    Files.deleteIfExists(report); // 只撤销自身过期回执，不清理或覆盖旧用户曲目。
    String id = null;
    JSONObject result = null;
    Throwable failure = null;
    try {
      access.current();
      Object parser = access.parserType.getField("INSTANCE").get(null);
      int bpm =
          (Integer) access.parserType.getMethod("tempo", String.class, int.class).invoke(parser, SCORE, 120);
      require(bpm == 96, "生产 tempo 解析与合法测试谱不一致");
      String title = TITLE_PREFIX + UUID.randomUUID();
      Object added =
          access.repositoryType
              .getMethod(
                  "add", String.class, String.class, int.class, String.class, String.class, String.class)
              .invoke(access.repository, title, SCORE, bpm, "简谱", "", "");
      id = (String) getter(added, "getId");
      require(UUID.fromString(id).toString().equals(id), "生产保存没有生成合法 UUID");
      require(!containsId(oldSongs, id), "新增曲目覆盖了已有曲目 id");
      require(id.equals(access.prefs.getString("selected", null)), "生产保存没有选择新增曲目");
      require(
          oldSongsHash.equals(hash(canonical(userSongs(access, id)))),
          "生产保存改变或遗漏了已有用户曲目的业务字段");
      JSONObject song = songRecord(findSong(access, id));
      require(title.equals(song.getString("title")), "生产列表没有返回唯一测试曲名");
      checkFixture(song);
      result = readSnapshot(access, id);
      result
          .put("entry", "SongRepository.add / LibraryViewModel.saveAs")
          .put("created", true)
          .put("originalSelectionPresent", hadSelection)
          .put("selectionRestored", false)
          .put("existingUserSongsCount", oldSongs.length())
          .put("existingUserSongsHash", oldSongsHash)
          .put("existingUserSongsUnchanged", true);
    } catch (Throwable invalid) {
      failure = unwrap(invalid);
    } finally {
      try {
        // add 仅变更 selected；不调用服务 select，也不重置速度、悬浮窗或其他用户设置。
        access.current();
        String selected = access.prefs.getString("selected", null);
        require(
            Objects.equals(selected, originalSelection) || (id != null && id.equals(selected)),
            "准备期间出现其他选曲写入，拒绝覆盖用户的新选择");
        restoreSelection(access.prefs, hadSelection, (String) originalSelection);
        require(originalSettings.equals(settingsHash(access.prefs.getAll())), "曲库原设置未完整保持");
        if (result != null) result.put("selectionRestored", true);
      } catch (Throwable restoreFailed) {
        if (failure == null) failure = restoreFailed;
        else failure.addSuppressed(restoreFailed);
      }
    }
    if (failure != null) throw new AssertionError("真实用户曲谱准备未完成；已保存的新曲目不删除", failure);
    require(result != null && id != null, "缺少真实保存曲目");
    require(
        oldSongsHash.equals(hash(canonical(userSongs(access, id)))), "恢复选择后已有曲目发生变化");
    // 以选择恢复后的真实状态作为回退比较基线，不使用 add 暂时选中新曲目的状态。
    JSONObject restored = readSnapshot(access, id);
    for (String key : keys(restored)) result.put(key, restored.get(key));
    result.put("passed", true).put("allSongLibrarySettingsUnchanged", true).put("readOnly", false);
    writeReport(access.target, report, result);
    return result;
  }

  /** 只读：使用当前 Source 的生产 Repository、列表和解析器，返回可跨代比较的逻辑值。 */
  static JSONObject snapshot(Instrumentation runner, Activity home, String songId) throws Exception {
    return readSnapshot(Access.open(runner, home), songId);
  }

  /** 只读：新 Source 的记录、全部用户曲目、选曲及曲库设置须与提供的基线一致。 */
  static JSONObject verify(Instrumentation runner, Activity home, JSONObject before) throws Exception {
    require(before != null && before.getBoolean("passed"), "缺少成功的真实曲谱基线");
    JSONObject after = snapshot(runner, home, before.getString("songId"));
    for (String key :
        new String[] {
          "songHash", "eventsHash", "userSongsHash", "storedLocalSongsHash",
          "songLibrarySettingsHash", "selectedId", "selectedKeyPresent", "selectedStoredId", "storePath"
        }) {
      require(
          canonical(before.get(key)).equals(canonical(after.get(key))), "曲谱回退保留失败：" + key);
    }
    return after
        .put("verifiedAgainstSourceIdentity", before.getString("sourceIdentity"))
        .put("allUserSongsUnchanged", true)
        .put("allSongLibrarySettingsUnchanged", true)
        .put("selectionRestored", true)
        .put("readOnly", true);
  }

  private static JSONObject readSnapshot(Access access, String id) throws Exception {
    access.current();
    require(id != null && UUID.fromString(id).toString().equals(id), "验收歌曲 id 不是合法 UUID");
    String prefsBefore = hash(canonicalPreferences(access.prefs.getAll(), false));
    Object actual = findSong(access, id);
    JSONObject song = songRecord(actual);
    checkFixture(song);
    JSONArray events = events((List<?>) getter(actual, "getEvents"));
    Object parser = access.parserType.getField("INSTANCE").get(null);
    JSONArray parsed =
        events(
            (List<?>)
                access.parserType.getMethod("parse", String.class).invoke(parser, song.getString("score")));
    require(canonical(events).equals(canonical(parsed)), "生产 Song.events 与真实解析器事件不同");
    checkEvents(events);
    require((Integer) getter(actual, "getNoteCount") == 7, "生产可演奏音符数不是 7");
    require((Long) getter(actual, "getDurationMs") == 3750L, "生产谱面时长不是 3750ms");
    String savedText = access.prefs.getString("songs", null);
    require(savedText != null, "真实持久曲库没有 songs 字段");
    JSONArray saved = new JSONArray(savedText);
    JSONObject savedSong = savedRecord(saved, id);
    require(
        song.getString("title").equals(savedSong.getString("title"))
            && SCORE.equals(savedSong.getString("score"))
            && savedSong.getInt("bpm") == 96
            && "简谱".equals(savedSong.getString("source")),
        "真实持久记录与生产列表不一致");
    JSONObject storage = storage(access, savedText);
    JSONArray users = userSongs(access, null);
    access.current();
    require(
        prefsBefore.equals(hash(canonicalPreferences(access.prefs.getAll(), false))),
        "只读检查期间曲库偏好发生变化");
    return new JSONObject()
        .put("passed", true)
        .put("pid", android.os.Process.myPid())
        .put("sourceIdentity", access.source.prepared.identity())
        .put("reader", "SongRepository.songs / Song.events / ScoreParser.parse")
        .put("songId", id)
        .put("song", song)
        .put("songHash", hash(canonical(song)))
        .put("scoreUtf8Bytes", SCORE.getBytes(StandardCharsets.UTF_8).length)
        .put("scoreSha256", hash(SCORE))
        .put("eventCount", events.length())
        .put("noteCount", 7)
        .put("durationMs", 3750)
        .put("events", events)
        .put("eventsHash", hash(canonical(events)))
        .put("userSongsCount", users.length())
        .put("userSongsHash", hash(canonical(users)))
        .put("storedLocalSongsCount", saved.length())
        .put("storedLocalSongsHash", hash(canonical(saved)))
        .put("selectedId", access.repositoryType.getMethod("getSelectedId").invoke(access.repository))
        .put("selectedKeyPresent", access.prefs.contains("selected"))
        .put("selectedStoredId", access.prefs.contains("selected") ? access.prefs.getString("selected", null) : JSONObject.NULL)
        .put("songLibrarySettingsHash", settingsHash(access.prefs.getAll()))
        .put("storage", "internal-fastkv")
        .put("storePath", storage.getString("path"))
        .put("storeFiles", storage.getJSONArray("files"))
        .put("currentSongsJsonOnDisk", true)
        .put("externalScoreFile", false)
        .put("readOnly", true)
        .put("audioPlaybackVerified", false)
        .put("coldProcessReloadVerified", false)
        .put("updateStateInjected", false)
        .put("activationCalled", false)
        .put("healthInjected", false);
  }

  private static Object findSong(Access access, String id) throws Exception {
    Object found = null;
    for (Object song : access.songs()) {
      if (!id.equals(getter(song, "getId"))) continue;
      require(found == null, "生产列表包含重复验收 id");
      found = song;
    }
    require(found != null, "当前业务生产列表没有保留验收曲目");
    return found;
  }

  private static JSONArray userSongs(Access access, String excluded) throws Exception {
    TreeMap<String, JSONObject> ordered = new TreeMap<>();
    for (Object song : access.songs()) {
      JSONObject record = songRecord(song);
      String id = record.getString("id");
      if (record.getBoolean("builtIn") || Objects.equals(id, excluded)) continue;
      require(ordered.put(id, record) == null, "生产用户列表包含重复 id");
    }
    JSONArray result = new JSONArray();
    for (JSONObject record : ordered.values()) result.put(record);
    return result;
  }

  private static JSONObject songRecord(Object song) throws Exception {
    return new JSONObject()
        .put("id", getter(song, "getId"))
        .put("title", getter(song, "getTitle"))
        .put("score", getter(song, "getScore"))
        .put("bpm", getter(song, "getBpm"))
        .put("source", getter(song, "getSource"))
        .put("builtIn", getter(song, "getBuiltIn"))
        .put("contentVersion", getter(song, "getContentVersion"))
        .put("synced", getter(song, "getSynced"))
        .put("remoteId", getter(song, "getRemoteId"))
        .put("coreVersion", getter(song, "getCoreVersion"))
        .put("needsFix", getter(song, "getNeedsFix"));
  }

  private static JSONArray events(List<?> actual) throws Exception {
    JSONArray result = new JSONArray();
    for (Object event : actual) {
      result.put(
          new JSONObject()
              .put("keyIndex", getter(event, "getKeyIndex"))
              .put("mode", ((Enum<?>) getter(event, "getMode")).name())
              .put("beats", getter(event, "getBeats"))
              .put("rest", getter(event, "getRest"))
              .put("halfTone", getter(event, "getHalfTone")));
    }
    return result;
  }

  private static void checkFixture(JSONObject song) throws Exception {
    require(
        song.getString("title").startsWith(TITLE_PREFIX)
            && SCORE.equals(song.getString("score"))
            && song.getInt("bpm") == 96
            && "简谱".equals(song.getString("source"))
            && !song.getBoolean("builtIn")
            && !song.getBoolean("synced")
            && song.getLong("contentVersion") == 0
            && song.getString("remoteId").isEmpty()
            && song.getString("coreVersion").isEmpty()
            && !song.getBoolean("needsFix"),
        "当前生产记录不是本夹具真实保存的合法本地曲目");
  }

  private static void checkEvents(JSONArray actual) throws Exception {
    int[] keys = {0, 1, 2, -1, 3, 4, 5, 7};
    double[] beats = {1, .5, .5, .5, 1, .5, 1, 1};
    require(actual.length() == keys.length, "真实解析没有得到 8 个事件");
    for (int i = 0; i < keys.length; i++) {
      JSONObject event = actual.getJSONObject(i);
      String mode = i == 4 ? "RAISE" : i == 5 ? "LOWER" : "NATURAL";
      require(
          event.getInt("keyIndex") == keys[i]
              && event.getDouble("beats") == beats[i]
              && event.getBoolean("rest") == (i == 3)
              && event.getBoolean("halfTone") == (i == 5)
              && mode.equals(event.getString("mode")),
          "真实事件的音高、拍长或模式错误：" + i);
    }
  }

  private static void validateSavedRecords(Access access) throws Exception {
    JSONArray saved = new JSONArray(access.prefs.getString("songs", "[]"));
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < saved.length(); i++) {
      JSONObject item = saved.getJSONObject(i);
      for (String key : keys(item))
        require(SAVED_FIELDS.contains(key), "旧曲目含生产保存无法保留的字段，拒绝新增：" + key);
      String id = item.getString("id");
      require(!id.isEmpty() && ids.add(id), "旧持久曲库含空 id 或重复 id，拒绝新增");
      require(
          item.optLong("contentVersion", 0) >= 0
              && optionalMetadataPreserved(item.optString("remoteId", ""))
              && optionalMetadataPreserved(item.optString("coreVersion", "")),
          "旧曲目含生产 write 会丢弃的非默认元数据，拒绝新增");
      JSONObject loaded = songRecord(findSong(access, id));
      require(!loaded.getBoolean("builtIn") && !loaded.getBoolean("synced"), "旧本地曲目与生产来源冲突");
      require(
          loaded.getString("title").equals(item.getString("title"))
              && loaded.getString("score").equals(item.getString("score"))
              && loaded.getInt("bpm") == item.optInt("bpm", 120)
              && loaded.getString("source").equals(item.optString("source", "简谱"))
              && loaded.getString("remoteId").equals(item.optString("remoteId", ""))
              && loaded.getLong("contentVersion") == item.optLong("contentVersion", 0)
              && loaded.getString("coreVersion").equals(item.optString("coreVersion", ""))
              && loaded.getBoolean("needsFix") == item.optBoolean("needsFix", false),
          "旧持久记录无法由生产列表完整表示，拒绝新增");
    }
  }

  private static boolean optionalMetadataPreserved(String text) {
    // Kotlin isNotBlank 同时判断 isWhitespace/isSpaceChar；不能仅用 Java String.isBlank。
    return text.isEmpty()
        || !text.chars().allMatch(value -> Character.isWhitespace((char) value) || Character.isSpaceChar((char) value));
  }

  private static JSONObject savedRecord(JSONArray array, String id) throws Exception {
    JSONObject found = null;
    for (int i = 0; i < array.length(); i++) {
      JSONObject item = array.getJSONObject(i);
      if (!id.equals(item.getString("id"))) continue;
      require(found == null, "持久曲库验收 id 重复");
      found = item;
    }
    require(found != null, "实际 songs 持久字段没有验收曲目");
    return found;
  }

  private static boolean containsId(JSONArray array, String id) throws Exception {
    for (int i = 0; i < array.length(); i++)
      if (id.equals(array.getJSONObject(i).getString("id"))) return true;
    return false;
  }

  private static Object getter(Object target, String method) throws Exception {
    return target.getClass().getMethod(method).invoke(target);
  }

  private static JSONObject storage(Access access, String songsJson) throws Exception {
    Class<?> kv = access.prefs.getClass();
    require("io.fastkv.FastKV".equals(kv.getName()), "生产曲库不是真实 FastKV 实例");
    var pathField = kv.getDeclaredField("path");
    var nameField = kv.getDeclaredField("name");
    pathField.setAccessible(true);
    nameField.setAccessible(true);
    Path root = new File((String) pathField.get(access.prefs)).getCanonicalFile().toPath();
    require(
        root.equals(new File(access.target.getFilesDir(), "fastkv").getCanonicalFile().toPath())
            && "song_library".equals(nameField.get(access.prefs)),
        "真实曲库没有使用正常内部 FastKV 路径");
    List<Path> paths = new ArrayList<>();
    for (String suffix : List.of(".kva", ".kvb", ".kvc")) {
      Path file = root.resolve("song_library" + suffix);
      if (Files.exists(file)) paths.add(file);
    }
    Path values = root.resolve("song_library");
    if (Files.exists(values)) {
      require(!Files.isSymbolicLink(values), "曲库大值目录含链接");
      try (var entries = Files.walk(values)) {
        paths.addAll(entries.filter(Files::isRegularFile).sorted().collect(Collectors.toList()));
      }
    }
    paths.sort(Path::compareTo);
    JSONArray files = new JSONArray();
    boolean persisted = false;
    byte[] needle = songsJson.getBytes(StandardCharsets.UTF_8);
    int[] prefix = prefix(needle);
    for (Path path : paths) {
      require(!Files.isSymbolicLink(path) && path.toRealPath().startsWith(root.toRealPath()), "曲库文件越界");
      JSONObject record = fileRecord(root, path, needle, prefix);
      persisted |= record.getBoolean("containsCurrentSongsJson");
      files.put(record);
    }
    require(files.length() > 0 && persisted, "当前 songs JSON 尚未出现在真实 FastKV 持久文件中");
    return new JSONObject().put("path", root.toString()).put("files", files);
  }

  private static JSONObject fileRecord(Path root, Path path, byte[] needle, int[] prefix) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    int matched = 0;
    long bytes = 0;
    boolean found = false;
    try (var input = Files.newInputStream(path)) {
      byte[] chunk = new byte[32768];
      int count;
      while ((count = input.read(chunk)) != -1) {
        digest.update(chunk, 0, count);
        bytes += count;
        if (found) continue;
        for (int i = 0; i < count; i++) {
          while (matched > 0 && chunk[i] != needle[matched]) matched = prefix[matched - 1];
          if (chunk[i] == needle[matched]) matched++;
          if (matched == needle.length) {
            found = true;
            break;
          }
        }
      }
    }
    require(bytes == Files.size(path), "只读期间曲库文件大小改变");
    return new JSONObject()
        .put("path", root.relativize(path).toString().replace(File.separatorChar, '/'))
        .put("size", bytes)
        .put("sha256", hex(digest.digest()))
        .put("containsCurrentSongsJson", found);
  }

  private static int[] prefix(byte[] needle) {
    require(needle.length > 0, "持久 songs JSON 为空");
    int[] result = new int[needle.length];
    for (int i = 1, matched = 0; i < needle.length; i++) {
      while (matched > 0 && needle[i] != needle[matched]) matched = result[matched - 1];
      if (needle[i] == needle[matched]) matched++;
      result[i] = matched;
    }
    return result;
  }

  private static void restoreSelection(SharedPreferences prefs, boolean present, String original) {
    if (prefs.contains("selected") == present && Objects.equals(original, prefs.getString("selected", null))) return;
    SharedPreferences.Editor editor = prefs.edit();
    if (present) editor.putString("selected", original);
    else editor.remove("selected");
    require(editor.commit(), "无法恢复原选曲偏好");
    require(
        prefs.contains("selected") == present && Objects.equals(original, prefs.getString("selected", null)),
        "原选曲键的存在状态或值没有恢复");
  }

  private static String settingsHash(Map<String, ?> values) throws Exception {
    return hash(canonicalPreferences(values, true));
  }

  private static String canonicalPreferences(Map<String, ?> values, boolean skipSongs) throws Exception {
    JSONObject result = new JSONObject();
    for (Map.Entry<String, ?> entry : new TreeMap<>(values).entrySet()) {
      if (skipSongs && entry.getKey().equals("songs")) continue;
      Object value = entry.getValue();
      require(value != null, "曲库偏好包含 null 值");
      Object encoded = value;
      if (value instanceof Set<?>) {
        List<String> strings = new ArrayList<>();
        for (Object item : (Set<?>) value) {
          require(item instanceof String, "曲库偏好集合类型无效");
          strings.add((String) item);
        }
        strings.sort(String::compareTo);
        encoded = new JSONArray(strings);
      }
      result.put(entry.getKey(), new JSONObject().put("type", value.getClass().getName()).put("value", encoded));
    }
    return canonical(result);
  }

  private static String canonical(Object value) throws Exception {
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      List<String> keys = keys(object);
      keys.sort(String::compareTo);
      List<String> items = new ArrayList<>();
      for (String key : keys) items.add(JSONObject.quote(key) + ":" + canonical(object.get(key)));
      return "{" + String.join(",", items) + "}";
    }
    if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      List<String> items = new ArrayList<>();
      for (int i = 0; i < array.length(); i++) items.add(canonical(array.get(i)));
      return "[" + String.join(",", items) + "]";
    }
    if (value == null || value == JSONObject.NULL) return "null";
    if (value instanceof String) return JSONObject.quote((String) value);
    return String.valueOf(value);
  }

  private static List<String> keys(JSONObject object) {
    List<String> result = new ArrayList<>();
    var keys = object.keys();
    while (keys.hasNext()) result.add(keys.next());
    return result;
  }

  private static String hash(String text) throws Exception {
    return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return result.toString();
  }

  private static void writeReport(Context target, Path report, JSONObject result) throws Exception {
    Path cache = target.getCacheDir().getCanonicalFile().toPath();
    Path temporary = Files.createTempFile(cache, "native-user-score-report-", ".tmp");
    try {
      Files.write(temporary, result.toString(2).getBytes(StandardCharsets.UTF_8));
      Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary); // 仅清理本夹具自己创建的 cache 临时文件。
    }
  }

  private static Throwable unwrap(Throwable failure) {
    return failure instanceof InvocationTargetException && failure.getCause() != null ? failure.getCause() : failure;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class Access {
    final Context target;
    final Bootstrap.Source source;
    final Class<?> repositoryType;
    final Class<?> parserType;
    final SharedPreferences prefs;
    final Object repository;

    private Access(
        Context target, Bootstrap.Source source, Class<?> repositoryType, Class<?> parserType,
        SharedPreferences prefs, Object repository) {
      this.target = target;
      this.source = source;
      this.repositoryType = repositoryType;
      this.parserType = parserType;
      this.prefs = prefs;
      this.repository = repository;
    }

    static Access open(Instrumentation runner, Activity home) throws Exception {
      Context target = runner.getTargetContext();
      var startup = Bootstrap.startupState();
      require(
          home != null
              && target.getPackageName().equals("app.luoxianlv.debug")
              && target.getPackageName().equals(home.getPackageName())
              && (target.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
              && startup != null
              && startup.config.environment.equals("test")
              && java.util.Set.of("http://127.0.0.1:18472", "http://127.0.0.1:18476")
                  .contains(startup.config.origin.toString())
              && Looper.myLooper() != Looper.getMainLooper(),
          "真实曲谱夹具只允许 Debug / test 本机测试工作线程");
      Bootstrap.Source source = Bootstrap.source();
      AtomicReference<Context> module = new AtomicReference<>();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      runner.runOnMainSync(
          () -> {
            try {
              require(Bootstrap.source() == source && !home.isDestroyed(), "当前业务或首页已经改变");
              module.set(source.prepared.context(home));
            } catch (Throwable invalid) {
              failure.set(invalid);
            }
          });
      if (failure.get() != null) throw new AssertionError("获取真实业务 Context 失败", failure.get());
      ClassLoader loader = source.prepared.classLoader();
      Class<?> kvType = Class.forName("app.luoxianlv.data.Kv", true, loader);
      Object kv = kvType.getField("INSTANCE").get(null);
      var adaptersField = kvType.getDeclaredField("adapters");
      adaptersField.setAccessible(true);
      SharedPreferences prefs;
      synchronized (kv) {
        Map<?, ?> adapters = (Map<?, ?>) adaptersField.get(null);
        // 首次 adapt 会迁移偏好；只允许首页已打开的曲库，确保 snapshot/verify 不触发写入。
        require(adapters.containsKey("song_library"), "当前业务首页尚未初始化真实曲库");
        prefs =
            (SharedPreferences)
                kvType.getMethod("of", Context.class, String.class).invoke(kv, module.get(), "song_library");
      }
      require(prefs.getBoolean("speed_migrated_v2", false), "生产曲库仍需速度迁移，拒绝以只读名义写入");
      String before = hash(canonicalPreferences(prefs.getAll(), false));
      Class<?> repositoryType = Class.forName("app.luoxianlv.data.SongRepository", true, loader);
      Object repository = repositoryType.getConstructor(Context.class).newInstance(module.get());
      Class<?> parserType = Class.forName("app.luoxianlv.core.score.ScoreParser", true, loader);
      require(Bootstrap.source() == source, "构造生产曲库期间业务来源改变");
      require(before.equals(hash(canonicalPreferences(prefs.getAll(), false))), "构造生产 Repository 改变了偏好");
      return new Access(target, source, repositoryType, parserType, prefs, repository);
    }

    List<?> songs() throws Exception {
      current();
      return (List<?>) repositoryType.getMethod("songs").invoke(repository);
    }

    void current() {
      require(Bootstrap.source() == source, "曲谱检查期间实际业务来源改变");
    }
  }
}
