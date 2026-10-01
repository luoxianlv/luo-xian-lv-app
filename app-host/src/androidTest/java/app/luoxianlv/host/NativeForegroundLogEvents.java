package app.luoxianlv.host;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** 只识别本PID固定tag的固定事件；不保留原日志或异常正文。 */
final class NativeForegroundLogEvents {
  static final int MAX_BYTES = 262144;
  private static final Pattern ROW = Pattern.compile("^\\s*([0-9]{1,12})\\.([0-9]{3,9})\\s+([0-9]{1,10})\\s+[0-9]{1,10}\\s+([VDIWEF])\\s+播放服务\\s*:\\s*(.*)$");
  private static final Pattern ENTER = Pattern.compile("已进入前台：启动序号=([0-9]{1,10})，停止请求=(true|false)");
  private static final Pattern STOP = Pattern.compile("已停止：启动序号=([0-9]{1,10})");
  private static final String UNAVAILABLE = "播放业务不可用，停止本次前台服务";

  static final class Cursor {
    private final int pid;
    private final Set<String> baseline = new HashSet<>();
    private final Map<String, Event> current = new LinkedHashMap<>();
    private long notBefore = Long.MAX_VALUE;
    Cursor(int pid, String before) {
      require(pid > 0, "无效日志PID");
      this.pid = pid;
      for (Event event : parse(before, pid)) baseline.add(event.key());
    }
    void startedAt(long epochMillis) {
      require(epochMillis >= 0 && notBefore == Long.MAX_VALUE, "日志启动游标重复或无效");
      notBefore = epochMillis;
    }
    Result observe(String text) {
      require(notBefore != Long.MAX_VALUE, "尚未记录实际启动时点");
      for (Event event : parse(text, pid)) {
        // 严格晚于启动毫秒；同毫秒/旧cursor记录不能冒充本次瞬态。
        if (event.at <= notBefore || baseline.contains(event.key())) continue;
        current.putIfAbsent(event.key(), event);
        require(current.size() <= 64, "本窗口固定前台事件数量超限");
      }
      Result result = new Result();
      Event lastEnter = null;
      for (Event event : current.values()) {
        if (event.kind.equals("error")) result.businessUnavailable = true;
        else if (event.kind.equals("enter")) {
          lastEnter = event;
          result.enterObserved = true;
          result.stopRequestedObserved |= event.stopRequested;
        } else if (lastEnter != null && event.id == lastEnter.id && !lastEnter.stopRequested
            && event.at >= lastEnter.at) {
          result.startId = event.id;
          result.enterAt = lastEnter.at;
          result.stopAt = event.at;
          result.normalIdleStopped = true;
        }
      }
      if (result.businessUnavailable || result.stopRequestedObserved) result.normalIdleStopped = false;
      return result;
    }
  }

  static final class Result {
    boolean enterObserved, normalIdleStopped, businessUnavailable, stopRequestedObserved;
    int startId;
    long enterAt, stopAt;
  }
  private record Event(long at, int id, String kind, boolean stopRequested, String timestamp) {
    String key() { return timestamp + ":" + id + ":" + kind + ":" + stopRequested; }
  }
  private static List<Event> parse(String text, int expectedPid) {
    require(text.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, "固定tag日志输入超限");
    var result = new ArrayList<Event>();
    for (String line : text.split("\\r?\\n")) {
      var row = ROW.matcher(line);
      if (!row.matches()) continue;
      long pid = Long.parseLong(row.group(3));
      if (pid != expectedPid) continue;
      String timestamp = row.group(1) + "." + row.group(2);
      long at = Long.parseLong(row.group(1)) * 1000 + Long.parseLong(row.group(2).substring(0, 3));
      String message = row.group(5);
      if (message.startsWith(UNAVAILABLE)) result.add(new Event(at, 0, "error", false, timestamp));
      else if (row.group(4).equals("I")) {
        var enter = ENTER.matcher(message); var stop = STOP.matcher(message);
        if (enter.matches()) {
          long id = Long.parseLong(enter.group(1));
          if (id > 0 && id <= Integer.MAX_VALUE) result.add(new Event(at, (int) id, "enter", Boolean.parseBoolean(enter.group(2)), timestamp));
        } else if (stop.matches()) {
          long id = Long.parseLong(stop.group(1));
          if (id > 0 && id <= Integer.MAX_VALUE) result.add(new Event(at, (int) id, "stop", false, timestamp));
        }
      }
    }
    return result;
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
