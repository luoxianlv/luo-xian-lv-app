#include "touch_model.h"
#include "touch_contact.h"
#include "touch_type_a.h"
#include "touch_device_selection.h"

#include <android/log.h>
#include <jni.h>
#include <linux/input.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <poll.h>

#include <atomic>
#include <cerrno>
#include <climits>
#include <cstdlib>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

using namespace luoxianlv::input;
using Clock = std::chrono::steady_clock;

namespace {
JavaVM* vm = nullptr;
constexpr int64_t kHeartbeatTimeoutMs = 4500;
constexpr int64_t kYieldWaitMs = 500;

bool privilegedIdentity() { return getuid() == 2000 || getuid() == 0; }

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now().time_since_epoch()).count();
}

std::string javaString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* raw = env->GetStringUTFChars(value, nullptr);
    if (!raw) return {};
    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

void fail(JNIEnv* env, const std::string& message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) { env->ThrowNew(type, message.c_str()); env->DeleteLocalRef(type); }
}

struct Descriptor {
    std::string path, name;
    input_id identity{};
    input_absinfo slot{}, x{}, y{}, tracking{};
    struct stat status{};
    std::string propertyBits, keyBits, absBits, rejection, fingerprint, capabilityIdentity;
    std::string sysfsTopology = "unknown", sysfsHash, driver, physHash, uniqHash, duplicateOf;
    int probeError = 0, topologyError = 0;
    bool hasTouchButton = false, hasSlots = false, hasTrackingIds = false, hasMultitouch = false, direct = false;
    bool compatible = false, capabilitiesKnown = false, hasDeviceNumber = false, uncertain = false;
    int count() const {
        return hasSlots ? (slot.maximum >= 0 && slot.maximum < kMaxPointers ? slot.maximum + 1 : 0)
                        : kMaxSimultaneousPointers;
    }
    const char* protocol() const { return hasSlots ? "type-b" : "type-a"; }
};

class UniqueFd {
public:
    explicit UniqueFd(int fd = -1) : value_(fd) {}
    ~UniqueFd() { if (value_ >= 0) close(value_); }
    int get() const { return value_; }
    int release() { int result = value_; value_ = -1; return result; }
private:
    int value_;
};

// 只作变更检测的稳定摘要，不将 PHYS/UNIQ、sysfs 原文写入 Bundle 或日志。
class DeviceFingerprint {
public:
    void add(const std::string& text) {
        const std::string size = std::to_string(text.size()) + ":";
        for (unsigned char value : size) append(value);
        for (unsigned char value : text) append(value);
    }
    template <typename Number> void number(Number value) { add(std::to_string(value)); }
    std::string digest() const {
        constexpr char digits[] = "0123456789abcdef";
        std::string result(16, '0');
        uint64_t value = hash_;
        for (int i = 15; i >= 0; --i) { result[i] = digits[value & 15]; value >>= 4; }
        return result;
    }
private:
    uint64_t hash_ = 14695981039346656037ULL;
    void append(unsigned char value) { hash_ = (hash_ ^ value) * 1099511628211ULL; }
};

std::string identityDigest(const std::string& value) {
    if (value.empty()) return {};
    DeviceFingerprint fingerprint;
    fingerprint.add(value);
    return fingerprint.digest();
}

template <size_t Size>
std::string bitsHex(const std::array<unsigned long, Size>& bits, int maximum) {
    constexpr size_t wordBits = sizeof(unsigned long) * 8;
    constexpr char digits[] = "0123456789abcdef";
    std::string result;
    for (int nibble = maximum / 4; nibble >= 0; --nibble) {
        unsigned value = 0;
        for (int bit = 0; bit < 4; ++bit) {
            const size_t code = static_cast<size_t>(nibble * 4 + bit);
            if (code <= static_cast<size_t>(maximum) &&
                (bits[code / wordBits] & (1UL << (code % wordBits)))) value |= 1U << bit;
        }
        result += digits[value];
    }
    return result;
}

std::string realInputPath(const std::string& path) {
    char resolved[PATH_MAX]{};
    return ::realpath(path.c_str(), resolved) ? std::string(resolved) : std::string{};
}

void addAxisIdentity(DeviceFingerprint& identity, const input_absinfo& axis) {
    // value 是会随触摸变化的状态；预检到准备之间不可用它判断设备换代。
    identity.number(axis.minimum); identity.number(axis.maximum);
    identity.number(axis.fuzz); identity.number(axis.flat); identity.number(axis.resolution);
}

void finishDescriptor(int fd, Descriptor& out) {
    std::string sysfs, phys, uniq;
    if (out.hasDeviceNumber) {
        sysfs = realInputPath("/sys/dev/char/" + std::to_string(major(out.status.st_rdev)) +
                             ":" + std::to_string(minor(out.status.st_rdev)));
        if (sysfs.empty()) out.topologyError = errno;
        else {
            out.sysfsTopology = sysfs.find("/devices/virtual/") != std::string::npos
                    ? "virtual-tree" : "device-tree";
            std::string parent = sysfs;
            for (int level = 0; level < 8 && parent.size() > 4; ++level) {
                std::string driver = realInputPath(parent + "/driver");
                if (!driver.empty()) { out.driver = driver.substr(driver.find_last_of('/') + 1); break; }
                parent = parent.substr(0, parent.find_last_of('/'));
            }
        }
    }
    if (fd >= 0) {
        char value[256]{};
        if (ioctl(fd, EVIOCGPHYS(sizeof(value)), value) > 0) {
            value[sizeof(value) - 1] = '\0'; phys = value;
        }
        std::memset(value, 0, sizeof(value));
        if (ioctl(fd, EVIOCGUNIQ(sizeof(value)), value) > 0) {
            value[sizeof(value) - 1] = '\0'; uniq = value;
        }
    }
    out.sysfsHash = identityDigest(sysfs);
    out.physHash = identityDigest(phys);
    out.uniqHash = identityDigest(uniq);
    DeviceFingerprint capability;
    capability.add("evdev-capabilities-v1");
    capability.add(out.name); capability.number(out.identity.bustype);
    capability.number(out.identity.vendor); capability.number(out.identity.product); capability.number(out.identity.version);
    capability.add(out.propertyBits); capability.add(out.keyBits); capability.add(out.absBits);
    addAxisIdentity(capability, out.x); addAxisIdentity(capability, out.y);
    addAxisIdentity(capability, out.slot); addAxisIdentity(capability, out.tracking);
    capability.add(sysfs); capability.add(out.driver); capability.add(phys); capability.add(uniq);
    out.capabilityIdentity = capability.digest();
    DeviceFingerprint identity;
    identity.add("evdev-identity-v1"); identity.add(out.capabilityIdentity);
    identity.number(static_cast<uint64_t>(out.status.st_dev));
    identity.number(static_cast<uint64_t>(out.status.st_rdev));
    identity.number(static_cast<uint64_t>(out.status.st_ino));
    identity.number(out.status.st_ctim.tv_sec); identity.number(out.status.st_ctim.tv_nsec);
    out.fingerprint = identity.digest();
}

