package app.luoxianlv.input;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class TouchRouteModelTest {
  @Test public void androidEightAllowsOnlyTheMissingEnabledField() throws Exception {
    String legacy = dump().replace("      Enabled: true\n", "");
    assertThrows(IOException.class, () -> TouchRouteModel.parse(legacy));
    assertTrue(TouchRouteModel.parse(legacy, true).get("/dev/input/event23").enabled());
    assertFalse(TouchRouteModel.parse(dump().replace("Enabled: true", "Enabled: false"), true)
        .get("/dev/input/event23").enabled());
    assertThrows(IOException.class, () -> TouchRouteModel.parse(legacy.replace("      Descriptor: " + A + "\n", ""), true));
  }
  private static final String A = "a".repeat(40), B = "b".repeat(40);
  private static final TouchRouteModel.Candidate ONE = new TouchRouteModel.Candidate("/dev/input/event23", "panel", 6, 1, 2, 3);
  private static final TouchRouteModel.Candidate TWO = new TouchRouteModel.Candidate("/dev/input/event8", "mirror", 6, 4, 5, 3);
  private static TouchRouteModel.Raw raw(TouchRouteModel.Candidate node, String descriptor, boolean enabled) {
    return new TouchRouteModel.Raw(node.path().equals(ONE.path())?9:2, node.path(), node.name(), descriptor, enabled, node.bus(), node.vendor(), node.product(), node.version());
  }
  private static TouchRouteModel.Device device(TouchRouteModel.Candidate node, String descriptor, int id, int display) {
    return new TouchRouteModel.Device(id, 17, node.name(), descriptor, node.vendor(), node.product(), true, true, display);
  }
  private static Map<String, TouchRouteModel.Raw> both() { return Map.of(ONE.path(),raw(ONE,A,true),TWO.path(),raw(TWO,B,true)); }
  private static TouchRouteModel.Decision choose(List<TouchRouteModel.Candidate> nodes, List<TouchRouteModel.Device> devices, TouchRouteModel.Hint hint) {
    return TouchRouteModel.choose(nodes,both(),devices,hint,0);
  }
  @Test public void realHostTouchChoosesCurrentSourceRegardlessOfNodeOrder() {
    var devices=List.of(device(ONE,A,41,-1),device(TWO,B,66,-1));
    var hint=new TouchRouteModel.Hint(41,A,0,17);
    assertEquals(ONE.path(),choose(List.of(ONE,TWO),devices,hint).path());
    assertEquals(ONE.path(),choose(List.of(TWO,ONE),devices,hint).path());
    assertEquals("android-host-touch",choose(List.of(ONE,TWO),devices,hint).method());
  }
  @Test public void displayAssociationExcludesOtherScreen() {
    assertEquals(ONE.path(),choose(List.of(ONE,TWO),List.of(device(ONE,A,41,0),device(TWO,B,66,7)),null).path());
  }
  @Test public void unknownScreenIsNotGuessedAsMainOrIgnored() {
    assertEquals("",choose(List.of(ONE,TWO),List.of(device(ONE,A,41,0),device(TWO,B,66,-1)),null).path());
  }
  @Test public void twoActiveScreensWithoutTouchProofStayAmbiguous() {
    assertEquals("ambiguous",choose(List.of(ONE,TWO),List.of(device(ONE,A,41,0),device(TWO,B,66,0)),null).method());
  }
  @Test public void disabledRawNodeCannotBeUsedEvenWhenLogicalDeviceEnabled() {
    var result=TouchRouteModel.choose(List.of(ONE),Map.of(ONE.path(),raw(ONE,A,false)),List.of(device(ONE,A,41,0)),null,0);
    assertEquals("none",result.method());
  }
  @Test public void staleHintAndExternalDisplayCannotSelectNode() {
    var devices=List.of(device(ONE,A,41,9),device(TWO,B,66,0));
    assertEquals(TWO.path(),choose(List.of(ONE,TWO),devices,new TouchRouteModel.Hint(41,A,0,17)).path());
    assertEquals("",choose(List.of(ONE,TWO),List.of(device(ONE,A,41,-1),device(TWO,B,66,-1)),new TouchRouteModel.Hint(41,B,0,17)).path());
  }
  @Test public void eventSuffixIsNotAndroidIdAndIdentityMismatchDoesNotExcludeUnknown() {
    assertEquals("",choose(List.of(ONE,TWO),List.of(device(ONE,A,23,-1),device(TWO,B,8,-1)),new TouchRouteModel.Hint(41,A,0,17)).path());
  }
  @Test public void duplicateLogicalDescriptorsCannotProveUniqueMapping() {
    assertEquals("",choose(List.of(ONE,TWO),List.of(device(ONE,A,41,-1),device(ONE,A,42,-1),device(TWO,B,66,-1)),new TouchRouteModel.Hint(41,A,0,17)).path());
  }
  @Test public void incompleteCapabilitiesAreNeverSelected() {
    var unreadable=new TouchRouteModel.Candidate(ONE.path(),"",-1,-1,-1,-1,false);
    assertEquals("",TouchRouteModel.choose(List.of(unreadable),Map.of(),List.of(),null,0).path());
    assertEquals("",TouchRouteModel.choose(List.of(ONE,new TouchRouteModel.Candidate(TWO.path(),"",-1,-1,-1,-1,false)),Map.of(),List.of(),null,0).path());
  }
  @Test public void readerDisabledMapperIsExcludedOnlyWhenCurrentGenerationMatches() {
    var route=new TouchRouteModel.Reader(66,17,TWO.name(),2,true,-1);
    var result=TouchRouteModel.choose(List.of(ONE,TWO),both(),Map.of(2,route),List.of(device(ONE,A,41,0),device(TWO,B,66,-1)),null,0);
    assertEquals(ONE.path(),result.path());
    var stale=new TouchRouteModel.Reader(66,16,TWO.name(),2,true,-1);
    assertEquals("",TouchRouteModel.choose(List.of(ONE,TWO),both(),Map.of(2,stale),List.of(device(ONE,A,41,0),device(TWO,B,66,-1)),null,0).path());
  }
  @Test public void compositeReaderCannotPromoteRepresentativeDescriptorAsActualNode() throws Exception {
    String text=dump().replace("[ 9 ]", "[ 9, 2 ]");
    var routes=TouchRouteModel.readers(text);
    assertTrue(routes.get(9).composite()); assertTrue(routes.get(2).composite());
    var result=TouchRouteModel.choose(List.of(ONE,TWO),both(),routes,List.of(device(ONE,A,41,-1)),new TouchRouteModel.Hint(41,A,0,17),0);
    assertEquals("",result.path());
    assertEquals("",TouchRouteModel.choose(List.of(ONE),both(),routes,List.of(device(ONE,A,41,0)),new TouchRouteModel.Hint(41,A,0,17),0).path());
  }
  @Test public void unknownNodeCannotBeIgnoredEvenWithKnownTouchHint() {
    var unknown=new TouchRouteModel.Candidate(TWO.path(),"",-1,-1,-1,-1,false);
    assertEquals("",TouchRouteModel.choose(List.of(ONE,unknown),both(),List.of(device(ONE,A,41,0)),new TouchRouteModel.Hint(41,A,0,17),0).path());
  }
  @Test public void changedHintGenerationCannotConfirmCurrentTouchOrigin() {
    assertEquals("",choose(List.of(ONE,TWO),List.of(device(ONE,A,41,-1),device(TWO,B,66,-1)),new TouchRouteModel.Hint(41,A,0,16)).path());
  }
  @Test public void nonDirectNodeRequiresVerifiedAndroidScreen() {
    var node=new TouchRouteModel.Candidate(ONE.path(),ONE.name(),6,1,2,3,true,false);
    assertEquals("",TouchRouteModel.choose(List.of(node),Map.of(),List.of(),null,0).path());
    assertEquals(ONE.path(),TouchRouteModel.choose(List.of(node),both(),List.of(device(ONE,A,41,0)),null,0).path());
    assertEquals(ONE.path(),TouchRouteModel.choose(List.of(node),both(),List.of(device(ONE,A,41,-1)),new TouchRouteModel.Hint(41,A,0,17),0).path());
  }
  @Test public void legacyReaderCanConfirmLogicalDisplayWithoutGuessingEventSuffix() throws Exception {
    var routes=TouchRouteModel.readers(dump().replace("    EventHub Devices: [ 9 ] \n",""),true);
    assertEquals(0,routes.get(-41).display());
    var modern=TouchRouteModel.readers(dump().replace("    EventHub Devices: [ 9 ] \n",""),false);
    assertTrue(modern.get(-41).composite());
  }
  @Test public void legacyDisabledMapperSupportsAospModeCasing() throws Exception {
    for (String disabled : List.of("disabled", "DISABLED")) {
      String text = "Input Reader State:\n  Device 41: panel\n    Generation: 17\n"
          + "    Touch Input Mapper (mode - direct):\n      Viewport INTERNAL: displayId=0, uniqueId=screen\n"
          + "  Device 66: mirror\n    Generation: 17\n    Touch Input Mapper (mode - " + disabled + "):\n"
          + "      Viewport INTERNAL: displayId=-1, uniqueId=\nInput Dispatcher State:\n";
      var routes = TouchRouteModel.readers(text, true);
      assertTrue(routes.get(-66).disabled());
      var selected = TouchRouteModel.choose(List.of(ONE, TWO), both(), routes,
          List.of(device(ONE, A, 41, -1), device(TWO, B, 66, -1)), null, 0);
      assertEquals(ONE.path(), selected.path());
    }
  }
  @Test public void freshDisabledReaderCannotBeOverriddenByStaleEnabledJavaOrHint() {
    var reader=new TouchRouteModel.Reader(41,18,ONE.name(),9,true,-1);
    var result=TouchRouteModel.choose(List.of(ONE),both(),Map.of(9,reader),List.of(device(ONE,A,41,0)),new TouchRouteModel.Hint(41,A,0,17),0);
    assertEquals("",result.path()); assertEquals("stale-route",result.method());
  }
  private static String dump() { return "Event Hub State:\n  Devices:\n    9: panel\n      Path: /dev/input/event23\n      Enabled: true\n      Descriptor: " + A + "\n      Identifier: bus=0x0006, vendor=0x0001, product=0x0002, version=0x0003, bluetoothAddress=<not set>\n  Unattached video devices:\n\nInput Reader State:\n  Device 41: panel\n    EventHub Devices: [ 9 ] \n    Generation: 17\n    Touch Input Mapper (mode - DIRECT):\n      Viewport INTERNAL: displayId=0, uniqueId=secret\nInput Dispatcher State:\n"; }
  @Test public void parsesOnlyBoundedCanonicalEventHubFieldsAndReaderLink() throws Exception {
    var nodes=TouchRouteModel.parse(dump());
    assertEquals(1,nodes.size()); assertEquals(9,nodes.get(ONE.path()).hubId()); assertEquals(A,nodes.get(ONE.path()).descriptor());
    var route=TouchRouteModel.readers(dump()).get(9);
    assertEquals(41,route.deviceId()); assertEquals(0,route.display()); assertFalse(route.disabled());
  }
  @Test public void rejectsTruncatedMissingDuplicateAndOversizedInput() {
    assertThrows(IOException.class,()->TouchRouteModel.parse(dump().substring(0,dump().indexOf("  Unattached"))));
    assertThrows(IOException.class,()->TouchRouteModel.parse(dump().replace("      Enabled: true\n","")));
    assertThrows(IOException.class,()->TouchRouteModel.parse(dump().replace("      Enabled: true\n","      Enabled: true\n      Enabled: true\n")));
    assertThrows(IOException.class,()->TouchRouteModel.parse("x".repeat(2*1024*1024+1)));
  }
  @Test public void realSystemSnapshotUsesSameStrictParser() throws Exception {
    String path=System.getenv("LX_INPUT_DUMP_SAMPLE"); org.junit.Assume.assumeTrue(path!=null);
    String text=java.nio.file.Files.readString(java.nio.file.Path.of(path));
    assertFalse(TouchRouteModel.parse(text).isEmpty());
    assertFalse(TouchRouteModel.readers(text).isEmpty());
  }
}
