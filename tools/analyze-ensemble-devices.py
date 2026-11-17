"""验证每台模拟器的逐音提交，并统计共同音符的时差。"""
import argparse
from collections import Counter
import json
import math
from pathlib import Path


def percentile(values, ratio):
    return sorted(values)[max(0, math.ceil(len(values) * ratio) - 1)]


def analyze_round(traces, start, minutes, delayed):
    run = start["states"][0]["room"]["plan"]["run"]
    notes = []
    duplicates = []
    for trace in traces:
        selected = [note for note in trace["notes"] if note["run"] == run]
        counts = Counter(note["note"] for note in selected)
        duplicates.append(sorted(index for index, count in counts.items() if count > 1))
        notes.append({note["note"]: note for note in selected})

    # 每半秒一个音。逐台查整段前缀及该机最后一次提交前的内部缺口，
    # 即使三台恰好漏了同一个音，也不能被交集或退房后清零的计数掩盖。
    required = max(1, minutes * 120)
    missing = [
        sorted(set(range(max(required, max(device, default=-1) + 1))) - device.keys())
        for device in notes
    ]
    common = sorted(set.intersection(*(set(device) for device in notes)))
    spans = {
        index: max(device[index]["serverUs"] for device in notes)
        - min(device[index]["serverUs"] for device in notes) for index in common
    }
    last = common[-1] if common else -1
    tail = [span for index, span in spans.items() if index > last - 120]
    injected = [
        sorted(index for index, note in device.items() if note.get("callbackDelayMs", 0) > 0)
        for device in notes
    ]
    expected_delays = [[73], [21]] + [[] for _ in notes[2:]] if delayed else [[] for _ in notes]
    first = spans.get(0)
    return {
        "run": run, "requiredNotesPerDevice": required,
        "submissionsPerDevice": [len(device) for device in notes],
        "missingPerDevice": missing, "duplicatesPerDevice": duplicates,
        "injectedAfterNotes": injected, "injectionVerified": injected == expected_delays,
        "commonNotes": len(common), "lastCommonNote": last, "firstNoteSpanUs": first,
        "fullSpanP95Us": percentile(list(spans.values()), .95) if spans else None,
        "fullSpanMaxUs": max(spans.values(), default=None), "tailNotes": len(tail),
        "tailSpanP95Us": percentile(tail, .95) if tail else None, "tailSpanMaxUs": max(tail, default=None),
        "noteCoveragePassed": not any(missing) and not any(duplicates),
        "timingTargetsPassed": first is not None and first <= 60_000 and
            (minutes < 10 or percentile(tail, .95) <= 80_000),
    }


parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
args = parser.parse_args()
directory = args.directory.resolve()
result = json.loads((directory / "result.json").read_text(encoding="utf-8"))
assert result["passed"], "流程尚未通过"
traces = [json.loads((directory / f"trace-{i}.json").read_text(encoding="utf-8")) for i in range(len(result["devices"]))]
assert all(t["actualRuntimeAndPlayback"] and t["systemTouchSimulated"] for t in traces)
starts = [s for s in result["stages"] if s["stage"] in ("十分钟共同起点", "本轮共同起点")]
assert len(starts) == result.get("rounds", 1), "共同起点数量不符"
rounds = [analyze_round(traces, start, result["minutes"], result.get("exerciseCallbackDelay") and i % 2 == 1)
          for i, start in enumerate(starts)]
assert len({r["run"] for r in rounds}) == len(rounds), "演奏轮次意外重复"
report = {
    **(rounds[0] if len(rounds) == 1 else {}),
    "participants": len(traces), "minutes": result["minutes"], "api": [t["api"] for t in traces],
    "scope": "实际业务、WS、校时及播放器；末端输入绑定记录。",
    "controlledClock": result["controlledClock"], "apkSha256": result["apkSha256"], "rounds": rounds,
    "skipped": [t["skipped"] for t in traces],  # 结束快照；逐音验收使用上面的各轮轨迹。
    "maxClockUncertaintyUs": max((n["uncertaintyUs"] for t in traces for n in t["notes"]), default=None),
    "noteCoveragePassed": all(r["noteCoveragePassed"] and r["injectionVerified"] for r in rounds),
    "timingTargetsPassed": all(r["timingTargetsPassed"] for r in rounds),
}
encoded = json.dumps(report, ensure_ascii=False, indent=2)
(directory / "timing.json").write_text(encoded, encoding="utf-8")
print(encoded)
assert report["noteCoveragePassed"], "逐音核对失败：缺号、重复或未执行预期延迟"
assert report["timingTargetsPassed"], "输入提交时序未达标"