bool inspect(int fd, const std::string& path, Descriptor& out) {
    out.path = path;
    auto reject = [&](const char* reason, int error = 0, bool uncertain = false) {
        out.rejection = reason; out.probeError = error; out.uncertain = uncertain;
        if (uncertain) out.capabilitiesKnown = false;
        finishDescriptor(fd, out);
        return false;
    };
    if (fstat(fd, &out.status) < 0) return reject("stat-failed", errno, true);
    if (!S_ISCHR(out.status.st_mode)) return reject("not-character-device");
    out.hasDeviceNumber = true;
    constexpr size_t wordBits = sizeof(unsigned long) * 8;
    std::array<unsigned long, (INPUT_PROP_MAX / wordBits) + 1> properties{};
    std::array<unsigned long, (KEY_MAX / wordBits) + 1> keys{};
    std::array<unsigned long, (ABS_MAX / wordBits) + 1> axes{};
    char name[256]{};
    if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) < 0) return reject("name-read-failed", errno, true);
    name[sizeof(name) - 1] = '\0';
    out.name = name;
    if (ioctl(fd, EVIOCGID, &out.identity) < 0) return reject("identity-read-failed", errno, true);
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(axes)), axes.data()) < 0)
        return reject("axis-capabilities-failed", errno, true);
    out.absBits = bitsHex(axes, ABS_MAX);
    auto axis = [&](int code) { return (axes[code / wordBits] & (1UL << (code % wordBits))) != 0; };
    out.hasMultitouch = axis(ABS_MT_POSITION_X) && axis(ABS_MT_POSITION_Y);
    out.hasSlots = axis(ABS_MT_SLOT);
    out.hasTrackingIds = axis(ABS_MT_TRACKING_ID);
    if (ioctl(fd, EVIOCGPROP(sizeof(properties)), properties.data()) < 0)
        return reject("properties-read-failed", errno, true);
    out.propertyBits = bitsHex(properties, INPUT_PROP_MAX);
    out.direct = (properties[INPUT_PROP_DIRECT / wordBits] & (1UL << (INPUT_PROP_DIRECT % wordBits))) != 0;
    const int keyBytes = ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys.data());
    if (keyBytes < 0) return reject("key-capabilities-failed", errno, true);
    out.keyBits = bitsHex(keys, KEY_MAX);
    out.hasTouchButton = (keys[BTN_TOUCH / wordBits] & (1UL << (BTN_TOUCH % wordBits))) != 0;
    out.capabilitiesKnown = true;
    if (!out.hasMultitouch) return reject("not-multitouch");
    // 即便后续因属性或协议排除，仍保留完整的触屏能力摘要。
    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &out.x) < 0 ||
        ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &out.y) < 0)
        return reject("coordinates-read-failed", errno, true);
    if (out.hasSlots && ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &out.slot) < 0)
        return reject("slots-read-failed", errno, true);
    if (out.hasTrackingIds && ioctl(fd, EVIOCGABS(ABS_MT_TRACKING_ID), &out.tracking) < 0)
        return reject("tracking-read-failed", errno, true);
    // DIRECT 是坐标映射属性，不是硬件来源证明；Android Reader/IDC 也可明确覆写类型。
    if ((properties[INPUT_PROP_SEMI_MT / wordBits] & (1UL << (INPUT_PROP_SEMI_MT % wordBits))) != 0)
        return reject("semi-multitouch");
    if (keyBytes <= BTN_TOUCH / 8) return reject("key-capabilities-incomplete", EPROTO, true);
    if (out.hasSlots) {
        if (!out.hasTrackingIds) return reject("type-b-without-tracking");
        if (out.slot.minimum != 0 || out.slot.maximum < 0 || out.slot.maximum >= kMaxPointers - 1)
            return reject("unsupported-slot-range");
    } else if (!out.hasTouchButton) return reject("type-a-without-touch-button");
    if (out.x.minimum >= out.x.maximum || out.y.minimum >= out.y.maximum ||
        static_cast<int64_t>(out.x.maximum) - out.x.minimum > INT32_MAX ||
        static_cast<int64_t>(out.y.maximum) - out.y.minimum > INT32_MAX)
        return reject("invalid-coordinate-range");
    out.compatible = true;
    finishDescriptor(fd, out);
    return true;
}

const char* decisionName(DeviceDecision decision) {
    switch (decision) {
        case DeviceDecision::Unique: return "unique";
        case DeviceDecision::Preferred: return "preferred";
        case DeviceDecision::Ambiguous: return "ambiguous";
        case DeviceDecision::Incomplete: return "incomplete";
        case DeviceDecision::NeedsRoute: return "needs-route";
        case DeviceDecision::Changed: return "changed";
        case DeviceDecision::InvalidPath: return "invalid-path";
        default: return "none";
    }
}

std::string selectionMessage(DeviceDecision decision) {
    switch (decision) {
        case DeviceDecision::Ambiguous: return "发现多个兼容触屏，需要确认当前主屏输入来源";
        case DeviceDecision::Incomplete: return "触屏设备列表读取不完整，不能确认输入来源";
        case DeviceDecision::NeedsRoute: return "此多点设备需要系统显示路由确认，不能按唯一节点猜选";
        case DeviceDecision::Changed: return "触屏设备在扫描中发生变化，请重新预检";
        case DeviceDecision::InvalidPath: return "触屏首选必须使用完整的输入设备路径";
        case DeviceDecision::None: return "没有找到对应的兼容多点触屏";
        default: return {};
    }
}

