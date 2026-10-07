package app.luoxianlv.input;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 只使用完整的系统关联证据；event 后缀不是 Android deviceId，未知信息不补成主屏。 */
final class TouchRouteModel {
  record Candidate(String path, String name, int bus, int vendor, int product, int version, boolean capable, boolean direct) {
    Candidate(String path, String name, int bus, int vendor, int product, int version) {
      this(path, name, bus, vendor, product, version, true, true);
    }
    Candidate(String path, String name, int bus, int vendor, int product, int version, boolean capable) {
      this(path,name,bus,vendor,product,version,capable,true);
    }
  }
  record Raw(int hubId, String path, String name, String descriptor, boolean enabled, int bus, int vendor, int product, int version) { }
  record Device(int id, int generation, String name, String descriptor, int vendor, int product,
                boolean enabled, boolean touchscreen, int display) { }
  record Hint(int id, String descriptor, int display, int generation) {
    Hint(int id, String descriptor, int display) { this(id,descriptor,display,-1); }
  }
  record Reader(int deviceId, int generation, String name, int hubId, boolean disabled, int display, boolean composite) {
    Reader(int deviceId, int generation, String name, int hubId, boolean disabled, int display) {
      this(deviceId,generation,name,hubId,disabled,display,false);
    }
  }
  record Decision(String path, String method, String reason, Map<String, String> evidence) { }

  private static final Pattern HEADER = Pattern.compile("    (-?[0-9]+): (.+)");
  private static final Pattern IDENTITY = Pattern.compile(
      "      Identifier: bus=0x([0-9a-fA-F]{4}), vendor=0x([0-9a-fA-F]{4}), product=0x([0-9a-fA-F]{4}), version=0x([0-9a-fA-F]{4})(?:, bluetoothAddress=.*)?");

  /** 只读取 EventHub 的固定字段，其他段落和个人设备地址均不保留。 */
  static Map<String, Raw> parse(String text) throws IOException {
    return parse(text, false);
  }

  /** Android 8.0 的 EventHub 没有 Enabled 字段；后续仍校验 Reader 的实际路由。 */
  static Map<String, Raw> parse(String text, boolean legacyEnabled) throws IOException {
    if (text == null || text.length() > 2 * 1024 * 1024) throw new IOException("系统输入摘要超过上限");
    Map<String, Raw> result = new HashMap<>();
    boolean hub = false, devices = false, completed = false;
    Entry entry = null;
    try (var lines = new BufferedReader(new StringReader(text))) {
      for (String line; (line = lines.readLine()) != null;) {
        if (!hub) { if (line.equals("Event Hub State:")) hub = true; continue; }
        if (!devices) { if (line.equals("  Devices:")) devices = true; continue; }
        if (!line.isEmpty() && !line.startsWith("    ")) {
          put(result, entry, legacyEnabled); completed = true; break;
        }
        var header = HEADER.matcher(line);
        if (header.matches()) {
          put(result, entry, legacyEnabled);
          if (result.size() >= 128) throw new IOException("系统输入节点超过上限");
          entry = new Entry(Integer.parseInt(header.group(1)), header.group(2));
        } else if (entry != null) entry.read(line);
      }
    }
    // 文件末尾落在设备字段中可能是截断输出，不能拿它消除其他候选。
    if (!hub || !devices || !completed) throw new IOException("系统输入摘要格式不完整");
    return result;
  }

  private static void put(Map<String, Raw> result, Entry entry, boolean legacyEnabled) throws IOException {
    if (entry == null || entry.path == null || entry.path.equals("<virtual>")) return;
    if (!entry.path.matches("/dev/input/event[0-9]{1,8}") || entry.descriptor == null
        || !entry.descriptor.matches("[0-9a-f]{40}") || (entry.enabled == null && !legacyEnabled) || entry.identity == null)
      throw new IOException("系统输入节点缺少完整身份");
    int[] id = entry.identity;
    Raw raw = new Raw(entry.id, entry.path, entry.name, entry.descriptor, entry.enabled == null || entry.enabled, id[0], id[1], id[2], id[3]);
    for (Raw old : result.values()) if (old.hubId == raw.hubId) throw new IOException("系统输入摘要重复声明原始设备身份");
    if (result.put(raw.path, raw) != null) throw new IOException("系统输入摘要重复声明设备路径");
  }

