#include "patch_engine.h"
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
static int check_token_cancellation(int old, int patch, int out, int64_t size) {
    if (ftruncate(out, 0)) return -1;
    lxupdate_cancel_job(101);
    if (lxupdate_merge_job(old, patch, out, size, 180000, 101) == 0) return -2;
    if (lxupdate_merge_job(old, patch, out, size, 180000, 102)) return -3;
    // 旧 token 的迟到取消不影响新任务；清理也只能清理自己的 token。
    lxupdate_cancel_job(101);
    if (lxupdate_merge_job(old, patch, out, size, 180000, 103)) return -4;
    lxupdate_cancel_job(104);
    lxupdate_finish_job(101);
    if (lxupdate_merge_job(old, patch, out, size, 180000, 104) == 0) return -5;
    if (lxupdate_merge_job(old, patch, out, size, 180000, 105)) return -6;
    return 0;
}
int main(int argc, char** argv) {
    if (argc != 5 && !(argc == 6 && strcmp(argv[5], "--token-cancellation") == 0)) {
        fprintf(stderr, "usage: lxupdate_decode OLD PATCH OUT TARGET_SIZE [--token-cancellation]\n"); return 2;
    }
    int old = open(argv[1], O_RDONLY), patch = open(argv[2], O_RDONLY), out = open(argv[3], O_CREAT | O_RDWR, 0600);
    if (old < 0 || patch < 0 || out < 0) return 3;
    int64_t size = strtoll(argv[4], NULL, 10);
    int result = argc == 6 ? check_token_cancellation(old, patch, out, size)
                          : lxupdate_merge(old, patch, out, size, 180000);
    close(old); close(patch); close(out);
    if (result) fprintf(stderr, "decoder error: %d\n", result);
    return result ? 1 : 0;
}