struct DeviceInventory {
    std::vector<Descriptor> descriptors;
    TouchDeviceSelection selection;
    std::string error, fingerprint;
    size_t nodeCount = 0, unknownCount = 0, duplicateCount = 0;
    bool truncated = false;
};

DeviceInventory inspectDevices(const std::string& preferred) {
    DeviceInventory result;
    if (!privilegedIdentity()) {
        result.error = "触控共存需要已授权的 Shizuku 或无线调试连接";
        return result;
    }
    DIR* directory = opendir("/dev/input");
    if (!directory) { result.error = "系统不允许读取触屏设备目录"; return result; }
    std::vector<std::string> paths;
    errno = 0;
    while (dirent* entry = readdir(directory)) {
        std::string path = "/dev/input/" + std::string(entry->d_name);
        if (!exactEventPath(path)) continue;
        ++result.nodeCount;
        auto place = std::lower_bound(paths.begin(), paths.end(), path);
        if (paths.size() < kMaxTouchDeviceNodes) paths.insert(place, path);
        else {
            result.truncated = true;
            if (place != paths.end()) { paths.insert(place, path); paths.pop_back(); }
        }
    }
    const int directoryError = errno;
    closedir(directory);
    if (directoryError != 0) {
        result.error = "触屏设备目录读取中断，请重新预检";
        result.truncated = true;
    }
    std::vector<TouchDeviceCandidate> candidates;
    for (const std::string& path : paths) {
        UniqueFd device(open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC));
        Descriptor descriptor;
        if (device.get() >= 0) inspect(device.get(), path, descriptor);
        else {
            descriptor.path = path; descriptor.probeError = errno; descriptor.uncertain = true;
            descriptor.rejection = errno == EACCES || errno == EPERM ? "open-denied" : "open-failed";
            descriptor.hasDeviceNumber = stat(path.c_str(), &descriptor.status) == 0 && S_ISCHR(descriptor.status.st_mode);
            finishDescriptor(-1, descriptor);
        }
        result.unknownCount += descriptor.uncertain;
        candidates.push_back({descriptor.path, static_cast<uint64_t>(descriptor.status.st_rdev),
                              descriptor.hasDeviceNumber, descriptor.compatible,
                              descriptor.uncertain, descriptor.capabilityIdentity, descriptor.direct});
        result.descriptors.push_back(std::move(descriptor));
    }
    result.selection = selectTouchDevice(candidates, preferred, result.truncated);
    if (result.selection.decision == DeviceDecision::Changed)
        result.error = selectionMessage(result.selection.decision);
    DeviceFingerprint snapshot;
    snapshot.add("evdev-inventory-v1"); snapshot.number(result.nodeCount); snapshot.number(result.truncated);
    for (size_t i = 0; i < result.descriptors.size(); ++i) {
        Descriptor& descriptor = result.descriptors[i];
        size_t first = result.selection.duplicateOf[i];
        if (first != kNoDeviceIndex) {
            descriptor.duplicateOf = result.descriptors[first].path;
            ++result.duplicateCount;
        }
        snapshot.add(descriptor.path); snapshot.add(descriptor.fingerprint);
        snapshot.add(descriptor.rejection); snapshot.number(descriptor.probeError);
    }
    result.fingerprint = snapshot.digest();
    return result;
}

ContactState readContact(int fd, const Descriptor& descriptor,
                         std::array<int32_t, kMaxPointers + 1>* tracking = nullptr) {
    constexpr size_t wordBits = sizeof(unsigned long) * 8;
    auto readTouch = [fd](int& pressed) {
        std::array<unsigned long, (KEY_MAX / wordBits) + 1> keys{};
        const int bytes = ioctl(fd, EVIOCGKEY(sizeof(keys)), keys.data());
        if (bytes < 0) return false;
        if (bytes <= BTN_TOUCH / 8) { errno = EPROTO; return false; }
        pressed = (keys[BTN_TOUCH / wordBits] & (1UL << (BTN_TOUCH % wordBits))) != 0;
        return true;
    };
    int before = -1, after = -1;
    if (descriptor.hasTouchButton && !readTouch(before))
        return evaluateContact(-1, true, before, after, errno);
    if (!descriptor.hasSlots) {
        // Type A 没有内核槽位快照；仅在前后接触键均抬起时从空帧开始读取。
        if (!readTouch(after)) return evaluateContact(-1, true, before, after, errno);
        if (tracking) tracking->fill(-1);
        return evaluateContact(0, true, before, after);
    }
    std::array<int32_t, kMaxPointers + 1> values{};
    values.fill(INT32_MIN); // ioctl 可能只写实际槽数；缺失项不能从初始 0 推断为有效 tracking。
    values[0] = ABS_MT_TRACKING_ID;
    const int count = descriptor.count();
    if (ioctl(fd, EVIOCGMTSLOTS(sizeof(int32_t) * (count + 1)), values.data()) < 0)
        return evaluateContact(-1, descriptor.hasTouchButton, before, after, errno);
    int activeSlots = 0;
    for (int i = 1; i <= count; ++i) {
        if (values[i] < -1) return evaluateContact(-1, descriptor.hasTouchButton, before, after, EPROTO);
        activeSlots += values[i] >= 0;
    }
    // 键与槽分别读取；前后状态变化时重试，不能把按下过程中的空槽当作已抬手。
    if (descriptor.hasTouchButton && !readTouch(after))
        return evaluateContact(activeSlots, true, before, after, errno);
    if (tracking) *tracking = values;
    return evaluateContact(activeSlots, descriptor.hasTouchButton, before, after);
}

struct Command {
    int type = 0;
    int durationMs = 0;
    int count = 0;
    int64_t token = 0;
    std::array<float, kMaxAutomaticPointers * 2> xy{};
};

class Engine : public std::enable_shared_from_this<Engine> {
public:
    Engine(JNIEnv* env, jobject listener, const Descriptor& descriptor, int fd,
           int width, int height, int rotation)
        : descriptor_(descriptor), inputFd_(fd), width_(width), height_(height),
          model_(descriptor.count(), descriptor.x.minimum, descriptor.x.maximum,
                 descriptor.y.minimum, descriptor.y.maximum, width, height, rotation, descriptor.hasTouchButton),
          typeA_(descriptor.x.minimum, descriptor.x.maximum, descriptor.y.minimum,
                 descriptor.y.maximum, descriptor.hasTrackingIds) {
        contactState_.touchSupported = descriptor.hasTouchButton;
        listener_ = env->NewGlobalRef(listener);
        if (!listener_ || env->ExceptionCheck()) return;
        jclass type = env->GetObjectClass(listener);
        if (!type || env->ExceptionCheck()) return;
        frameMethod_ = env->GetMethodID(type, "frame", "([I[FZ)Z");
        if (!frameMethod_ || env->ExceptionCheck()) { env->DeleteLocalRef(type); return; }
        finishedMethod_ = env->GetMethodID(type, "noteFinished", "(JZLjava/lang/String;)V");
        env->DeleteLocalRef(type);
        wakeFd_ = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
        previousTracking_.fill(-1);
    }

