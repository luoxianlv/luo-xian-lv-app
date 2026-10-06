#pragma once

#include "touch_model.h"

#include <limits>

namespace luoxianlv::input {

// Type A 每个同步帧包含全部触点，包的先后顺序不代表手指身份。
class TypeATouchReader {
public:
    TypeATouchReader(int minX, int maxX, int minY, int maxY, bool trackingIds)
        : minX_(minX), maxX_(maxX), minY_(minY), maxY_(maxY), trackingIds_(trackingIds) {}

    void clear() {
        previous_.fill({});
        resetFrame();
    }

    bool x(int value) {
        if (value < minX_ || value > maxX_) return false;
        packet_.x = value; haveX_ = true; return true;
    }
    bool y(int value) {
        if (value < minY_ || value > maxY_) return false;
        packet_.y = value; haveY_ = true; return true;
    }
    bool tracking(int value) {
        if (value < -1) return false;
        packet_.trackingId = value; haveId_ = true; return true;
    }
    bool touch(int value) {
        if (value < 0 || value > 1) return false;
        pendingTouch_ = value;
        return true;
    }

    bool packet() {
        if (!haveX_ && !haveY_ && !haveId_) return true; // 空包表示没有触点。
        if (!haveId_ || packet_.trackingId >= 0) {
            if (!haveX_ || !haveY_ || (trackingIds_ && !haveId_) ||
                    count_ == kMaxSimultaneousPointers) return false;
            if (trackingIds_)
                for (int i = 0; i < count_; ++i)
                    if (points_[i].trackingId == packet_.trackingId) return false;
            points_[count_++] = packet_;
        }
        packet_ = {};
        haveX_ = haveY_ = haveId_ = false;
        return true;
    }

    bool commit(TouchModel& model) {
        if (!packet()) return false; // BTN_TOUCH 设备允许省略末尾 SYN_MT_REPORT。
        const bool touching = pendingTouch_ < 0 ? model.touchPressed() : pendingTouch_ != 0;
        if (count_ == 0 && touching) return false;
        std::array<Slot, kMaxSimultaneousPointers> next{};
        std::array<int, kMaxSimultaneousPointers> slots{};
        slots.fill(-1);
        if (trackingIds_) {
            std::array<bool, kMaxSimultaneousPointers> used{};
            for (int i = 0; i < count_; ++i)
                for (int slot = 0; slot < kMaxSimultaneousPointers; ++slot)
                    if (previous_[slot].trackingId == points_[i].trackingId) {
                        slots[i] = slot; used[slot] = true; break;
                    }
            for (int i = 0; i < count_; ++i) {
                if (slots[i] >= 0) continue;
                for (int slot = 0; slot < kMaxSimultaneousPointers; ++slot)
                    if (!used[slot]) { slots[i] = slot; used[slot] = true; break; }
            }
        } else {
            assignAnonymous(slots);
            for (int i = 0; i < count_; ++i) {
                int id = previous_[slots[i]].trackingId;
                if (id < 0) {
                    if (nextId_ == INT32_MAX) return false;
                    id = nextId_++;
                }
                points_[i].trackingId = id;
            }
        }
        for (int i = 0; i < count_; ++i) next[slots[i]] = points_[i];
        // 一帧可能跨多次 read；在整帧齐全前保持上一帧的接触与坐标。
        if (pendingTouch_ >= 0 && !model.touch(pendingTouch_)) return false;
        for (int slot = 0; slot < kMaxSimultaneousPointers; ++slot) {
            const auto& point = next[slot];
            if (!model.select(slot) || !model.tracking(point.trackingId)) return false;
            if (point.trackingId >= 0 && (!model.x(point.x) || !model.y(point.y))) return false;
        }
        previous_ = next;
        resetFrame();
        return true;
    }

private:
    int minX_, maxX_, minY_, maxY_;
    bool trackingIds_;
    int count_ = 0, nextId_ = 0, pendingTouch_ = -1;
    Slot packet_;
    bool haveX_ = false, haveY_ = false, haveId_ = false;
    std::array<Slot, kMaxSimultaneousPointers> points_{}, previous_{};

    void resetFrame() {
        count_ = 0; packet_ = {};
        pendingTouch_ = -1;
        haveX_ = haveY_ = haveId_ = false;
    }

    double cost(int point, int slot) const {
        if (previous_[slot].trackingId < 0) return 3.0;
        const double x = static_cast<double>(points_[point].x - previous_[slot].x) / (maxX_ - minX_);
        const double y = static_cast<double>(points_[point].y - previous_[slot].y) / (maxY_ - minY_);
        return x * x + y * y;
    }

    // 无硬件 ID 时做矩形最小代价匹配；最多 16 点，固定缓冲，不受包顺序影响。
    void assignAnonymous(std::array<int, kMaxSimultaneousPointers>& slots) const {
        constexpr int n = kMaxSimultaneousPointers;
        std::array<double, n + 1> rows{}, columns{};
        std::array<int, n + 1> matched{}, from{};
        for (int row = 1; row <= count_; ++row) {
            matched[0] = row;
            int column = 0;
            std::array<double, n + 1> distance{};
            distance.fill(std::numeric_limits<double>::infinity());
            std::array<bool, n + 1> used{};
            do {
                used[column] = true;
                const int current = matched[column];
                double step = std::numeric_limits<double>::infinity();
                int next = 0;
                for (int j = 1; j <= n; ++j) if (!used[j]) {
                    const double candidate = cost(current - 1, j - 1) - rows[current] - columns[j];
                    if (candidate < distance[j]) { distance[j] = candidate; from[j] = column; }
                    if (distance[j] < step) { step = distance[j]; next = j; }
                }
                for (int j = 0; j <= n; ++j) {
                    if (used[j]) { rows[matched[j]] += step; columns[j] -= step; }
                    else distance[j] -= step;
                }
                column = next;
            } while (matched[column] != 0);
            do {
                const int previous = from[column];
                matched[column] = matched[previous]; column = previous;
            } while (column != 0);
        }
        for (int j = 1; j <= n; ++j)
            if (matched[j] != 0) slots[matched[j] - 1] = j - 1;
    }
};

}  // namespace luoxianlv::input
