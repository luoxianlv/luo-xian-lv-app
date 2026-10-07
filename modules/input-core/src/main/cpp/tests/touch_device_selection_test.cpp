#include "../touch_device_selection.h"

#include <cstdlib>
#include <iostream>

using namespace luoxianlv::input;

static void require(bool condition, const char* message) {
    if (!condition) { std::cerr << message << '\n'; std::exit(1); }
}

static TouchDeviceCandidate device(const std::string& path, uint64_t number,
                                   const std::string& identity = "same-capabilities") {
    return {path, number, true, true, false, identity, true};
}

static std::string selectedPath(const std::vector<TouchDeviceCandidate>& candidates,
                                 const TouchDeviceSelection& selection) {
    return selection.selected == kNoDeviceIndex ? "" : candidates[selection.selected].path;
}

int main() {
    require(exactEventPath("/dev/input/event0") && exactEventPath("/dev/input/event123"), "拒绝合法 evdev 路径");
    for (const char* path : {"/dev/input/event", "/dev/input/event1/other", "/dev/input/event../0",
                             "/dev/input/event-1", "touchscreen", "/tmp/event0"})
        require(!exactEventPath(path), "接受了非精确输入设备路径");
    {
        std::vector<TouchDeviceCandidate> candidates;
        require(selectTouchDevice(candidates).decision == DeviceDecision::None, "空设备列表产生选择");
        candidates.push_back(device("/dev/input/event7", 71));
        auto selection = selectTouchDevice(candidates);
        require(selection.decision == DeviceDecision::Unique && selectedPath(candidates, selection) == candidates[0].path,
                "唯一兼容设备没有选中");
        candidates[0].compatible = false;
        require(selectTouchDevice(candidates).decision == DeviceDecision::None, "选择了不兼容设备");
    }
    {
        std::vector<TouchDeviceCandidate> candidates = {device("/dev/input/event6", 66)};
        candidates[0].direct = false;
        auto selection = selectTouchDevice(candidates);
        require(selection.decision == DeviceDecision::NeedsRoute && selection.selected == kNoDeviceIndex,
                "缺少 DIRECT 的唯一节点被无路由猜选");
        auto routed = selectTouchDevice(candidates, "/dev/input/event6");
        require(routed.decision == DeviceDecision::Preferred && selectedPath(candidates, routed) == candidates[0].path,
                "Android 已验证的非 DIRECT 触屏不能选中");
        candidates.push_back(device("/dev/input/event2", 22));
        require(selectTouchDevice(candidates).decision == DeviceDecision::Ambiguous,
                "多个候选中随意偏好 DIRECT 节点");
    }
    {
        // 同名/同型号、物理/软件以及 A/B 差异都不是选择证据。
        std::vector<TouchDeviceCandidate> candidates = {
            device("/dev/input/event9", 90, "type-a-physical"),
            device("/dev/input/event2", 20, "type-b-software")};
        for (int permutation = 0; permutation < 2; ++permutation) {
            auto ambiguous = selectTouchDevice(candidates);
            require(ambiguous.decision == DeviceDecision::Ambiguous && ambiguous.selected == kNoDeviceIndex,
                    "按枚举顺序或协议猜选多个设备");
            auto verified = selectTouchDevice(candidates, "/dev/input/event9");
            require(verified.decision == DeviceDecision::Preferred &&
                    selectedPath(candidates, verified) == "/dev/input/event9", "精确路由不能选择 Type A 设备");
            std::reverse(candidates.begin(), candidates.end());
        }
        require(selectTouchDevice(candidates, "type-b-software").decision == DeviceDecision::InvalidPath,
                "用名称代替了路由证据");
        require(selectTouchDevice(candidates, "/dev/input/event404").selected == kNoDeviceIndex,
                "精确路由不匹配时回退到其他设备");
    }
    {
        std::vector<TouchDeviceCandidate> aliases = {device("/dev/input/event2", 77), device("/dev/input/event1", 77)};
        for (int permutation = 0; permutation < 2; ++permutation) {
            auto selection = selectTouchDevice(aliases);
            require(selection.unique.size() == 1 && selectedPath(aliases, selection) == "/dev/input/event1",
                    "同一 rdev 的别名未去重或结果受顺序影响");
            require(selectedPath(aliases, selectTouchDevice(aliases, "/dev/input/event2")) == "/dev/input/event2",
                    "已经核对的精确别名不能打开");
            std::reverse(aliases.begin(), aliases.end());
        }
        aliases[0].capabilityIdentity = "node-recreated";
        require(selectTouchDevice(aliases, aliases[1].path).decision == DeviceDecision::Changed,
                "同一 rdev 扫描期间身份变化仍被选中");
    }
    {
        std::vector<TouchDeviceCandidate> candidates = {device("/dev/input/event1", 77)};
        candidates.push_back({"/dev/input/event2", 78, true, false, true, ""});
        require(selectTouchDevice(candidates).decision == DeviceDecision::Incomplete,
                "读取失败被当成其他设备不存在");
        require(selectTouchDevice(candidates, "/dev/input/event1").decision == DeviceDecision::Preferred,
                "明确的系统路由无法选择可读设备");
        require(selectTouchDevice(candidates, "/dev/input/event1", true).decision == DeviceDecision::Incomplete,
                "列表截断仍接受精确选择");
    }
    {
        std::vector<TouchDeviceCandidate> candidates;
        for (size_t i = 0; i <= kMaxTouchDeviceNodes; ++i)
            candidates.push_back(device("/dev/input/event" + std::to_string(i), i + 1));
        require(selectTouchDevice(candidates, "/dev/input/event0").decision == DeviceDecision::Incomplete,
                "接受了超过安全上限的设备列表");
    }
    require(sameTouchDeviceIdentity("stable", "stable") && !sameTouchDeviceIdentity("", "") &&
            !sameTouchDeviceIdentity("stable", "recreated"), "准备阶段允许身份或能力变化");
    std::cout << "设备选择验证通过：顺序无关、rdev 别名、真实歧义、精确路径、截断和身份变化\n";
}