    ~Engine() {
        if (inputFd_ >= 0) { ioctl(inputFd_, EVIOCGRAB, 0); ::close(inputFd_); }
        if (wakeFd_ >= 0) ::close(wakeFd_);
        if (listener_) {
            JNIEnv* env = nullptr;
            bool attached = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK;
            if (attached && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
            env->DeleteGlobalRef(listener_);
            if (attached) vm->DetachCurrentThread();
        }
    }

    bool valid() const { return listener_ && frameMethod_ && finishedMethod_ && wakeFd_ >= 0; }

    void start() {
        auto self = shared_from_this();
        std::thread worker([self] { self->run(); });
        try {
            std::thread watcher([self] { self->watchdog(); });
            worker.detach();
            watcher.detach();
        } catch (...) {
            emergency("无法启动触控看门狗");
            worker.join();
            throw;
        }
    }

    bool activate() {
        if (stopping_) return rejectActivation("触控引擎已停止");
        if (ending_) return rejectActivation("正在归还触屏，请稍后重试");
        if (active_) return true;
        std::lock_guard lock(ioMutex_);
        if (stopping_ || inputFd_ < 0) return rejectActivation("触控设备已关闭");
        if (ending_) return rejectActivation("正在归还触屏，请稍后重试");
        if (active_) return true;
        // 接管前丢弃本 fd 的旧事件，不影响系统的物理输入；必须从完整同步帧开始取快照。
        std::array<input_event, 64> stale{};
        int drained = 0;
        ssize_t bytes;
        while ((bytes = ::read(inputFd_, stale.data(), sizeof(stale))) > 0) {
            if (bytes % sizeof(input_event) != 0) return rejectActivation("触屏事件长度无效");
            const auto& last = stale[bytes / sizeof(input_event) - 1];
            activationFrameComplete_ = last.type == EV_SYN && last.code == SYN_REPORT;
            if (++drained == 16) return rejectActivation("正在同步触屏事件，请稍候", true);
        }
        if (bytes == 0 || (errno != EAGAIN && errno != EINTR))
            return rejectActivation("触屏事件读取失败");
        if (!activationFrameComplete_) return rejectActivation("正在等待触屏同步帧", true);
        std::array<int32_t, kMaxPointers + 1> beforeTracking{};
        const ContactState before = activationContact(&beforeTracking);
        if (!before.adoptable(descriptor_.hasSlots))
            return rejectActivation(before.readError ? "无法读取当前触点状态" :
                    descriptor_.hasSlots ? "正在同步触点状态，请稍候" : "此触屏需先抬起手指再开始演奏",
                    before.readError == 0);
        input_absinfo selected{};
        selected.value = INT32_MIN;
        initialX_.fill(INT32_MIN);
        initialY_.fill(INT32_MIN);
        initialX_[0] = ABS_MT_POSITION_X;
        initialY_[0] = ABS_MT_POSITION_Y;
        const size_t snapshotBytes = sizeof(int32_t) * (descriptor_.count() + 1);
        if (descriptor_.hasSlots && (ioctl(inputFd_, EVIOCGABS(ABS_MT_SLOT), &selected) < 0 ||
            ioctl(inputFd_, EVIOCGMTSLOTS(snapshotBytes), initialX_.data()) < 0 ||
            ioctl(inputFd_, EVIOCGMTSLOTS(snapshotBytes), initialY_.data()) < 0)) {
            const int failureErrno = errno;
            return rejectActivation("无法读取触屏坐标快照（errno=" + std::to_string(failureErrno) + "）");
        }
        if (!descriptor_.hasSlots) {
            selected.value = 0;
            initialX_.fill(descriptor_.x.minimum);
            initialY_.fill(descriptor_.y.minimum);
        }
        if (selected.value < 0 || selected.value >= descriptor_.count()) {
            return rejectActivation("触屏当前槽位无效，未接管触屏");
        }
        for (int i = 1; i <= descriptor_.count(); ++i) {
            if (initialX_[i] == INT32_MIN || initialY_[i] == INT32_MIN) {
                return rejectActivation("触屏坐标快照不完整，未接管触屏");
            }
        }
        const ContactState after = activationContact(&initialTracking_);
        if (!after.adoptable(descriptor_.hasSlots) || beforeTracking != initialTracking_ ||
                before.touchPressed != after.touchPressed)
            return rejectActivation(after.readError ? "无法读取当前触点状态" : "触点正在变化，正在重新同步",
                    after.readError == 0);
        if (!after.released() && after.trackedSlots >= kMaxSimultaneousPointers)
            return rejectActivation("真实触点已达系统上限，无法加入自动按键");
        for (int i = 1; i <= descriptor_.count(); ++i) {
            if (initialTracking_[i] >= 0 && !after.released() &&
                    (initialX_[i] < descriptor_.x.minimum || initialX_[i] > descriptor_.x.maximum ||
                     initialY_[i] < descriptor_.y.minimum || initialY_[i] > descriptor_.y.maximum))
                return rejectActivation("已按下触点的坐标无效，未接管触屏");
        }
        // 读取快照期间又有事件时重新采样，所有可能失败的读取均在抓取之前完成。
        pollfd pending{inputFd_, POLLIN, 0};
        const int queued = poll(&pending, 1, 0);
        if (queued < 0 || (pending.revents & (POLLERR | POLLHUP | POLLNVAL)))
            return rejectActivation("无法确认触屏事件状态，未接管触屏");
        if (pending.revents & POLLIN) return rejectActivation("触点正在变化，正在重新同步", true);
        if (ioctl(inputFd_, EVIOCGRAB, 1) < 0)
            return rejectActivation("系统拒绝接管触屏（errno=" + std::to_string(errno) + "）");
        // 抓取后不再清空队列：快照之后的移动、松手必须交给工作线程继续处理。
        initialTouchPressed_ = after.touchPressed;
        { std::lock_guard errorLock(errorMutex_); failure_.clear(); }
        activationWaiting_.store(false);
        initialSlot_ = selected.value;
        activation_.request();
        heartbeatAt_.store(nowMs());
        active_.store(true);
        wake();
        return true;
    }

    bool begin(const float* xy, int count, int duration, int64_t token) {
        if (!active_ || ending_ || stopping_ || count < 1 || count > model_.maximumAutomatic() ||
            duration < 1 || duration > 120000 || token == 0) return false;
        for (int i = 0; i < count; ++i)
            if (!std::isfinite(xy[i * 2]) || !std::isfinite(xy[i * 2 + 1]) ||
                xy[i * 2] < 0 || xy[i * 2 + 1] < 0 || xy[i * 2] > width_ - 1 ||
                xy[i * 2 + 1] > height_ - 1) return false;
        Command command;
        command.type = 0;
        command.count = count;
        command.durationMs = duration;
        command.token = token;
        std::copy(xy, xy + count * 2, command.xy.begin());
        return enqueue(command);
    }

    void command(int type) {
        if (type != 1 && type != 2) return;
        if (type == 2) {
            yieldRequestedAt_.store(nowMs());
            ending_.store(true);
        }
        Command command;
        command.type = type;
        if (!enqueue(command) && type == 2) emergency("触控指令队列不可用");
    }

    void heartbeat() { heartbeatAt_.store(nowMs()); }
    bool active() const { return active_.load() && !stopping_.load(); }
    std::string lastFailure() { std::lock_guard lock(errorMutex_); return failure_; }
    bool activationWaiting() const { return activationWaiting_.load() && !stopping_.load(); }
    ContactState contactState() { std::lock_guard lock(errorMutex_); return contactState_; }

    void stop() {
        stopping_.store(true);
        releaseGrab(true);
        wake();
        if (gettid() == workerTid_.load()) return;
        std::unique_lock lock(finishMutex_);
        // 即使系统注入 Binder 卡住，调用者也不会无限等待；抓取已在上方释放。
        finishedCondition_.wait_for(lock, std::chrono::seconds(2), [this] { return finished_; });
    }

private:
    Descriptor descriptor_;
    int inputFd_ = -1, wakeFd_ = -1;
    int width_, height_;
    jobject listener_ = nullptr;
    jmethodID frameMethod_ = nullptr, finishedMethod_ = nullptr;
    TouchModel model_;
    TypeATouchReader typeA_;
    std::array<int, kMaxPointers> previousTracking_{};
    std::array<int32_t, kMaxPointers + 1> initialX_{}, initialY_{}, initialTracking_{};
    int initialSlot_ = 0;
    int initialTouchPressed_ = 0;
    bool activationFrameComplete_ = true;
    std::atomic<bool> active_{false}, ending_{false}, stopping_{false};
    std::atomic<bool> activationWaiting_{false};
    ActivationGate activation_;
    std::atomic<int64_t> heartbeatAt_{0}, yieldRequestedAt_{0};
    std::mutex ioMutex_, commandsMutex_, errorMutex_, finishMutex_;
    std::deque<Command> commands_;
    std::string failure_;
    ContactState contactState_;
    std::condition_variable finishedCondition_;
    bool finished_ = false;
    std::atomic<pid_t> workerTid_{0};
    int64_t noteToken_ = 0, noteDeadline_ = 0, yieldDeadline_ = 0;

    ContactState activationContact(std::array<int32_t, kMaxPointers + 1>* tracking) {
        const ContactState observed = readContact(inputFd_, descriptor_, tracking);
        { std::lock_guard lock(errorMutex_); contactState_ = observed; }
        return observed;
    }

    bool rejectActivation(const std::string& message, bool waitingForFingers = false) {
        std::lock_guard lock(errorMutex_);
        if (!stopping_ || failure_.empty()) failure_ = message;
        activationWaiting_.store(waitingForFingers && !stopping_.load());
        return false;
    }

    void wake() const {
        if (wakeFd_ < 0) return;
        uint64_t signal = 1;
        const auto ignored = ::write(wakeFd_, &signal, sizeof(signal));
        (void) ignored;
    }

    bool enqueue(const Command& command) {
        std::lock_guard lock(commandsMutex_);
        if (stopping_ || commands_.size() >= 32) return false;
        commands_.push_back(command);
        wake();
        return true;
    }

    void releaseGrab(bool closeDevice = false) {
        std::lock_guard lock(ioMutex_);
        active_.store(false);
        if (inputFd_ >= 0) {
            ioctl(inputFd_, EVIOCGRAB, 0);
            if (closeDevice) { ::close(inputFd_); inputFd_ = -1; }
        }
    }

    void emergency(const std::string& message) {
        activationWaiting_.store(false);
        {
            std::lock_guard lock(errorMutex_);
            if (failure_.empty()) failure_ = message;
        }
        stopping_.store(true);
        releaseGrab(true);
        wake();
    }

    void watchdog() {
        while (!stopping_) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            if (active_ && nowMs() - heartbeatAt_.load() > kHeartbeatTimeoutMs) {
                emergency("触控助手心跳超时，已归还触屏");
                break;
            }
            if (active_ && ending_ && nowMs() - yieldRequestedAt_.load() > kYieldWaitMs) {
                emergency("暂停触控等待超时，已归还触屏");
                break;
            }
        }
    }

