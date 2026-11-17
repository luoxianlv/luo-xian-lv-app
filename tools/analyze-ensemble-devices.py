"""统计模拟器实际输入提交时间；不把记录绑定当作游戏出声或实机触控。"""
import argparse
import json
import math
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
args = parser.parse_args()
directory = args.directory.resolve()
result = json.loads((directory / "result.json").read_text(encoding="utf-8"))
assert result["passed"], "流程尚未通过"
traces = [json.loads((directory / f"trace-{i}.json").read_text(encoding="utf-8")) for i in range(len(result["devices"]))]
assert all(t["actualRuntimeAndPlayback"] and t["systemTouchSimulated"] for t in traces)
start = next(stage for stage in result["stages"] if stage["stage"] == "十分钟共同起点")
run = start["states"][0]["room"]["plan"]["run"]
notes = []
for trace in traces:
    selected = [n for n in trace["notes"] if n["run"] == run]
    assert selected and trace["skipped"] == 0
    indexed = {}
    for note in selected:
        assert note["note"] not in indexed, "同一音符重复提交，不能用选择较好样本掩盖"
        indexed[note["note"]] = note
    notes.append(indexed)
common = sorted(set.intersection(*(set(n) for n in notes)))
assert common and common[0] == 0
if result["minutes"] >= 10:
    assert common[-1] >= 1199, "没有覆盖接近第十分钟的对应音符"
spans = [(index, max(n[index]["serverUs"] for n in notes) - min(n[index]["serverUs"] for n in notes)) for index in common]
tail = [span for index, span in spans if index >= common[-1] - 119]
def percentile(values, ratio):
    return sorted(values)[max(0, math.ceil(len(values) * ratio)-1)]
report = {
    "participants": len(traces), "minutes": result["minutes"], "api": [t["api"] for t in traces],
    "scope": "真实业务、WS、四时间戳及播放器；末端输入绑定记录，首页绘制暂停；非实机或游戏出声",
    "controlledClock": result["controlledClock"], "apkSha256": result["apkSha256"],
    "commonNotes": len(common), "lastCommonNote": common[-1], "firstNoteSpanUs": spans[0][1],
    "fullSpanP95Us": percentile([s for _, s in spans], .95), "fullSpanMaxUs": max(s for _, s in spans),
    "tailNotes": len(tail), "tailSpanP95Us": percentile(tail, .95), "tailSpanMaxUs": max(tail),
    "maxClockUncertaintyUs": max(n["uncertaintyUs"] for trace in traces for n in trace["notes"]),
    "skipped": [t["skipped"] for t in traces],
}
report["timingTargetsPassed"] = report["firstNoteSpanUs"] <= 60_000 and (result["minutes"] < 10 or report["tailSpanP95Us"] <= 80_000)
(directory / "timing.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
assert report["timingTargetsPassed"], "输入提交时序未达标"
