#include "../touch_type_a.h"

#include <cstdlib>
#include <iostream>

using namespace luoxianlv::input;

static void require(bool condition, const char* message) {
    if (!condition) { std::cerr << message << '\n'; std::exit(1); }
}

static void point(TypeATouchReader& reader, int id, int x, int y, bool hardware = true) {
    require((!hardware || reader.tracking(id)) && reader.x(x) && reader.y(y) && reader.packet(),
            "触点包构造失败");
}

int main() {
    {
        TouchModel model(16, 0, 100, 0, 100, 101, 101, 0, true);
        TypeATouchReader reader(0, 100, 0, 100, true);
        require(reader.touch(1) && reader.tracking(1) && reader.x(10), "分批触摸帧失败");
        require(!model.touchPressed() && model.physicalCount() == 0, "半帧提前改变接触状态");
        require(reader.y(20) && reader.packet() && reader.commit(model) && model.physicalCount() == 1,
                "完整帧未同时更新接触与坐标");
        require(reader.touch(0) && model.physicalCount() == 1, "半帧提前抬起真实手指");
        require(reader.commit(model) && model.physicalCount() == 0, "无坐标抬手帧未释放接触");
        require(!reader.touch(-1) && !reader.touch(2), "非法接触键被接受");
    }
    {
        TouchModel model(16, 0, 100, 0, 100, 101, 101, 0, true);
        TypeATouchReader reader(0, 100, 0, 100, true);
        const float note[] = {80, 80};
        require(reader.commit(model) && model.begin(note, 1), "空帧不能开始自动演奏");
        require(model.touch(1), "接触键更新失败");
        point(reader, 42, 20, 30); point(reader, 43, 50, 60);
        require(reader.commit(model) && model.frame().count == 3, "物理双指未与自动音符合流");
        point(reader, 43, 51, 61); point(reader, 42, 21, 31); // 驱动可以交换包顺序。
        require(reader.commit(model), "乱序触点帧失败");
        auto frame = model.frame();
        require(frame.ids[0] == 0 && frame.xy[0] == 21 && frame.ids[1] == 1 && frame.xy[2] == 51,
                "包顺序变化导致手指交换身份");
        model.cancelAutomatic();
        require(model.frame().count == 2, "音符结束取消了真实手指");
        point(reader, 43, 52, 62);
        require(reader.commit(model) && model.frame().count == 1 && model.frame().ids[0] == 1,
                "抬起一个手指影响了仍按住的另一个手指");
        std::array<int, kMaxPointers> previous{}; previous.fill(-1); model.copyTracking(previous);
        point(reader, 44, 53, 63);
        require(reader.commit(model) && model.frame().count == 1, "新触点替换失败");
        model.copyTracking(previous);
        point(reader, 45, 54, 64);
        require(reader.commit(model) && model.hasReplacement(previous), "槽复用没有识别新触点");
        require(model.touch(0) && reader.packet() && reader.commit(model) && model.frame().count == 0,
                "空 SYN_MT_REPORT 未释放最终触点");
        require(model.touch(1) && reader.tracking(46) && reader.x(54) && reader.y(64) && reader.commit(model),
                "省略最后的 SYN_MT_REPORT 时丢失触点");
        require(model.frame().count == 1, "最后触点数量错误");
        reader.clear(); model.clear();
        require(reader.commit(model) && model.frame().count == 0, "再次激活继承了旧触点");
    }
    {
        TouchModel model(16, 0, 100, 0, 100, 101, 101, 0, true);
        TypeATouchReader reader(0, 100, 0, 100, false);
        model.touch(1);
        point(reader, 0, 0, 0, false); point(reader, 0, 100, 0, false);
        require(reader.commit(model), "匿名初始帧失败");
        // 贪心最近邻会错误选择；应保留总代价最小的两个物理身份。
        point(reader, 0, 40, 0, false); point(reader, 0, 0, 45, false);
        require(reader.commit(model), "匿名匹配失败");
        auto frame = model.frame();
        require(frame.ids[0] == 0 && frame.xy[0] == 0 && frame.xy[1] == 45 &&
                frame.ids[1] == 1 && frame.xy[2] == 40, "匿名触点匹配不是全局最优");
        point(reader, 0, 41, 0, false);
        require(reader.commit(model) && model.frame().ids[0] == 1, "匿名抬手后残留手指被重新编号");
        require(model.touch(0) && reader.commit(model) && model.physicalCount() == 0, "匿名抬手失败");
    }
    {
        TouchModel model(16, 0, 100, 0, 100, 101, 101, 0, true);
        TypeATouchReader missing(0, 100, 0, 100, true);
        require(missing.tracking(1) && missing.x(10) && !missing.packet(), "不完整坐标包被接受");
        TypeATouchReader duplicate(0, 100, 0, 100, true);
        point(duplicate, 1, 10, 20);
        require(duplicate.tracking(1) && duplicate.x(30) && duplicate.y(40) && !duplicate.packet(),
                "同帧重复硬件 ID 被接受");
        TypeATouchReader invalid(0, 100, 0, 100, true);
        require(!invalid.x(-1) && !invalid.y(101) && !invalid.tracking(-2), "非法事件被接受");
        require(invalid.tracking(-1) && invalid.packet() && invalid.commit(model), "释放包被拒绝");
        model.touch(1);
        require(!invalid.commit(model), "接触键按下但没有任何坐标仍被接受");
        TypeATouchReader crowded(0, 100, 0, 100, true);
        for (int i = 0; i < 16; ++i) point(crowded, i, i, i);
        require(crowded.commit(model) && model.frame().count == 16, "16 个物理触点被错误拒绝");
        const float note[] = {80, 80};
        require(!model.begin(note, 1), "真实触点已满时仍加入自动按键");
        for (int i = 0; i < 16; ++i) point(crowded, i, i, i);
        require(crowded.tracking(16) && crowded.x(16) && crowded.y(16) && !crowded.packet(),
                "第 17 个触点没有被拒绝");
    }
    std::cout << "Type A 验证通过：整帧合流、稳定指针、匿名匹配、抬手、异常包及容量边界\n";
}