    bool publish(JNIEnv* env, const Frame& frame, bool cancel = false) {
        jintArray ids = env->NewIntArray(frame.count);
        jfloatArray xy = env->NewFloatArray(frame.count * 2);
        if (!ids || !xy) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (ids) env->DeleteLocalRef(ids);
            if (xy) env->DeleteLocalRef(xy);
            emergency("触控帧内存不足，已归还触屏");
            return false;
        }
        if (frame.count > 0) {
            env->SetIntArrayRegion(ids, 0, frame.count, frame.ids.data());
            env->SetFloatArrayRegion(xy, 0, frame.count * 2, frame.xy.data());
        }
        bool accepted = env->CallBooleanMethod(listener_, frameMethod_, ids, xy, cancel ? JNI_TRUE : JNI_FALSE);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            __android_log_print(ANDROID_LOG_WARN, "触控共存", "合流回调发生 Java 异常，已停止并归还触屏");
            accepted = false;
        }
        env->DeleteLocalRef(ids);
        env->DeleteLocalRef(xy);
        if (!accepted && !cancel) emergency("系统未接受合流触摸事件，已归还触屏");
        return accepted;
    }

    bool publishState(JNIEnv* env) {
        if (!active_ || stopping_) return false;
        if (model_.hasReplacement(previousTracking_) &&
            !publish(env, model_.frame(&previousTracking_, true))) return false;
        if (!active_ || stopping_) return false;
        const bool accepted = publish(env, model_.frame());
        if (accepted) model_.copyTracking(previousTracking_);
        return accepted;
    }

    void notifyFinished(JNIEnv* env, int64_t token, bool success, const std::string& message) {
        if (token == 0) return;
        jstring text = env->NewStringUTF(message.c_str());
        if (text) {
            env->CallVoidMethod(listener_, finishedMethod_, static_cast<jlong>(token),
                                success ? JNI_TRUE : JNI_FALSE, text);
            env->DeleteLocalRef(text);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            __android_log_print(ANDROID_LOG_WARN, "触控共存", "演奏回调发生 Java 异常，已停止并归还触屏");
            emergency("演奏回调失败，已归还触屏");
        }
    }

    void finishNote(JNIEnv* env, bool success, const std::string& message) {
        const int64_t token = noteToken_;
        noteToken_ = 0;
        noteDeadline_ = 0;
        model_.cancelAutomatic();
        success = active_ && !stopping_ && publishState(env) && success;
        success = success && active_ && !stopping_;
        notifyFinished(env, token, success, message);
    }

    void processCommands(JNIEnv* env) {
        std::deque<Command> pending;
        {
            std::lock_guard lock(commandsMutex_);
            pending.swap(commands_);
        }
        for (const Command& command : pending) {
            if (command.type == 0) {
                applyPendingActivation();
                if (noteToken_) finishNote(env, false, "已由后续音符替换");
                if (!active_ || ending_ || stopping_ || !model_.begin(command.xy.data(), command.count)) {
                    notifyFinished(env, command.token, false, "触控引擎暂不可用或按键位置无效");
                    continue;
                }
                noteToken_ = command.token;
                noteDeadline_ = nowMs() + command.durationMs;
                if (!publishState(env)) finishNote(env, false, "系统未接受自动按键");
            } else if (command.type == 1) {
                finishNote(env, false, "已取消自动按键");
            } else {
                finishNote(env, false, "已暂停演奏");
                yieldDeadline_ = nowMs() + kYieldWaitMs;
            }
        }
    }

    bool processEvent(JNIEnv* env, const input_event& event) {
        if (event.type == EV_SYN && event.code == SYN_DROPPED) {
            emergency("触屏事件丢失，已停止合流并归还触屏");
            return false;
        }
        if (event.type == EV_KEY && event.code == BTN_TOUCH && descriptor_.hasTouchButton) {
            if (!(descriptor_.hasSlots ? model_.touch(event.value) : typeA_.touch(event.value))) {
                emergency("触屏上报了异常接触状态，已归还触屏");
                return false;
            }
        } else if (event.type == EV_ABS) {
            bool valid = true;
            if (!descriptor_.hasSlots) {
                switch (event.code) {
                    case ABS_MT_TRACKING_ID: valid = typeA_.tracking(event.value); break;
                    case ABS_MT_POSITION_X: valid = typeA_.x(event.value); break;
                    case ABS_MT_POSITION_Y: valid = typeA_.y(event.value); break;
                    default: break;
                }
            } else switch (event.code) {
                case ABS_MT_SLOT: valid = model_.select(event.value); break;
                case ABS_MT_TRACKING_ID: valid = model_.tracking(event.value); break;
                case ABS_MT_POSITION_X: valid = model_.x(event.value); break;
                case ABS_MT_POSITION_Y: valid = model_.y(event.value); break;
                default: break;
            }
            if (!valid) { emergency("触屏上报了异常触点，已归还触屏"); return false; }
        } else if (!descriptor_.hasSlots && event.type == EV_SYN && event.code == SYN_MT_REPORT) {
            if (!typeA_.packet()) { emergency("触屏触点数据包不完整，已归还触屏"); return false; }
        } else if (event.type == EV_SYN && event.code == SYN_REPORT) {
            if (!descriptor_.hasSlots && !typeA_.commit(model_)) {
                emergency("触屏同步帧不完整，已归还触屏"); return false;
            }
            if (model_.physicalCount() > kMaxSimultaneousPointers) {
                emergency("真实触点超过系统容量，已归还触屏");
                return false;
            }
            if (model_.frame().count > kMaxSimultaneousPointers)
                finishNote(env, false, "真实触点增加，已释放自动按键");
            return publishState(env);
        }
        return true;
    }

    void readPhysical(JNIEnv* env) {
        std::array<input_event, 64> events{};
        ssize_t bytes;
        {
            std::lock_guard lock(ioMutex_);
            if (!active_ || stopping_) return;
            applyPendingActivation();
            bytes = ::read(inputFd_, events.data(), sizeof(events));
        }
        if (bytes < 0 && (errno == EAGAIN || errno == EINTR)) return;
        if (bytes <= 0 || bytes % sizeof(input_event) != 0) {
            emergency("触屏已断开或事件损坏，已停止合流");
            return;
        }
        for (size_t i = 0; i < static_cast<size_t>(bytes) / sizeof(input_event); ++i) {
            if (!active_ || stopping_ || !processEvent(env, events[i])) break;
        }
    }

    void applyPendingActivation() {
        activation_.apply([this] {
            model_.clear();
            typeA_.clear();
            for (int i = 0; i < descriptor_.count(); ++i) {
                model_.select(i);
                model_.tracking(initialTracking_[i + 1]);
                // 空槽可能尚未初始化坐标；按下后的实际事件仍严格验证范围。
                model_.x(std::clamp(initialX_[i + 1], descriptor_.x.minimum, descriptor_.x.maximum));
                model_.y(std::clamp(initialY_[i + 1], descriptor_.y.minimum, descriptor_.y.maximum));
            }
            model_.select(initialSlot_);
            if (descriptor_.hasTouchButton) model_.touch(initialTouchPressed_);
            previousTracking_.fill(-1);
            yieldDeadline_ = 0;
        });
    }

    void run() {
        workerTid_.store(gettid());
        JNIEnv* env = nullptr;
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            emergency("无法启动触控工作线程");
            markFinished();
            return;
        }
        while (!stopping_) {
            applyPendingActivation();
            processCommands(env);
            if (noteToken_ && nowMs() >= noteDeadline_) finishNote(env, true, "按键完成");
            if (yieldDeadline_ && (model_.physicalCount() == 0 || nowMs() >= yieldDeadline_)) {
                if (model_.physicalCount() != 0) publish(env, model_.frame(), true);
                releaseGrab();
                model_.clear();
                previousTracking_.fill(-1);
                yieldDeadline_ = 0;
                ending_.store(false);
            }
            int physicalFd;
            { std::lock_guard lock(ioMutex_); physicalFd = active_ ? inputFd_ : -1; }
            pollfd descriptors[2] = {{wakeFd_, POLLIN, 0}, {physicalFd, POLLIN, 0}};
            int waitMs = 40;
            if (noteToken_) waitMs = static_cast<int>(std::max<int64_t>(0, std::min<int64_t>(waitMs, noteDeadline_ - nowMs())));
            int ready = poll(descriptors, 2, waitMs);
            if (ready < 0 && errno != EINTR) { emergency("触屏事件等待失败，已归还触屏"); break; }
            if (descriptors[0].revents & POLLIN) { uint64_t signal; while (::read(wakeFd_, &signal, sizeof(signal)) > 0) {} }
            if (descriptors[1].revents & (POLLERR | POLLHUP | POLLNVAL)) { emergency("触屏已断开，已停止合流"); break; }
            if (descriptors[1].revents & POLLIN) readPhysical(env);
        }
        std::string reason;
        { std::lock_guard lock(errorMutex_); reason = failure_.empty() ? "触控助手已关闭" : failure_; }
        publish(env, model_.frame(), true);
        finishNote(env, false, reason);
        // 尚未执行的音符也返回失败，宿主不会一直等待回调。
        std::deque<Command> abandoned;
        { std::lock_guard lock(commandsMutex_); abandoned.swap(commands_); }
        for (const Command& command : abandoned)
            if (command.type == 0) notifyFinished(env, command.token, false, reason);
        releaseGrab(true);
        vm->DetachCurrentThread();
        markFinished();
    }

    void markFinished() {
        { std::lock_guard lock(finishMutex_); finished_ = true; }
        finishedCondition_.notify_all();
    }
};

