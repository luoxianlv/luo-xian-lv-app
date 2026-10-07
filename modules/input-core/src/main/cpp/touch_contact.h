#pragma once

namespace luoxianlv::input {

enum class ContactDecision { NotRead, Clear, InactiveTracking, Contact, Changing, ReadError };

inline const char* contactDecisionName(ContactDecision value) {
    switch (value) {
        case ContactDecision::Clear: return "clear";
        case ContactDecision::InactiveTracking: return "inactive-tracking";
        case ContactDecision::Contact: return "contact";
        case ContactDecision::Changing: return "changing";
        case ContactDecision::ReadError: return "read-error";
        default: return "not-read";
    }
}

// tracking 表示槽仍被跟踪；支持 BTN_TOUCH 的设备还可能上报未接触屏幕的悬停槽。
struct ContactState {
    int trackedSlots = -1;
    bool touchSupported = false;
    int touchPressed = -1;
    int readError = 0;
    bool inactiveTrackedAllowed = false;
    ContactDecision decision = ContactDecision::NotRead;

    bool released() const {
        return readError == 0 &&
                (decision == ContactDecision::Clear || decision == ContactDecision::InactiveTracking);
    }

    // 有槽位快照的 Type B 可接续已经按下的手指；半帧、读取失败和空槽按下不作猜测。
    bool adoptable(bool hasSlots) const {
        return released() || (hasSlots && decision == ContactDecision::Contact &&
                readError == 0 && trackedSlots > 0);
    }
};

inline ContactState evaluateContact(int trackedSlots, bool touchSupported,
                                    int keyBefore, int keyAfter, int readError = 0) {
    ContactState state{trackedSlots, touchSupported, keyAfter, readError};
    if (readError != 0 || trackedSlots < 0 ||
            (touchSupported && (keyBefore < 0 || keyBefore > 1 || keyAfter < 0 || keyAfter > 1))) {
        state.decision = ContactDecision::ReadError;
    } else if (!touchSupported) {
        state.decision = trackedSlots == 0 ? ContactDecision::Clear : ContactDecision::Contact;
    } else if (keyBefore != keyAfter) {
        state.decision = ContactDecision::Changing;
    } else if (keyAfter != 0) {
        state.decision = ContactDecision::Contact;
    } else if (trackedSlots > 0) {
        state.inactiveTrackedAllowed = true;
        state.decision = ContactDecision::InactiveTracking;
    } else {
        state.decision = ContactDecision::Clear;
    }
    return state;
}

}  // namespace luoxianlv::input
