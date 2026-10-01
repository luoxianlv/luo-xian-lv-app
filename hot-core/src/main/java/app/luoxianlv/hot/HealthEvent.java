package app.luoxianlv.hot;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/** 健康事件只传获准尝试编号和稳定事件码，不传谱子、截图、异常原文或设备标识。 */
public final class HealthEvent {
  public final String attemptId, kind, code;
  public final long sequence;

  public HealthEvent(String attemptId, long sequence, String kind, String code) {
    StrictJson.require(
        UUID.fromString(attemptId).toString().equals(attemptId)
            && sequence > 0
            && sequence <= 1000000
            && Arrays.asList(
                    "prepared",
                    "activated",
                    "healthy",
                    "module_used",
                    "load_failed",
                    "crash",
                    "anr",
                    "recovered",
                    "download_failed")
                .contains(kind)
            && HotManifest.validId(code),
        "更新健康事件格式无效");
    this.attemptId = attemptId;
    this.sequence = sequence;
    this.kind = kind;
    this.code = code;
  }

  Map<String, Object> fields() {
    return JsonWire.fields(
        "attemptId", attemptId, "sequence", sequence, "kind", kind, "code", code);
  }
}