std::mutex enginesMutex;
std::map<jlong, std::shared_ptr<Engine>> engines;
std::atomic<jlong> nextHandle{1};

std::shared_ptr<Engine> lookup(jlong handle) {
    std::lock_guard lock(enginesMutex);
    auto found = engines.find(handle);
    return found == engines.end() ? nullptr : found->second;
}

template <size_t Size>
jobjectArray javaStrings(JNIEnv* env, jclass strings, const std::array<std::string, Size>& values) {
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size()), strings, nullptr);
    if (!result) return nullptr;
    for (size_t i = 0; i < values.size(); ++i) {
        jstring text = env->NewStringUTF(values[i].c_str());
        if (!text) { env->DeleteLocalRef(result); return nullptr; }
        env->SetObjectArrayElement(result, static_cast<jsize>(i), text);
        env->DeleteLocalRef(text);
        if (env->ExceptionCheck()) { env->DeleteLocalRef(result); return nullptr; }
    }
    return result;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* machine, void*) {
    vm = machine;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_luoxianlv_input_TouchEngine_nativeProbe(JNIEnv* env, jclass, jstring preferred) {
    std::string preference = javaString(env, preferred);
    if (env->ExceptionCheck()) return nullptr;
    DeviceInventory inventory = inspectDevices(preference);
    jclass strings = env->FindClass("java/lang/String");
    if (!strings || env->ExceptionCheck()) return nullptr;
    jclass rows = env->FindClass("[Ljava/lang/String;");
    if (!rows || env->ExceptionCheck()) { env->DeleteLocalRef(strings); return nullptr; }
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(inventory.descriptors.size() + 1), rows, nullptr);
    env->DeleteLocalRef(rows);
    if (!result) { env->DeleteLocalRef(strings); return nullptr; }
    std::array<std::string, 11> metadata = {"touch-candidates-v1", inventory.error,
        inventory.truncated ? "1" : "0", std::to_string(inventory.nodeCount),
        inventory.selection.selected == kNoDeviceIndex ? "-1" : std::to_string(inventory.selection.selected),
        decisionName(inventory.selection.decision), selectionMessage(inventory.selection.decision),
        std::to_string(inventory.selection.unique.size()), std::to_string(inventory.unknownCount),
        std::to_string(inventory.duplicateCount), inventory.fingerprint};
    jobjectArray row = javaStrings(env, strings, metadata);
    if (!row) { env->DeleteLocalRef(strings); env->DeleteLocalRef(result); return nullptr; }
    env->SetObjectArrayElement(result, 0, row);
    env->DeleteLocalRef(row);
    for (size_t i = 0; i < inventory.descriptors.size() && !env->ExceptionCheck(); ++i) {
        const Descriptor& descriptor = inventory.descriptors[i];
        const std::string deviceNumber = descriptor.hasDeviceNumber
                ? std::to_string(major(descriptor.status.st_rdev)) + ":" + std::to_string(minor(descriptor.status.st_rdev))
                : std::string{};
        std::array<std::string, 34> values = {descriptor.path, descriptor.name,
            std::to_string(descriptor.hasMultitouch ? descriptor.count() : 0),
            std::to_string(descriptor.x.minimum), std::to_string(descriptor.x.maximum),
            std::to_string(descriptor.y.minimum), std::to_string(descriptor.y.maximum),
            std::to_string(descriptor.identity.vendor), std::to_string(descriptor.identity.product),
            std::to_string(descriptor.identity.version), std::to_string(descriptor.identity.bustype),
            descriptor.rejection, descriptor.hasMultitouch ? descriptor.protocol() : "",
            descriptor.hasTrackingIds ? "1" : "0", descriptor.fingerprint, deviceNumber,
            descriptor.propertyBits, descriptor.keyBits, descriptor.absBits, descriptor.hasTouchButton ? "1" : "0",
            descriptor.sysfsTopology, descriptor.sysfsHash, descriptor.driver, descriptor.physHash, descriptor.uniqHash,
            std::to_string(descriptor.probeError), std::to_string(descriptor.topologyError),
            std::to_string(descriptor.slot.minimum), std::to_string(descriptor.slot.maximum),
            std::to_string(descriptor.tracking.minimum), std::to_string(descriptor.tracking.maximum),
            descriptor.duplicateOf, descriptor.capabilitiesKnown ? "1" : "0", descriptor.uncertain ? "1" : "0"};
        row = javaStrings(env, strings, values);
        if (!row) { env->DeleteLocalRef(strings); env->DeleteLocalRef(result); return nullptr; }
        env->SetObjectArrayElement(result, static_cast<jsize>(i + 1), row);
        env->DeleteLocalRef(row);
    }
    env->DeleteLocalRef(strings);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_luoxianlv_input_TouchEngine_nativePrepare(JNIEnv* env, jclass, jobject listener,
        jstring path, jstring fingerprint, jstring scanFingerprint, jint width, jint height, jint rotation) {
    if (!privilegedIdentity()) { fail(env, "触控内核需要 Shizuku 或无线调试的系统输入身份"); return 0; }
    std::string inputPath = javaString(env, path);
    const std::string expected = javaString(env, fingerprint);
    const std::string expectedScan = javaString(env, scanFingerprint);
    if (env->ExceptionCheck()) return 0;
    if (!exactEventPath(inputPath) || expected.empty() || expectedScan.empty() ||
        width < 2 || height < 2 || width > 32768 || height > 32768 || rotation < 0 || rotation > 3 || !listener) {
        fail(env, "触控预检参数无效"); return 0;
    }
    DeviceInventory inventory = inspectDevices(inputPath);
    if (!inventory.error.empty() || inventory.selection.selected == kNoDeviceIndex ||
        !sameTouchDeviceIdentity(expectedScan, inventory.fingerprint)) {
        fail(env, "输入设备列表发生变化，请重新预检"); return 0;
    }
    UniqueFd fd(open(inputPath.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC));
    Descriptor descriptor;
    if (fd.get() < 0 || !inspect(fd.get(), inputPath, descriptor) ||
        !sameTouchDeviceIdentity(expected, descriptor.fingerprint)) {
        fail(env, "触屏身份或能力发生变化，请重新预检"); return 0;
    }
    std::shared_ptr<Engine> engine;
    try {
        engine = std::make_shared<Engine>(env, listener, descriptor, fd.get(), width, height, rotation);
        fd.release();
        if (!engine->valid() || env->ExceptionCheck()) { fail(env, "无法初始化触控回调或事件队列"); return 0; }
        engine->start();
        jlong handle = nextHandle.fetch_add(1);
        { std::lock_guard lock(enginesMutex); engines.emplace(handle, engine); }
        return handle;
    } catch (const std::exception&) {
        if (engine) engine->stop();
        fail(env, "触控工作线程初始化失败");
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_luoxianlv_input_TouchEngine_nativeActivate(JNIEnv*, jclass, jlong handle) {
    auto engine = lookup(handle);
    return engine && engine->activate();
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_luoxianlv_input_TouchEngine_nativeFailure(JNIEnv* env, jclass, jlong handle) {
    auto engine = lookup(handle);
    std::string reason = engine ? engine->lastFailure() : "触控引擎尚未准备";
    return env->NewStringUTF(reason.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_luoxianlv_input_TouchEngine_nativeActivationWaiting(JNIEnv*, jclass, jlong handle) {
    auto engine = lookup(handle);
    return engine && engine->activationWaiting();
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_luoxianlv_input_TouchEngine_nativeContactState(JNIEnv* env, jclass, jlong handle) {
    auto engine = lookup(handle);
    const ContactState state = engine ? engine->contactState() : ContactState{};
    const std::array<std::string, 6> fields = {
        std::to_string(state.trackedSlots), state.touchSupported ? "1" : "0",
        std::to_string(state.touchPressed), std::to_string(state.readError),
        state.inactiveTrackedAllowed ? "1" : "0", contactDecisionName(state.decision)};
    jclass strings = env->FindClass("java/lang/String");
    if (!strings) return nullptr;
    jobjectArray result = env->NewObjectArray(fields.size(), strings, nullptr);
    env->DeleteLocalRef(strings);
    if (!result) return nullptr;
    for (size_t i = 0; i < fields.size(); ++i) {
        jstring value = env->NewStringUTF(fields[i].c_str());
        if (!value) return nullptr;
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_luoxianlv_input_TouchEngine_nativeBegin(JNIEnv* env, jclass, jlong handle,
                                               jfloatArray xy, jint duration, jlong token) {
    auto engine = lookup(handle);
    if (!engine || !xy) return JNI_FALSE;
    jsize count = env->GetArrayLength(xy);
    if (count < 2 || count > kMaxAutomaticPointers * 2 || count % 2 != 0) return JNI_FALSE;
    std::array<float, kMaxAutomaticPointers * 2> values{};
    env->GetFloatArrayRegion(xy, 0, count, values.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    return engine->begin(values.data(), count / 2, duration, token);
}

extern "C" JNIEXPORT void JNICALL
Java_app_luoxianlv_input_TouchEngine_nativeHeartbeat(JNIEnv*, jclass, jlong handle) {
    auto engine = lookup(handle);
    if (engine) engine->heartbeat();
}

extern "C" JNIEXPORT void JNICALL
Java_app_luoxianlv_input_TouchEngine_nativeCommand(JNIEnv*, jclass, jlong handle, jint command) {
    auto engine = lookup(handle);
    if (engine) engine->command(command);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_luoxianlv_input_TouchEngine_nativeIsActive(JNIEnv*, jclass, jlong handle) {
    auto engine = lookup(handle);
    return engine && engine->active();
}

extern "C" JNIEXPORT void JNICALL
Java_app_luoxianlv_input_TouchEngine_nativeClose(JNIEnv*, jclass, jlong handle) {
    std::shared_ptr<Engine> engine;
    {
        std::lock_guard lock(enginesMutex);
        auto found = engines.find(handle);
        if (found == engines.end()) return;
        engine = std::move(found->second);
        engines.erase(found);
    }
    engine->stop();
}
