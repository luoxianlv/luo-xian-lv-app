#ifndef LXUPDATE_PATCH_ENGINE_H
#define LXUPDATE_PATCH_ENGINE_H
#include <stdint.h>
// 返回零表示精确格式解码完成；输出大小和摘要仍由可信宿主复核。
int lxupdate_merge(int base_fd, int patch_fd, int output_fd, int64_t target_size, int64_t timeout_ms);
void lxupdate_cancel(void);
int lxupdate_merge_job(int base_fd, int patch_fd, int output_fd, int64_t target_size,
        int64_t timeout_ms, int64_t job_token);
void lxupdate_cancel_job(int64_t job_token);
void lxupdate_finish_job(int64_t job_token);
#endif
