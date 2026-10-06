#include "../touch_model.h"
#include "../touch_contact.h"

#include <cstdlib>
#include <iostream>
#include <limits>

using namespace luoxianlv::input;

static void require(bool condition, const char* message) {
    if (!condition) { std::cerr << message << '\n'; std::exit(1); }
}

int main() {
    {
        require(evaluateContact(0, false, -1, -1).released(), "无接触键的空槽被错误拒绝");
        require(!evaluateContact(1, false, -1, -1).released(), "无接触键时忽略了真实tracking");
        require(evaluateContact(0, true, 0, 0).released(), "完整抬手状态被错误拒绝");
        const auto inactive = evaluateContact(1, true, 0, 0);
        require(inactive.released() && inactive.inactiveTrackedAllowed &&
                inactive.decision == ContactDecision::InactiveTracking, "非接触tracking被误判为手指按下");
        require(!evaluateContact(1, true, 1, 1).released(), "真实手指按下被放行");
        require(!evaluateContact(0, true, 1, 1).released(), "按下帧的空槽被错误放行");
        require(!evaluateContact(1, true, 0, 1).released() &&
                !evaluateContact(1, true, 1, 0).released(), "前后变化的接触状态被错误放行");
        require(!evaluateContact(1, true, -1, 0, 13).released(), "读取接触状态失败后仍放行");
        require(!evaluateContact(-1, true, 0, 0, 5).released(), "读取槽位失败后仍放行");
        require(!evaluateContact(1, true, -1, -1).released(), "未知接触键被当成已抬手");

        TouchModel gated(10, 0, 1000, 0, 2000, 501, 1001, 0, true);
        require(gated.select(0) && gated.tracking(42) && gated.x(200) && gated.y(600), "非接触槽建立失败");
        require(gated.physicalCount() == 0 && gated.frame().count == 0, "非接触tracking被注入为真实手指");
        const float note[] = {400, 900};
        require(gated.begin(note, 1) && gated.frame().count == 1, "非接触槽阻止了自动演奏");
        require(gated.x(250) && gated.frame().count == 1, "悬停更新时残留槽变成真实手指");
        require(gated.touch(1) && gated.physicalCount() == 1 && gated.frame().count == 2,
                "同一tracking ID转为实际按下时没有恢复合流");
        gated.cancelAutomatic();
        require(gated.frame().count == 1, "自动音符结束误抬起实际按下手指");
        require(gated.touch(0) && gated.frame().count == 0, "BTN_TOUCH抬起后残留tracking仍被注入");
        std::array<int, kMaxPointers> tracked;
        tracked.fill(-1);
        gated.copyTracking(tracked);
        require(tracked[0] == -1 && !gated.hasReplacement(tracked), "非接触槽污染了指针连续性");
        require(gated.touch(1) && gated.frame().count == 1, "抬起后复用同一tracking ID无法再次按下");
        require(gated.tracking(-1) && gated.frame().count == 0, "接触键仍按下时已释放的槽未抬起");
        require(!gated.touch(-1) && !gated.touch(2), "接受非法接触键值");
        gated.clear();
        require(gated.tracking(43) && gated.frame().count == 0, "重新激活没有清理接触键状态");
    }
    {
        TouchModel first(10, 0, 1000, 0, 2000, 501, 1001, 0);
        ActivationGate gate;
        int resets = 0;
        auto reset = [&] { first.clear(); ++resets; };
        gate.apply(reset); // 工作循环已检查，但激活尚未发生。
        gate.request();   // 激活在同一轮 Begin 前才到达。
        gate.apply(reset); // Begin 边界先初始化，不能等下一轮才清空。
        const float note[] = {400, 900};
        require(first.begin(note, 1), "首音建立失败");
        require(first.select(0) && first.tracking(1) && first.x(200) && first.y(600), "首帧物理触点失败");
        gate.apply(reset); // 下一循环不能再抹掉已经开始的首音和真实手指。
        require(resets == 1 && first.frame().count == 2, "激活竞态抹掉已开始的触点");
        first.clear();
        gate.request();
        gate.apply(reset); // 无自动音符时，物理读取边界也先初始化。
        require(first.select(0) && first.tracking(2), "重新激活的物理触点失败");
        gate.apply(reset);
        require(resets == 2 && first.physicalCount() == 1, "激活竞态抹掉首次物理帧");
    }
    TouchModel model(10, 0, 1000, 0, 2000, 501, 1001, 0);
    require(model.select(0) && model.tracking(42) && model.x(200) && model.y(600), "物理触点建立失败");
    auto held = model.frame();
    require(held.count == 1 && held.ids[0] == 0 && held.xy[0] == 100 && held.xy[1] == 300, "物理映射失败");
    const float chord[] = {400, 900, 450, 800};
    require(model.begin(chord, 2), "自动和弦建立失败");
    auto combined = model.frame();
    require(combined.count == 3 && combined.ids[1] == 10 && combined.ids[2] == 11, "触点编号冲突");
    require(model.x(300), "真实移动失败");
    model.cancelAutomatic();
    auto remaining = model.frame();
    require(remaining.count == 1 && remaining.ids[0] == 0 && remaining.xy[0] == 150, "音符结束误抬起真实手指");
    std::array<int, kMaxPointers> previous;
    previous.fill(-1);
    model.copyTracking(previous);
    require(model.tracking(43) && model.hasReplacement(previous), "槽位复用未检测");
    require(model.frame(&previous, true).count == 0 && model.frame().count == 1, "槽位复用缺少抬起帧");
    require(model.tracking(-1) && model.frame().count == 0, "物理抬起失败");
    require(!model.select(10) && !model.select(-1), "接受越界槽位");
    require(!model.x(1001) && !model.y(-1) && !model.tracking(-2), "接受异常内核数据");
    const float invalid[] = {std::numeric_limits<float>::quiet_NaN(), 0};
    require(!model.begin(invalid, 1) && !model.begin(chord, 11), "接受异常自动触点");
    for (int rotation = 0; rotation < 4; ++rotation) {
        TouchModel rotated(1, 0, 100, 0, 100, 201, 101, rotation);
        require(rotated.tracking(1) && rotated.x(25) && rotated.y(75), "旋转输入失败");
        const auto frame = rotated.frame();
        const float expected[][2] = {{50, 75}, {150, 75}, {150, 25}, {50, 25}};
        require(frame.xy[0] == expected[rotation][0] && frame.xy[1] == expected[rotation][1], "旋转映射错误");
    }
    TouchModel crowded(31, 0, 100, 0, 100, 100, 100, 0);
    require(crowded.maximumAutomatic() == 1 && !crowded.begin(chord, 2), "总触点超过 32");
    TouchModel simultaneous(10, 0, 100, 0, 100, 100, 100, 0);
    for (int i = 0; i < 10; ++i) require(simultaneous.select(i) && simultaneous.tracking(i), "多触点输入失败");
    const float many[20] = {};
    require(!simultaneous.begin(many, 7) && simultaneous.begin(many, 6), "单个系统事件超过 16 触点");
    TouchModel wideIds(10, 0, 100, 0, 100, 100, 100, 0);
    for (int i = 0; i < 6; ++i) require(wideIds.select(i) && wideIds.tracking(i + 100), "真实触点建立失败");
    require(wideIds.begin(many, 10), "符合同时容量的 10 自动触点被错误拒绝");
    require(wideIds.frame().count == 16 && wideIds.frame().ids[15] == 19, "混淆指针编号与同时触点容量");
    require(wideIds.select(6) && wideIds.tracking(106), "后续真实触点建立失败");
    wideIds.cancelAutomatic();
    require(wideIds.frame().count == 7 && wideIds.frame().ids[6] == 6, "容量不足时误抬起真实触点");
    std::cout << "触点合流模型验证通过：接触快照、非接触槽、同ID连续触摸、长按移动、和弦结束、槽位复用、旋转、异常坐标及容量边界\n";
}
