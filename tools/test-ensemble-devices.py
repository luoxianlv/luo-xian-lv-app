"""六个临时只读模拟器的真实合奏联调；保留原始日志，末端触摸由仪器记录。"""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import subprocess
import time
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--adb", required=True)
parser.add_argument("--fixture", required=True)
parser.add_argument("--apk", required=True)
parser.add_argument("--test-apk", required=True)
parser.add_argument("--output", required=True)
parser.add_argument("--minutes", type=int, default=10)
parser.add_argument("--participants", type=int, choices=[3, 6], default=6)
args = parser.parse_args()
fixture = pathlib.Path(args.fixture).resolve()
assert ".local" in fixture.parts and 0 <= args.minutes <= 20
output = pathlib.Path(args.output).resolve()
output.mkdir(parents=True, exist_ok=False)
size = args.participants
member = size - 1
# 主控断网使用有 Wi-Fi 服务的 API30；API26 仍参加完整的准备、调度和暂停。
ports = [5560, 5556, 5566] if size == 3 else [5560, 5556, 5558, 5562, 5564, 5566]
serials = [f"emulator-{port}" for port in ports]
remote = "files/ensemble-multi"
package = "app.luoxianlv.debug"
pool = concurrent.futures.ThreadPoolExecutor(max_workers=6)
processes = []
logs = []
stages = []
tokens = json.loads((fixture / "tokens.json").read_text(encoding="utf-8"))
fixture_state = json.loads((fixture / "multi-ready.json").read_text(encoding="utf-8"))
controlled = fixture_state["controlledClock"]
used = fixture / "runner.used"
if used.is_file() and used.read_text(encoding="utf-8") == fixture_state["schema"]:
    raise RuntimeError("本机夹具已经运行过；先重新启动服务，隔离上次房间和账号状态")
used.write_text(fixture_state["schema"], encoding="utf-8")
apk_hash = hashlib.sha256(pathlib.Path(args.apk).read_bytes()).hexdigest()
test_apk_hash = hashlib.sha256(pathlib.Path(args.test_apk).read_bytes()).hexdigest()


def adb(index, *command, timeout=90, check=True):
    result = subprocess.run([args.adb, "-s", serials[index], *command], capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"{serials[index]} ADB失败：{(result.stdout+result.stderr).decode('utf-8',errors='replace')[:512]}")
    return result.stdout


def each(function, indexes=range(size)):
    return list(pool.map(function, indexes))


def read(index, filename):
    data = adb(index, "exec-out", "run-as", package, "cat", f"{remote}/{filename}", timeout=10, check=False)
    try:
        return json.loads(data)
    except (ValueError, UnicodeDecodeError):
        return {}


def states():
    return each(lambda index: read(index, "state.json"))


def inputs():
    return each(lambda index: read(index, "input.json"))


def command(index, action, **fields):
    body = {"id": str(uuid.uuid4()), "action": action, **fields}
    path = fixture / f"control-{index}.json"
    path.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
    temporary = f"/data/local/tmp/ensemble-control-{index}.json"
    adb(index, "push", str(path), temporary)
    adb(index, "shell", "chmod", "644", temporary)
    adb(index, "shell", "run-as", package, "cp", temporary, f"{remote}/control.json")
    adb(index, "shell", "rm", temporary)


def mark(name, value):
    stages.append({"stage": name, "elapsedSeconds": round(time.monotonic() - began, 3), **value})
    (output / "progress.json").write_text(json.dumps(stages, ensure_ascii=False, indent=2), encoding="utf-8")
    print(name, flush=True)


def await_state(name, predicate, timeout=60):
    deadline = time.monotonic() + timeout
    latest = []
    while time.monotonic() < deadline:
        latest = states()
        if predicate(latest):
            mark(name, {"states": latest})
            return latest
        for index, process in enumerate(processes):
            if process.poll() is not None:
                raise RuntimeError(f"设备 {index} 仪器已退出，查看 device-{index}.log")
        time.sleep(0.15)
    (output / "failed-state.json").write_text(json.dumps(latest, ensure_ascii=False, indent=2), encoding="utf-8")
    raise RuntimeError(f"等待{name}超时")


