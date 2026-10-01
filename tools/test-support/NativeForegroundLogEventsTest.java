package app.luoxianlv.host;

import static org.junit.Assert.*;
import org.junit.Test;

public final class NativeForegroundLogEventsTest {
  private static final int PID = 20726;
  private static String row(String stamp, int pid, String priority, String message) {
    return stamp + "  " + pid + "  " + pid + " " + priority + " 播放服务: " + message + "\n";
  }
  private static String enter(String stamp, int id, boolean stop) { return row(stamp, PID, "I", "已进入前台：启动序号="+id+"，停止请求="+stop); }
  private static String stop(String stamp, int id) { return row(stamp, PID, "I", "已停止：启动序号="+id); }
  private static NativeForegroundLogEvents.Cursor cursor(String old) {
    var cursor = new NativeForegroundLogEvents.Cursor(PID, old); cursor.startedAt(1000100); return cursor;
  }
  @Test public void actualFixedEpochPairProvesShortIdleStopWithoutLiveInstance() {
    var result = cursor("").observe(enter("1000.101",1,false)+stop("1000.127",1));
    assertTrue(result.normalIdleStopped); assertFalse(result.businessUnavailable);
    assertEquals(1,result.startId); assertEquals(26,result.stopAt-result.enterAt);
  }
  @Test public void oldCursorOldWindowAndSameStartMillisecondCannotPass() {
    String old = enter("1000.201",1,false)+stop("1000.227",1);
    assertFalse(cursor(old).observe(old).normalIdleStopped);
    assertFalse(cursor("").observe(enter("1000.099",1,false)+stop("1000.100",1)).normalIdleStopped);
    assertFalse(cursor("").observe(enter("1000.100",1,false)+stop("1000.127",1)).normalIdleStopped);
  }
  @Test public void anotherPidTagOrPriorityCannotClaimThisStart() {
    String pair = enter("1000.101",1,false)+stop("1000.127",1);
    assertFalse(cursor("").observe(pair.replace("20726","20727")).normalIdleStopped);
    assertFalse(cursor("").observe(pair.replace("播放服务","其他服务")).normalIdleStopped);
    assertFalse(cursor("").observe(pair.replace(" I "," D ")).normalIdleStopped);
  }
  @Test public void mismatchedOrSupersededStartIdsCannotPair() {
    assertFalse(cursor("").observe(enter("1000.101",1,false)+stop("1000.127",2)).normalIdleStopped);
    assertFalse(cursor("").observe(enter("1000.101",1,false)+enter("1000.102",2,false)+stop("1000.127",1)).normalIdleStopped);
  }
  @Test public void reverseOrderOrMissingStopIsNotNormalCompletion() {
    assertFalse(cursor("").observe(stop("1000.127",1)+enter("1000.101",1,false)).normalIdleStopped);
    assertFalse(cursor("").observe(enter("1000.101",1,false)).normalIdleStopped);
    assertFalse(cursor("").observe(stop("1000.127",1)).normalIdleStopped);
  }
  @Test public void explicitStopRequestedNeverBecomesNormalIdleStop() {
    assertFalse(cursor("").observe(enter("1000.101",1,true)+stop("1000.127",1)).normalIdleStopped);
    assertFalse(cursor("").observe(enter("1000.101",1,false)+stop("1000.127",1)+enter("1000.130",2,true)).normalIdleStopped);
  }
  @Test public void anyBusinessUnavailableInNewWindowRejectsBeforeOrAfterPair() {
    String error = row("1000.115",PID,"E","播放业务不可用，停止本次前台服务");
    var result = cursor("").observe(enter("1000.101",1,false)+error+stop("1000.127",1));
    assertTrue(result.businessUnavailable); assertFalse(result.normalIdleStopped);
    var cursor = cursor("");
    assertTrue(cursor.observe(enter("1000.101",1,false)+stop("1000.127",1)).normalIdleStopped);
    assertFalse(cursor.observe(row("1000.140",PID,"E","播放业务不可用，停止本次前台服务")).normalIdleStopped);
    assertTrue(cursor.observe("").businessUnavailable);
  }
  @Test public void partialReadsRetainParsedEventsWithoutKeepingRawMessages() {
    var cursor = cursor("");
    assertFalse(cursor.observe(enter("1000.101",1,false)).normalIdleStopped);
    assertTrue(cursor.observe(stop("1000.127",1)).normalIdleStopped);
    assertTrue(cursor.observe("").normalIdleStopped);
    assertTrue(cursor.observe(enter("1000.101",1,false)+stop("1000.127",1)).normalIdleStopped);
  }
  @Test public void malformedIdsAndOversizedInputFailClosed() {
    assertFalse(cursor("").observe(enter("1000.101",0,false)+stop("1000.127",0)).normalIdleStopped);
    assertFalse(cursor("").observe(enter("1000.101",1,false)+row("1000.127",PID,"I","已停止：启动序号=9999999999")).normalIdleStopped);
    assertThrows(AssertionError.class,()->cursor("").observe("x".repeat(NativeForegroundLogEvents.MAX_BYTES+1)));
    assertThrows(AssertionError.class,()->new NativeForegroundLogEvents.Cursor(PID,"").observe(""));
  }
}
