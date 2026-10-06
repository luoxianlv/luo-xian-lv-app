package app.luoxianlv.input;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class InputDeviceMatcherTest {
  private static final String PATH = "/dev/input/event3";
  private static final String DESC = "00112233445566778899aabbccddeeff00112233";
  private static InputDeviceMatcher.Device device(int id, String name, int vendor, int product, String descriptor, boolean main) {
    return new InputDeviceMatcher.Device(id, name, vendor, product, descriptor, main);
  }
  private static String hub(String path, String descriptor) {
    return "Event Hub State:\n  Devices:\n    3: kernel_touch\n      Path: " + path
        + "\n      Enabled: true\n      Descriptor: " + descriptor + "\n";
  }
  private static String reader(int id, String descriptor, String members) {
    return "Input Reader State:\n  Device " + id + ": logical_touch\n    Descriptor: " + descriptor
        + "\n    EventHub Devices: [ " + members + " ]\n";
  }

  @Test public void exactIdentityUsesFastPath() {
    var match = InputDeviceMatcher.byIdentity("kernel_touch", 1, 2,
        List.of(device(7, "kernel_touch", 1, 2, DESC, true)));
    assertEquals(7, match.id()); assertEquals("identity", match.method());
  }
  @Test public void renamedLogicalDeviceUsesExactSystemPathAndDescriptor() {
    var match = InputDeviceMatcher.bySystemMap(PATH, hub(PATH, DESC),
        List.of(device(8, "renamed_touch", 0, 0, DESC, true)));
    assertEquals(8, match.id()); assertEquals("eventhub-descriptor", match.method());
  }
  @Test public void mergedLogicalDeviceUsesReaderAssociation() {
    String logical = "ffeeddccbbaa99887766554433221100ffeeddcc";
    var match = InputDeviceMatcher.bySystemMap(PATH, hub(PATH, DESC) + reader(42, logical, "2, 3"),
        List.of(device(42, "logical", 0, 0, logical, true)));
    assertEquals(42, match.id()); assertEquals("inputreader-device", match.method());
  }
  @Test public void readerSectionSupportsAndroid16DeviceCountHeading() {
    String logical = "ffeeddccbbaa99887766554433221100ffeeddcc";
    String dump = hub(PATH, DESC) + reader(42, logical, "3")
        .replace("Input Reader State:", "Input Reader State (Nums of device: 14):");
    assertEquals(42, InputDeviceMatcher.bySystemMap(PATH, dump,
        List.of(device(42, "touch", 0, 0, logical, true))).id());
    assertEquals(42, InputDeviceMatcher.bySystemMap(PATH,
        dump.replace("Input Reader State", "InputReader State"),
        List.of(device(42, "touch", 0, 0, logical, true))).id());
  }
  @Test public void readerWithoutDescriptorRequiresMemberIdentity() {
    String dump = hub(PATH, DESC) + "Input Reader State (Nums of device: 1):\n  Device 42: logical\n    EventHub Devices: [ 3 ]\n";
    assertEquals(42, InputDeviceMatcher.bySystemMap(PATH, dump,
        List.of(device(42, "touch", 0, 0, DESC, true))).id());
    assertFalse(InputDeviceMatcher.bySystemMap(PATH, dump,
        List.of(device(42, "touch", 0, 0, "stale", true))).found());
  }
  @Test public void mergedReaderDescriptorCanComeFromAnotherMember() {
    String logical = "ffeeddccbbaa99887766554433221100ffeeddcc";
    String dump = hub(PATH, DESC) + "    4: keyboard_member\n      Path: /dev/input/event4\n      Descriptor: "
        + logical + "\nInput Reader State:\n  Device 42: logical\n    EventHub Devices: [ 3, 4 ]\n";
    assertEquals(42, InputDeviceMatcher.bySystemMap(PATH, dump,
        List.of(device(42, "touch", 0, 0, logical, true))).id());
  }
  @Test public void soleScreenWithoutSystemProofIsRejected() {
    assertFalse(InputDeviceMatcher.bySystemMap(PATH, hub("/dev/input/event4", DESC),
        List.of(device(8, "touch", 0, 0, DESC, true))).found());
  }
  @Test public void duplicatePathIsRejected() {
    assertFalse(InputDeviceMatcher.bySystemMap(PATH, hub(PATH, DESC)
        + "    4: other\n      Path: " + PATH + "\n      Descriptor: " + DESC + "\n",
        List.of(device(8, "touch", 0, 0, DESC, true))).found());
  }
  @Test public void disabledDeviceIsRejected() {
    assertFalse(InputDeviceMatcher.bySystemMap(PATH, hub(PATH, DESC).replace("Enabled: true", "Enabled: false"),
        List.of(device(8, "touch", 0, 0, DESC, true))).found());
  }
  @Test public void readerIdAloneCannotAuthorizeStaleDescriptor() {
    assertFalse(InputDeviceMatcher.bySystemMap(PATH, hub(PATH, DESC) + reader(8, "old", "3"),
        List.of(device(8, "touch", 0, 0, DESC, true))).found());
  }
  @Test public void duplicatedPublicDescriptorIsRejected() {
    assertFalse(InputDeviceMatcher.bySystemMap(PATH, hub(PATH, DESC), List.of(
        device(8, "a", 0, 0, DESC, true), device(9, "b", 0, 0, DESC, true))).found());
  }
  @Test public void secondaryScreenIsRejectedInBothPaths() {
    var external = List.of(device(8, "kernel_touch", 1, 2, DESC, false));
    assertFalse(InputDeviceMatcher.byIdentity("kernel_touch", 1, 2, external).found());
    assertFalse(InputDeviceMatcher.bySystemMap(PATH, hub(PATH, DESC), external).found());
  }
  @Test public void unrelatedDumpSectionCannotInventPhysicalPath() {
    assertFalse(InputDeviceMatcher.bySystemMap(PATH, "Input Dispatcher State:\n    3: fake\n      Path: "
        + PATH + "\n      Descriptor: " + DESC + "\n", List.of(device(8, "touch", 0, 0, DESC, true))).found());
  }
  @Test public void nestedMapperStateDoesNotEndReaderDeviceSection() {
    String dump = hub(PATH, DESC) + reader(5, "other", "9") + "    Gesture State:\n      mode: idle\n"
        + "  Device 8: logical\n    Descriptor: " + DESC + "\n    EventHub Devices: [ 3 ]\n";
    assertEquals(8, InputDeviceMatcher.bySystemMap(PATH, dump, List.of(device(8, "touch", 0, 0, DESC, true))).id());
  }
  @Test public void descriptorAndInputBoundsAreMandatory() {
    assertFalse(InputDeviceMatcher.bySystemMap(PATH, hub(PATH, ""), List.of(device(8, "touch", 0, 0, DESC, true))).found());
    assertFalse(InputDeviceMatcher.bySystemMap("/dev/input/../event3", hub(PATH, DESC), List.of()).found());
    assertFalse(InputDeviceMatcher.bySystemMap(PATH, " ".repeat(524289), List.of()).found());
  }
}
