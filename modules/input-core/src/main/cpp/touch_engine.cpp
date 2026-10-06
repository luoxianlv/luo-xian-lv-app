#include "touch_model.h"
#include "touch_contact.h"

#include <android/log.h>
#include <jni.h>
#include <linux/input.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <poll.h>

#include <atomic>
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
    input_absinfo slot{}, x{}, y{};
    bool hasTouchButton = false;
    int count() const { return slot.maximum + 1; }
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

bool inspect(int fd, const std::string& path, Descriptor& out) {
    constexpr size_t wordBits = sizeof(unsigned long) * 8;
    std::array<unsigned long, (INPUT_PROP_MAX / wordBits) + 1> properties{};
    std::array<unsigned long, (KEY_MAX / wordBits) + 1> keys{};
    char name[256]{};
    if (ioctl(fd, EVIOCGPROP(sizeof(properties)), properties.data()) < 0 ||
        (properties[INPUT_PROP_DIRECT / wordBits] & (1UL << (INPUT_PROP_DIRECT % wordBits))) == 0 ||
        ioctl(fd, EVIOCGNAME(sizeof(name)), name) < 0 ||
        ioctl(fd, EVIOCGID, &out.identity) < 0 ||
        ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &out.slot) < 0 ||
        ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &out.x) < 0 ||
        ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &out.y) < 0) return false;
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys.data()) <= BTN_TOUCH / 8) return false;
    out.hasTouchButton = (keys[BTN_TOUCH / wordBits] & (1UL << (BTN_TOUCH % wordBits))) != 0;
    input_absinfo tracking{};
    if (ioctl(fd, EVIOCGABS(ABS_MT_TRACKING_ID), &tracking) < 0 || out.slot.minimum != 0 ||
        out.slot.maximum < 0 || out.slot.maximum >= kMaxPointers - 1 ||
        out.x.minimum >= out.x.maximum || out.y.minimum >= out.y.maximum ||
        static_cast<int64_t>(out.x.maximum) - out.x.minimum > INT32_MAX ||
        static_cast<int64_t>(out.y.maximum) - out.y.minimum > INT32_MAX) return false;
    struct stat status{};
    if (fstat(fd, &status) < 0 || !S_ISCHR(status.st_mode)) return false;
    name[sizeof(name) - 1] = '\0';
    out.path = path;
    out.name = name;
    return true;
}