def setup(index):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if adb(index, "shell", "getprop", "sys.boot_completed", check=False).strip() == b"1":
            break
        time.sleep(1)
    else:
        raise RuntimeError(f"{serials[index]} 未启动")
    # 这六个固定端口只供本轮以 -read-only 启动的临时实例；原有 5554 不参与。
    assert serials[index] != "emulator-5554"
    adb(index, "install", "-r", args.apk)
    adb(index, "install", "-r", args.test_apk)
    installed = adb(index, "shell", "pm", "path", package).decode().strip().removeprefix("package:")
    assert adb(index, "shell", "sha256sum", installed).decode().split()[0] == apk_hash, "安装包与测试记录不一致"
    adb(index, "shell", "pm", "clear", package)
    adb(index, "reverse", "tcp:18474", "tcp:18474")
    adb(index, "shell", "settings", "put", "global", "http_proxy", "10.0.2.2:7890")
    for setting in ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]:
        adb(index, "shell", "settings", "put", "global", setting, "0")
    adb(index, "shell", "run-as", package, "mkdir", "-p", remote)
    config = fixture / f"device-{index}.json"
    config.write_text(json.dumps(tokens[index]), encoding="utf-8")
    temporary = f"/data/local/tmp/ensemble-config-{index}.json"
    adb(index, "push", str(config), temporary)
    adb(index, "shell", "chmod", "644", temporary)
    adb(index, "shell", "run-as", package, "mkdir", "-p", "files")
    adb(index, "shell", "run-as", package, "cp", temporary, "files/ensemble-multi-config.json")
    adb(index, "shell", "rm", temporary)


def network(index, enabled):
    action = "enable" if enabled else "disable"
    adb(index, "shell", "svc", "wifi", action)
    adb(index, "shell", "svc", "data", action)


def all_(values, predicate):
    return bool(values) and all(bool(value) and predicate(value, index) for index, value in enumerate(values))