  private static final class Entry {
    final String name;
    final int id;
    String path, descriptor;
    Boolean enabled;
    int[] identity;
    Entry(int id, String name) { this.id = id; this.name = name; }
    void read(String line) throws IOException {
      if (line.startsWith("      Path: ")) {
        if (path != null) throw new IOException("系统输入节点重复路径字段");
        path = line.substring(12);
      } else if (line.startsWith("      Descriptor: ")) {
        if (descriptor != null) throw new IOException("系统输入节点重复身份字段");
        descriptor = line.substring(18);
      } else if (line.startsWith("      Enabled: ")) {
        if (enabled != null) throw new IOException("系统输入节点重复启用字段");
        String value = line.substring(15);
        if (!value.equals("true") && !value.equals("false")) throw new IOException("系统输入节点启用状态无效");
        enabled = value.equals("true");
      } else if (line.startsWith("      Identifier: ")) {
        if (identity != null) throw new IOException("系统输入节点重复能力身份");
        var match = IDENTITY.matcher(line);
        if (!match.matches()) throw new IOException("系统输入节点能力身份无效");
        identity = new int[4];
        for (int i = 0; i < 4; i++) identity[i] = Integer.parseInt(match.group(i + 1), 16);
      }
    }
  }

  /** Android 14 起的显式 EventHub 联结；只使用单节点、单触屏映射的完整条目。 */
  static Map<Integer, Reader> readers(String text) throws IOException {
    return readers(text, true);
  }

  static Map<Integer, Reader> readers(String text, boolean allowLegacyLogicalLink) throws IOException {
    Map<Integer, Reader> result = new HashMap<>();
    var header = Pattern.compile("  Device (-?[0-9]+): (.+)");
    var members = Pattern.compile("    EventHub Devices: \\[\\s*(-?[0-9]+(?:\\s*,\\s*-?[0-9]+)*)\\s*\\]\\s*");
    var viewport = Pattern.compile("      Viewport [A-Z_]+: displayId=(-?[0-9]+),.*");
    boolean inReader = false;
    int id = -1, generation = -1, hubId = -1, modes = 0, display = -1;
    boolean disabled = false;
    ArrayList<Integer> group = new ArrayList<>();
    String name = "";
    try (var lines = new BufferedReader(new StringReader(text))) {
      for (String line; (line = lines.readLine()) != null;) {
        if (!inReader) { if (line.startsWith("Input Reader State")) inReader = true; continue; }
        var match = header.matcher(line);
        boolean sectionEnd = !line.isEmpty() && !line.startsWith("  ");
        if (match.matches() || sectionEnd) {
          if (id > 0 && (group.size() > 1 || (generation >= 0 && modes >= 1))) {
            boolean composite = group.size() > 1 || modes > 1 || (!allowLegacyLogicalLink && group.isEmpty());
            Reader logical = new Reader(id,generation,name,-1,disabled,display,composite);
            if (result.put(-id,logical) != null) throw new IOException("系统输入路由重复逻辑设备");
            for (int member : group) if (member > 0)
              if (result.put(member, new Reader(id, generation, name, member, disabled, display, composite)) != null)
                throw new IOException("系统输入路由重复声明节点");
          }
          if (sectionEnd) break;
          id = Integer.parseInt(match.group(1)); name = match.group(2);
          generation = hubId = display = -1; modes = 0; disabled = false;
          group.clear();
        } else if (line.startsWith("    Generation: ")) {
          try { generation = Integer.parseInt(line.substring(16)); }
          catch (NumberFormatException invalid) { throw new IOException("系统输入路由代际无效"); }
        } else if (line.startsWith("    EventHub Devices: ")) {
          var node = members.matcher(line);
          if (node.matches()) {
            if (!group.isEmpty()) throw new IOException("系统输入路由重复成员字段");
            for (String value : node.group(1).split(",")) group.add(Integer.parseInt(value.trim()));
            if (group.size() > 128 || group.stream().distinct().count() != group.size()) throw new IOException("系统输入路由成员无效");
            if (group.size() == 1) hubId = group.get(0);
          }
        } else if (line.startsWith("    Touch Input Mapper (mode - ")) {
          modes++; disabled = line.equalsIgnoreCase("    Touch Input Mapper (mode - DISABLED):");
        } else {
          var value = viewport.matcher(line);
          if (value.matches() && modes == 1) display = Integer.parseInt(value.group(1));
        }
      }
    }
    return result;
  }

  static Decision choose(List<Candidate> candidates, Map<String, Raw> raw, List<Device> devices,
                         Hint hint, int targetDisplay) {
    return choose(candidates, raw, Map.of(), devices, hint, targetDisplay);
  }