bool findDevice(const std::string& preferred, Descriptor& result, std::string& error) {
    if (getuid() != 2000) { error = "触控共存需要通过 Shizuku 或无线调试启动 shell 助手"; return false; }
    DIR* directory = opendir("/dev/input");
    if (!directory) { error = "系统不允许读取触屏设备目录"; return false; }
    int matches = 0;
    bool permissionDenied = false;
    while (dirent* entry = readdir(directory)) {
        std::string name = entry->d_name;
        if (name.rfind("event", 0) != 0 || name.size() > 32) continue;
        std::string path = "/dev/input/" + name;
        if (!preferred.empty() && preferred.front() == '/' && preferred != path) continue;
        UniqueFd device(open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC));
        if (device.get() < 0) { permissionDenied |= errno == EACCES || errno == EPERM; continue; }
        Descriptor descriptor;
        if (!inspect(device.get(), path, descriptor)) continue;
        if (!preferred.empty() && preferred.front() != '/' && preferred != descriptor.name) continue;
        result = descriptor;
        ++matches;
    }
    closedir(directory);
    if (matches == 1) return true;
    error = matches > 1 ? "发现多个兼容触屏，无法唯一确认输入设备" :
            permissionDenied ? "当前系统不允许 shell 读取兼容触屏" : "没有找到兼容的物理多点触屏";
    return false;
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
                 descriptor.y.minimum, descriptor.y.maximum, width, height, rotation, descriptor.hasTouchButton) {
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
        if (!fingersReleased())
            return rejectActivation(errno == 0 ? "请先抬起所有手指后开始演奏" :
                    "无法读取当前触点状态（errno=" + std::to_string(errno) + "）", errno == 0);
        // 丢弃预检期间的旧事件，抓取后再次检查，避免把已按下的手指当成新触点。
        std::array<input_event, 64> stale{};
        int drained = 0;
        while (::read(inputFd_, stale.data(), sizeof(stale)) > 0)
            if (++drained == 16) return rejectActivation("触屏事件仍在变化，请抬起手指后重试", true);
        if (!fingersReleased())
            return rejectActivation(errno == 0 ? "请先抬起所有手指后开始演奏" :
                    "无法读取当前触点状态（errno=" + std::to_string(errno) + "）", errno == 0);
        if (ioctl(inputFd_, EVIOCGRAB, 1) < 0)
            return rejectActivation("系统拒绝接管触屏（errno=" + std::to_string(errno) + "）");
        if (!fingersReleased()) {
            const int failureErrno = errno;
            ioctl(inputFd_, EVIOCGRAB, 0);
            return rejectActivation(failureErrno == 0 ? "接管时检测到手指按下，请抬起后重试" :
                    "接管后无法读取触点（errno=" + std::to_string(failureErrno) + "）", failureErrno == 0);
        }
        input_absinfo selected{};
        selected.value = INT32_MIN;
        initialX_.fill(INT32_MIN);
        initialY_.fill(INT32_MIN);
        initialX_[0] = ABS_MT_POSITION_X;
        initialY_[0] = ABS_MT_POSITION_Y;
        const size_t snapshotBytes = sizeof(int32_t) * (descriptor_.count() + 1);
        if (ioctl(inputFd_, EVIOCGABS(ABS_MT_SLOT), &selected) < 0 ||
            ioctl(inputFd_, EVIOCGMTSLOTS(snapshotBytes), initialX_.data()) < 0 ||
            ioctl(inputFd_, EVIOCGMTSLOTS(snapshotBytes), initialY_.data()) < 0) {
            const int failureErrno = errno;
            ioctl(inputFd_, EVIOCGRAB, 0);
            return rejectActivation("无法读取触屏坐标快照（errno=" + std::to_string(failureErrno) + "）");
        }
        if (selected.value < 0 || selected.value >= descriptor_.count()) {
            ioctl(inputFd_, EVIOCGRAB, 0);
            return rejectActivation("触屏当前槽位无效，已归还触屏");
        }
        for (int i = 1; i <= descriptor_.count(); ++i) {
            if (initialX_[i] == INT32_MIN || initialY_[i] == INT32_MIN) {
                ioctl(inputFd_, EVIOCGRAB, 0);
                return rejectActivation("触屏坐标快照不完整，已归还触屏");
            }
        }
        if (!fingersReleased(&initialTracking_)) {
            const int failureErrno = errno;
            ioctl(inputFd_, EVIOCGRAB, 0);
            return rejectActivation(failureErrno == 0 ? "触屏状态变化，请抬起手指后重试" :
                    "无法读取当前触点状态（errno=" + std::to_string(failureErrno) + "）", failureErrno == 0);
        }
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
    std::array<int, kMaxPointers> previousTracking_{};
    std::array<int32_t, kMaxPointers + 1> initialX_{}, initialY_{}, initialTracking_{};
    int initialSlot_ = 0;
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

    bool fingersReleased(std::array<int32_t, kMaxPointers + 1>* tracking = nullptr) {
        const ContactState observed = readContact(inputFd_, descriptor_, tracking);
        { std::lock_guard lock(errorMutex_); contactState_ = observed; }
        errno = observed.readError;
        return observed.released();
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
            if (!model_.touch(event.value)) {
                emergency("触屏上报了异常接触状态，已归还触屏");
                return false;
            }
        } else if (event.type == EV_ABS) {
            bool valid = true;
            switch (event.code) {
                case ABS_MT_SLOT: valid = model_.select(event.value); break;
                case ABS_MT_TRACKING_ID: valid = model_.tracking(event.value); break;
                case ABS_MT_POSITION_X: valid = model_.x(event.value); break;
                case ABS_MT_POSITION_Y: valid = model_.y(event.value); break;
                default: break;
            }
            if (!valid) { emergency("触屏上报了异常触点，已归还触屏"); return false; }
        } else if (event.type == EV_SYN && event.code == SYN_REPORT) {
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
            for (int i = 0; i < descriptor_.count(); ++i) {
                model_.select(i);
                model_.tracking(initialTracking_[i + 1]);
                // 空槽可能尚未初始化坐标；按下后的实际事件仍严格验证范围。
                model_.x(std::clamp(initialX_[i + 1], descriptor_.x.minimum, descriptor_.x.maximum));
                model_.y(std::clamp(initialY_[i + 1], descriptor_.y.minimum, descriptor_.y.maximum));
            }
            model_.select(initialSlot_);
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
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* machine, void*) {
    vm = machine;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_luoxianlv_input_TouchEngine_nativeProbe(JNIEnv* env, jclass, jstring preferred) {
    Descriptor descriptor;
    std::string error;
    std::string preference = javaString(env, preferred);
    if (env->ExceptionCheck()) return nullptr;
    findDevice(preference, descriptor, error);
    std::array<std::string, 12> values = {descriptor.path, descriptor.name, std::to_string(descriptor.count()),
        std::to_string(descriptor.x.minimum), std::to_string(descriptor.x.maximum),
        std::to_string(descriptor.y.minimum), std::to_string(descriptor.y.maximum),
        std::to_string(descriptor.identity.vendor), std::to_string(descriptor.identity.product),
        std::to_string(descriptor.identity.version), std::to_string(descriptor.identity.bustype), error};
    jclass strings = env->FindClass("java/lang/String");
    if (!strings || env->ExceptionCheck()) return nullptr;
    jobjectArray result = env->NewObjectArray(values.size(), strings, nullptr);
    env->DeleteLocalRef(strings);
    if (!result) return nullptr;
    for (size_t i = 0; i < values.size(); ++i) {
        jstring text = env->NewStringUTF(values[i].c_str());
        if (!text) return result;
        env->SetObjectArrayElement(result, i, text);
        env->DeleteLocalRef(text);
    }
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_luoxianlv_input_TouchEngine_nativePrepare(JNIEnv* env, jclass, jobject listener,
        jstring path, jstring name, jint vendor, jint product, jint width, jint height, jint rotation) {
    if (getuid() != 2000) { fail(env, "触控内核只接受 shell 助手身份"); return 0; }
    std::string inputPath = javaString(env, path);
    if (inputPath.rfind("/dev/input/event", 0) != 0 || inputPath.find("..") != std::string::npos ||
        width < 2 || height < 2 || width > 32768 || height > 32768 || rotation < 0 || rotation > 3 || !listener) {
        fail(env, "触控预检参数无效"); return 0;
    }
    UniqueFd fd(open(inputPath.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC));
    Descriptor descriptor;
    if (fd.get() < 0 || !inspect(fd.get(), inputPath, descriptor) ||
        descriptor.name != javaString(env, name) || descriptor.identity.vendor != vendor ||
        descriptor.identity.product != product) { fail(env, "触屏身份发生变化，请重新预检"); return 0; }
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
