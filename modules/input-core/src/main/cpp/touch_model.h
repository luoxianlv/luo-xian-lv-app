#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>

namespace luoxianlv::input {

constexpr int kMaxPointers = 32;
constexpr int kMaxAutomaticPointers = 10;
// Android 的指针编号有 32 个，单个 MotionEvent 仍最多容纳 16 个同时触点。
constexpr int kMaxSimultaneousPointers = 16;

// 激活可发生在工作循环中途；每次消费 Begin/物理帧前都必须先消费初始化。
class ActivationGate {
public:
    void request() { pending_.store(true); }
    template <typename Reset> void apply(Reset&& reset) {
        if (pending_.exchange(false)) reset();
    }
private:
    std::atomic<bool> pending_{false};
};

struct Frame {
    int count = 0;
    std::array<int, kMaxPointers> ids{};
    std::array<float, kMaxPointers * 2> xy{};

    void add(int id, float x, float y) {
        ids[count] = id;
        xy[count * 2] = x;
        xy[count * 2 + 1] = y;
        ++count;
    }
};

struct Slot {
    int trackingId = -1;
    int x = 0;
    int y = 0;
};

// 物理槽与自动槽各有固定的指针编号，音符结束不影响真实手指。
class TouchModel {
public:
    TouchModel(int slots, int minX, int maxX, int minY, int maxY,
               int width, int height, int rotation, bool hasTouchButton = false)
        : slots_(slots), minX_(minX), maxX_(maxX), minY_(minY), maxY_(maxY),
          width_(width), height_(height), rotation_(rotation), hasTouchButton_(hasTouchButton) {
        clear();
    }

    void clear() {
        for (auto& slot : physical_) slot = {-1, minX_, minY_};
        automaticCount_ = 0;
        selected_ = 0;
        touching_ = !hasTouchButton_;
    }

    bool select(int slot) {
        if (slot < 0 || slot >= slots_) return false;
        selected_ = slot;
        return true;
    }

    bool tracking(int id) {
        if (id < -1) return false;
        physical_[selected_].trackingId = id;
        return true;
    }

    bool x(int value) {
        if (value < minX_ || value > maxX_) return false;
        physical_[selected_].x = value;
        return true;
    }

    bool y(int value) {
        if (value < minY_ || value > maxY_) return false;
        physical_[selected_].y = value;
        return true;
    }

    bool begin(const float* xy, int count) {
        if (count <= 0 || count > maximumAutomatic() || physicalCount() + count > kMaxSimultaneousPointers)
            return false;
        for (int i = 0; i < count; ++i) {
            const float px = xy[i * 2], py = xy[i * 2 + 1];
            if (!std::isfinite(px) || !std::isfinite(py) || px < 0 || py < 0 ||
                px > width_ - 1 || py > height_ - 1) return false;
        }
        for (int i = 0; i < count * 2; ++i) automatic_[i] = xy[i];
        automaticCount_ = count;
        return true;
    }

    void cancelAutomatic() { automaticCount_ = 0; }

    // 只屏蔽物理接触的输出，保留 raw tracking/坐标，兼容悬停转按下复用同一 ID。
    bool touch(int value) {
        if (value < 0 || value > 1) return false;
        if (hasTouchButton_) touching_ = value != 0;
        return true;
    }

    int physicalCount() const {
        if (!touching_) return 0;
        int count = 0;
        for (int i = 0; i < slots_; ++i) count += physical_[i].trackingId >= 0;
        return count;
    }

    bool touchPressed() const { return touching_; }

    int maximumAutomatic() const { return std::min(kMaxAutomaticPointers, kMaxPointers - slots_); }

    // 同一槽的新 tracking id 先抬起旧触点，再交给下一帧创建，避免编号被错误复用。
    Frame frame(const std::array<int, kMaxPointers>* previous = nullptr,
                bool omitReplaced = false) const {
        Frame result;
        for (int i = 0; i < slots_; ++i) {
            const auto& slot = physical_[i];
            if (!touching_ || slot.trackingId < 0) continue;
            if (omitReplaced && previous && (*previous)[i] >= 0 && (*previous)[i] != slot.trackingId)
                continue;
            const float u = static_cast<float>(slot.x - minX_) / static_cast<float>(maxX_ - minX_);
            const float v = static_cast<float>(slot.y - minY_) / static_cast<float>(maxY_ - minY_);
            float x = u, y = v;
            switch (rotation_) {
                case 1: x = v; y = 1 - u; break;
                case 2: x = 1 - u; y = 1 - v; break;
                case 3: x = 1 - v; y = u; break;
                default: break;
            }
            result.add(i, x * (width_ - 1), y * (height_ - 1));
        }
        for (int i = 0; i < automaticCount_; ++i)
            result.add(slots_ + i, automatic_[i * 2], automatic_[i * 2 + 1]);
        return result;
    }

    bool hasReplacement(const std::array<int, kMaxPointers>& previous) const {
        if (!touching_) return false;
        for (int i = 0; i < slots_; ++i)
            if (previous[i] >= 0 && physical_[i].trackingId >= 0 &&
                previous[i] != physical_[i].trackingId) return true;
        return false;
    }

    void copyTracking(std::array<int, kMaxPointers>& destination) const {
        for (int i = 0; i < slots_; ++i) destination[i] = touching_ ? physical_[i].trackingId : -1;
    }

private:
    int slots_, minX_, maxX_, minY_, maxY_, width_, height_, rotation_;
    int selected_ = 0;
    int automaticCount_ = 0;
    bool hasTouchButton_, touching_;
    std::array<Slot, kMaxPointers> physical_{};
    std::array<float, kMaxAutomaticPointers * 2> automatic_{};
};

}  // namespace luoxianlv::input