  static Decision choose(List<Candidate> candidates, Map<String, Raw> raw, Map<Integer, Reader> readers,
                         List<Device> devices, Hint hint, int targetDisplay) {
    Map<String, String> evidence = new HashMap<>();
    ArrayList<Candidate> possible = new ArrayList<>(), routed = new ArrayList<>(), touched = new ArrayList<>();
    java.util.HashSet<String> blockedFallback = new java.util.HashSet<>();
    boolean unknownCapabilities = false;
    boolean staleRoute = false;
    for (Candidate candidate : candidates) {
      if (!candidate.capable) {
        possible.add(candidate); unknownCapabilities = true;
        evidence.put(candidate.path, "节点能力或身份读取失败，仍可能属于主屏"); continue;
      }
      Raw node = raw.get(candidate.path);
      if (node == null || (candidate.capable && !same(candidate, node))) {
        possible.add(candidate); evidence.put(candidate.path, "缺少一致的系统节点映射"); continue;
      }
      if (!node.enabled) { evidence.put(candidate.path, "系统原始节点已停用"); continue; }
      Reader rawReader = readers.get(node.hubId);
      if (rawReader != null && rawReader.composite) {
        possible.add(candidate); blockedFallback.add(candidate.path);
        evidence.put(candidate.path, "逻辑设备含多个原始节点，代表身份不能证明实际触摸来源"); continue;
      }
      Device device = null; int matches = 0;
      for (Device value : devices) if (value.descriptor.equals(node.descriptor)
          && value.name.equals(node.name) && value.vendor == node.vendor && value.product == node.product) {
        matches++; device = value;
      }
      if (matches != 1) {
        possible.add(candidate); evidence.put(candidate.path, "系统逻辑设备映射不唯一"); continue;
      }
      if (!device.enabled || !device.touchscreen) { evidence.put(candidate.path, "系统未启用此触屏来源"); continue; }
      int display = device.display;
      Reader reader = readers.get(node.hubId);
      if (reader == null) reader = readers.get(-device.id);
      if (reader != null && reader.composite) {
        possible.add(candidate); blockedFallback.add(candidate.path);
        evidence.put(candidate.path, "系统未提供唯一原始节点联结，不能推断实际触摸来源"); continue;
      }
      if (reader != null && (reader.deviceId != device.id || reader.generation != device.generation || !reader.name.equals(device.name))) {
        possible.add(candidate); staleRoute = true;
        evidence.put(candidate.path, "系统路由与逻辑设备身份或配置代际不同，等待状态稳定"); continue;
      }
      if (reader != null) {
        if (reader.disabled) { evidence.put(candidate.path, "系统触屏映射已停用"); continue; }
        if (display < 0) display = reader.display;
      }
      if (display >= 0 && display != targetDisplay) { evidence.put(candidate.path, "属于其他显示器"); continue; }
      possible.add(candidate);
      if (hint != null && hint.id == device.id && hint.descriptor.equals(device.descriptor)
          && hint.display == targetDisplay && hint.generation >= 0 && hint.generation == device.generation) {
        touched.add(candidate); evidence.put(candidate.path, "宿主当前显示收到的真实手指来源");
      } else if (display == targetDisplay) {
        routed.add(candidate); evidence.put(candidate.path, "系统确认属于当前显示器");
      } else evidence.put(candidate.path, "已确认触屏来源，显示关联未知");
    }
    if (staleRoute) return new Decision("", "stale-route", "系统输入配置正在更新，请重新连接", evidence);
    if (unknownCapabilities) return new Decision("", "unknown-capabilities", "部分输入节点无法读取身份或能力，未接管触屏", evidence);
    if (touched.size() == 1) return new Decision(touched.get(0).path, "android-host-touch", "已按当前显示的真实触摸来源确认触屏", evidence);
    if (touched.size() > 1) return new Decision("", "ambiguous", "同一触摸来源对应多个触屏节点，不能安全接管", evidence);
    if (possible.size() == 1 && possible.get(0).capable && !blockedFallback.contains(possible.get(0).path)
        && (possible.get(0).direct || routed.size() == 1)) return new Decision(possible.get(0).path,
        routed.size() == 1 ? "android-display-route" : "unique-capabilities", "已确认唯一可用触屏", evidence);
    if (possible.size() == 1) return new Decision("", "unconfirmed-source", "未确认此输入节点属于当前主屏，请在设置页点重新连接", evidence);
    // 未知候选仍可能属于主屏，不能因为另一个候选证据较多就随意丢弃它。
    return new Decision("", possible.isEmpty() ? "none" : "ambiguous",
        possible.isEmpty() ? "系统没有启用可用触屏" : "仍有多个当前触屏候选，请在设置页点重新连接以确认触摸来源", evidence);
  }

  private static boolean same(Candidate candidate, Raw node) {
    return candidate.name.equals(node.name) && candidate.bus == node.bus && candidate.vendor == node.vendor
        && candidate.product == node.product && candidate.version == node.version;
  }
  private TouchRouteModel() { }
}
