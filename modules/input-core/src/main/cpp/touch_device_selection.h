#pragma once

#include <algorithm>
#include <cstdint>
#include <limits>
#include <map>
#include <string>
#include <vector>

namespace luoxianlv::input {

constexpr size_t kMaxTouchDeviceNodes = 128;
constexpr size_t kNoDeviceIndex = std::numeric_limits<size_t>::max();

// 路径只标识本次扫描中的 evdev 节点，不能由 Android deviceId 推导。
inline bool exactEventPath(const std::string& path) {
    constexpr const char* prefix = "/dev/input/event";
    constexpr size_t prefixSize = 16;
    if (path.compare(0, prefixSize, prefix) != 0 || path.size() <= prefixSize ||
        path.size() > prefixSize + 16) return false;
    return std::all_of(path.begin() + prefixSize, path.end(),
                       [](char value) { return value >= '0' && value <= '9'; });
}

struct TouchDeviceCandidate {
    std::string path;
    uint64_t deviceNumber = 0;
    bool hasDeviceNumber = false;
    bool compatible = false;
    bool uncertain = false;
    // 同一 rdev 的别名可有不同 inode，但必须具有同一内核设备和静态能力。
    std::string capabilityIdentity;
    bool direct = false;
};

enum class DeviceDecision { None, Unique, Preferred, Ambiguous, Incomplete, NeedsRoute, Changed, InvalidPath };

struct TouchDeviceSelection {
    DeviceDecision decision = DeviceDecision::None;
    size_t selected = kNoDeviceIndex;
    std::vector<size_t> unique;
    std::vector<size_t> duplicateOf;
};

// 本规则不读取名称、bus、虚拟标记或协议，也不为多个真实设备打分。
// exactPreferred 只能由已核对的 Android 路由证据给出。
inline TouchDeviceSelection selectTouchDevice(const std::vector<TouchDeviceCandidate>& candidates,
                                               const std::string& exactPreferred = {},
                                               bool truncated = false) {
    TouchDeviceSelection result;
    result.duplicateOf.assign(candidates.size(), kNoDeviceIndex);
    std::map<uint64_t, size_t> canonical;
    bool uncertain = false, changed = false;
    for (size_t i = 0; i < candidates.size(); ++i) {
        const auto& candidate = candidates[i];
        uncertain |= candidate.uncertain;
        if (!candidate.compatible || !candidate.hasDeviceNumber || !exactEventPath(candidate.path)) continue;
        auto [entry, inserted] = canonical.emplace(candidate.deviceNumber, i);
        if (inserted) continue;
        size_t first = entry->second;
        if (candidate.capabilityIdentity.empty() ||
            candidate.capabilityIdentity != candidates[first].capabilityIdentity) changed = true;
        if (candidate.path < candidates[first].path) entry->second = i;
    }
    for (size_t i = 0; i < candidates.size(); ++i) {
        const auto& candidate = candidates[i];
        if (!candidate.compatible || !candidate.hasDeviceNumber || !exactEventPath(candidate.path)) continue;
        size_t first = canonical.at(candidate.deviceNumber);
        if (first == i) result.unique.push_back(i);
        else result.duplicateOf[i] = first;
    }
    std::sort(result.unique.begin(), result.unique.end(), [&](size_t left, size_t right) {
        return candidates[left].path < candidates[right].path;
    });
    if (truncated || candidates.size() > kMaxTouchDeviceNodes) {
        result.decision = DeviceDecision::Incomplete;
        return result;
    }
    if (changed) { result.decision = DeviceDecision::Changed; return result; }
    if (!exactPreferred.empty()) {
        if (!exactEventPath(exactPreferred)) { result.decision = DeviceDecision::InvalidPath; return result; }
        for (size_t i = 0; i < candidates.size(); ++i) {
            if (candidates[i].path == exactPreferred && candidates[i].compatible && candidates[i].hasDeviceNumber) {
                result.selected = i;
                result.decision = DeviceDecision::Preferred;
                return result;
            }
        }
        return result;
    }
    if (result.unique.size() > 1) { result.decision = DeviceDecision::Ambiguous; return result; }
    if (uncertain) { result.decision = DeviceDecision::Incomplete; return result; }
    if (result.unique.size() == 1) {
        // Android 可用 IDC 将没有 DIRECT 的 MT 节点配置为触屏；必须先有明确路由。
        if (!candidates[result.unique.front()].direct) {
            result.decision = DeviceDecision::NeedsRoute;
            return result;
        }
        result.selected = result.unique.front();
        result.decision = DeviceDecision::Unique;
    }
    return result;
}

inline bool sameTouchDeviceIdentity(const std::string& expected, const std::string& current) {
    return !expected.empty() && expected == current;
}

}  // namespace luoxianlv::input
