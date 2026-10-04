#include "patch_engine.h"
#include <jni.h>
JNIEXPORT jint JNICALL Java_app_luoxianlv_update_NativePatch_merge(JNIEnv* env, jclass type,
        jint base, jint patch, jint output, jlong size, jlong timeout) {
    (void)env; (void)type;
    return lxupdate_merge(base, patch, output, size, timeout);
}
JNIEXPORT void JNICALL Java_app_luoxianlv_update_NativePatch_cancel(JNIEnv* env, jclass type) {
    (void)env; (void)type; lxupdate_cancel();
}
