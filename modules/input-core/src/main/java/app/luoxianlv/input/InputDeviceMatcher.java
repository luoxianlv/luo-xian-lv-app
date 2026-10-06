package app.luoxianlv.input;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 名称快查与系统物理/逻辑映射共用的匹配规则；歧义设备不能拿来注入。 */
final class InputDeviceMatcher {
  record Device(int id, String name, int vendor, int product, String descriptor, boolean mainDisplay) {}
  record Match(int id, String method, String error) {
    boolean found() { return id >= 0; }
  }
  private record Hub(int id, String path, String descriptor, boolean enabled) {}
  private record Reader(int id, String descriptor, Set<Integer> hubs) {}
  private static final Pattern HUB_HEADER = Pattern.compile("^\\s+(-?\\d+): .+$");
  private static final Pattern READER_HEADER = Pattern.compile("^\\s*Device (-?\\d+):.*$");
  private static final Pattern INTEGER = Pattern.compile("-?\\d+");
  private static final Pattern READER_SECTION = Pattern.compile("^Input\\s*Reader State(?: \\([^\\r\\n]{0,100}\\))?:$");

  static Match byIdentity(String name, int vendor, int product, List<Device> devices) {
    List<Device> matching = new ArrayList<>();
    for (Device device : devices)
      if (device.mainDisplay && name.equals(device.name) && vendor == device.vendor && product == device.product)
        matching.add(device);
    return unique(matching, "identity");
  }

  /** 只接受 EventHub 的精确设备路径和系统 descriptor；不以“只有一个触屏”作推断。 */
  static Match bySystemMap(String path, String dump, List<Device> devices) {
    if (path == null || !path.matches("/dev/input/event[0-9]+") || dump == null || dump.length() > 524288)
      return missing("系统触屏映射无效");
    List<Hub> hubs = new ArrayList<>();
    List<Reader> readers = new ArrayList<>();
    int section = 0, id = -1;
    String devicePath = "", descriptor = "";
    boolean enabled = true;
    Set<Integer> members = new HashSet<>();
    for (String line : dump.split("\\n", -1)) {
      String text = line.trim();
      boolean readerSection = READER_SECTION.matcher(text).matches();
      if (text.equals("Event Hub State:") || readerSection
          || (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && text.endsWith("State:"))) {
        if (section == 1 && id >= 0) hubs.add(new Hub(id, devicePath, descriptor, enabled));
        if (section == 2 && id >= 0) readers.add(new Reader(id, descriptor, new HashSet<>(members)));
        section = text.equals("Event Hub State:") ? 1 : readerSection ? 2 : 0;
        id = -1; devicePath = ""; descriptor = ""; enabled = true; members.clear();
        continue;
      }
      Matcher header = (section == 1 ? HUB_HEADER : READER_HEADER).matcher(line);
      if (section != 0 && header.matches()) {
        if (section == 1 && id >= 0) hubs.add(new Hub(id, devicePath, descriptor, enabled));
        if (section == 2 && id >= 0) readers.add(new Reader(id, descriptor, new HashSet<>(members)));
        try { id = Integer.parseInt(header.group(1)); } catch (NumberFormatException invalid) { return missing("系统设备编号无效"); }
        devicePath = ""; descriptor = ""; enabled = true; members.clear();
      } else if (id >= 0) {
        if (text.startsWith("Path:")) devicePath = text.substring(5).trim();
        else if (text.startsWith("Descriptor:")) descriptor = text.substring(11).trim();
        else if (text.startsWith("Enabled:")) {
          String flag = text.substring(8).trim();
          enabled = flag.equals("true") || flag.equals("1");
        } else if (section == 2 && text.startsWith("EventHub Devices:")) {
          Matcher number = INTEGER.matcher(text.substring(17));
          while (number.find()) {
            if (members.size() >= 64) return missing("系统设备关联数量异常");
            try { members.add(Integer.parseInt(number.group())); }
            catch (NumberFormatException invalid) { return missing("系统设备关联编号无效"); }
          }
        }
      }
    }
    if (section == 1 && id >= 0) hubs.add(new Hub(id, devicePath, descriptor, enabled));
    if (section == 2 && id >= 0) readers.add(new Reader(id, descriptor, new HashSet<>(members)));
    List<Hub> physical = new ArrayList<>();
    for (Hub candidate : hubs) if (path.equals(candidate.path)) physical.add(candidate);
    if (physical.size() != 1 || !physical.get(0).enabled)
      return missing("系统未唯一登记所选物理触屏");
    Hub hub = physical.get(0);
    List<Reader> linked = new ArrayList<>();
    for (Reader reader : readers) if (reader.hubs.contains(hub.id)) linked.add(reader);
    List<Device> matching = new ArrayList<>();
    for (Device device : devices) {
      if (!device.mainDisplay || device.descriptor == null || device.descriptor.isEmpty()) continue;
      boolean verified = linked.isEmpty()
          ? !hub.descriptor.isEmpty() && hub.descriptor.equals(device.descriptor)
          : false;
      for (Reader reader : linked) {
        if (reader.id != device.id) continue;
        if (!reader.descriptor.isEmpty()) verified |= reader.descriptor.equals(device.descriptor);
        else for (Hub member : hubs)
          verified |= reader.hubs.contains(member.id) && !member.descriptor.isEmpty()
              && member.descriptor.equals(device.descriptor);
      }
      if (verified) matching.add(device);
    }
    return unique(matching, linked.isEmpty() ? "eventhub-descriptor" : "inputreader-device");
  }

  private static Match unique(List<Device> devices, String method) {
    if (devices.size() == 1) return new Match(devices.get(0).id, method, "");
    return missing(devices.isEmpty() ? "物理触屏与 Android 输入设备无法对应" : "触屏映射不唯一，无法安全确认输入设备");
  }

  private static Match missing(String message) { return new Match(-1, "unmatched", message); }
}