began = time.monotonic()
success = False
try:
    each(setup)
    for index in range(size):
        stream = (output / f"device-{index}.log").open("wb")
        logs.append(stream)
        processes.append(subprocess.Popen([args.adb, "-s", serials[index], "shell", "am", "instrument", "-w",
            f"{package}.test/app.luoxianlv.host.EnsembleMultiInstrumentation"], stdout=stream, stderr=subprocess.STDOUT))
    await_state("六机登录授权", lambda all: all_(all, lambda state, i: state.get("account") == f"emulator-{i}"), 100)
    command(0, "create")
    result = await_state("主控建房", lambda all: bool(all[0].get("room")) and not all[0].get("working"))
    code = result[0]["room"]["code"]
    each(lambda index: command(index, "join", code=code), range(1, size))
    await_state("同房", lambda all: all_(all, lambda state, i: len((state.get("room") or {}).get("members", [])) == size))
    command(0, "choose")
    await_state("同谱下载描述", lambda all: all_(all, lambda state, i: bool((state.get("room") or {}).get("score")) and not state.get("working")))
    each(lambda index: command(index, "prepare"))
    await_state("六机输入和校时就绪", lambda all: all_(all, lambda state, i: state.get("ready") and state.get("inputReady")))
    assert all(value.get("noteCount", 0) == 0 for value in inputs()), "准备阶段提前演奏"
    # 在真实应用内观察全员确认后立即取消；ADB 的六机回读不能占用两秒倒计时。
    command(0, "startAndCancel")
    await_state("全员确认后取消倒计时", lambda all: bool(all[0].get("cancelledPlan")) and all_(all, lambda state, i: (state.get("room") or {}).get("phase") == "paused" and not state.get("playing")))
    time.sleep(3)
    assert all(value.get("noteCount", 0) == 0 and value.get("starts", 0) == 0 for value in inputs()), "取消后旧计划启动"
    each(lambda index: command(index, "prepare"))
    await_state("取消后重新准备", lambda all: all_(all, lambda state, i: state.get("ready")))
    command(0, "resume")
    await_state("六机同步起奏", lambda all: all_(all, lambda state, i: state.get("playing")), 20)
    time.sleep(2)
    initial = inputs()
    assert all(value.get("noteCount", 0) > 0 for value in initial), "有设备未提交首音"
    mark("首音记录", {"inputs": initial})
    command(member, "stop")
    await_state("成员停止不拖停他人", lambda all: not all[member].get("playing") and all_(all[:member], lambda state, i: state.get("playing")))
    before = inputs()
    time.sleep(2)
    after = inputs()
    assert after[member]["noteCount"] == before[member]["noteCount"]
    assert all(after[i]["noteCount"] > before[i]["noteCount"] for i in range(member))
    command(member, "prepare")
    await_state("成员再次准备", lambda all: all[member].get("ready"))
    command(0, "rejoin", account=f"emulator-{member}")
    await_state("按拍点重入", lambda all: all_(all, lambda state, i: state.get("playing")), 20)
    for index, label in [(member, "成员"), (0, "主控")]:
        previous = states()[index]["epoch"]
        network(index, False)
        await_state(label+"断线其余继续", lambda all: not all[index].get("playing") and all_(all, lambda state, i: i == index or state.get("playing")), 40)
        time.sleep(2)
        network(index, True)
        await_state(label+"恢复并重新校时、不自动点击", lambda all: all[index].get("account") == f"emulator-{index}" and all[index].get("epoch") not in ["", previous] and all[index].get("clockReady") and not all[index].get("ready") and not all[index].get("playing"), 90)
        command(index, "prepare")
        await_state(label+"恢复准备", lambda all: all[index].get("ready"))
        command(0, "rejoin", account=f"emulator-{index}")
        await_state(label+"恢复重入", lambda all: all_(all, lambda state, i: state.get("playing")), 20)
    # 所有人暂停后重来，形成共同起点，测完整十分钟绝对时间线。
    command(0, "pause")
    await_state("全员立即暂停", lambda all: all_(all, lambda state, i: not state.get("playing") and (state.get("room") or {}).get("phase") == "paused"))
    each(lambda index: command(index, "prepare"))
    await_state("重来准备", lambda all: all_(all, lambda state, i: state.get("ready")))
    command(0, "restart")
    await_state("十分钟共同起点", lambda all: all_(all, lambda state, i: state.get("playing")), 20)
    until = time.monotonic() + args.minutes * 60
    while time.monotonic() < until:
        current = states()
        assert all_(current, lambda state, i: state.get("playing")), "十分钟期间出现非预期停止"
        time.sleep(min(10, max(0, until-time.monotonic())))
    command(0, "pause")
    await_state("长时间线暂停", lambda all: all_(all, lambda state, i: not state.get("playing")))
    command(0, "leave")
    await_state("退房清理", lambda all: all_(all, lambda state, i: state.get("room") is None and not state.get("working")))
    each(lambda index: command(index, "finish"))
    for index, process in enumerate(processes):
        process.wait(timeout=30)
        logs[index].flush()
        assert "多模拟器真实业务/播放器验证完成" in (output/f"device-{index}.log").read_text(encoding="utf-8"), "仪器未通过"
        trace = read(index, "trace.json")
        (output/f"trace-{index}.json").write_text(json.dumps(trace, ensure_ascii=False, indent=2), encoding="utf-8")
    success = True
finally:
    if not success:
        for index in range(size):
            try:
                (output/f"state-{index}-failed.json").write_text(json.dumps(read(index,"state.json"), ensure_ascii=False, indent=2), encoding="utf-8")
                command(index,"finish")
            except Exception:
                pass
    for index, process in enumerate(processes):
        try:
            process.wait(timeout=20)
            for name in ["history.json", "trace.json", "input.json"]:
                value = read(index, name)
                (output/f"{name[:-5]}-device-{index}.json").write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
            diagnostic = adb(index, "shell", "logcat", "-d", "-s", "多人合奏:V", check=False)
            (output/f"ensemble-log-{index}.txt").write_bytes(diagnostic)
        except Exception:
            pass
    for stream in logs:
        stream.close()
    (output/"result.json").write_text(json.dumps({"passed": success, "devices": serials, "minutes": args.minutes,
        "apkSha256": apk_hash, "testApkSha256": test_apk_hash,
        "realRuntimeAndPlayback": True,"systemTouchSimulated": True,"controlledClock": controlled,"stages": stages}, ensure_ascii=False, indent=2), encoding="utf-8")
