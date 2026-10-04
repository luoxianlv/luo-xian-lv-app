#include "patch_engine.h"
#include <jni.h>
JNIEXPORT jint JNICALL Java_app_luoxianlv_update_NativePatch_merge(JNIEnv* env, jclass type,
        jint base, jint patch, jint output, jlong size, jlong timeout, jlong token) {
    (void)env; (void)type;
    return lxupdate_merge_job(base, patch, output, size, timeout, token);
}
JNIEXPORT void JNICALL Java_app_luoxianlv_update_NativePatch_cancel(JNIEnv* env, jclass type, jlong token) {
    (void)env; (void)type; lxupdate_cancel_job(token);
}
JNIEXPORT void JNICALL Java_app_luoxianlv_update_NativePatch_finish(JNIEnv* env, jclass type, jlong token) {
    (void)env; (void)type; lxupdate_finish_job(token);
}
