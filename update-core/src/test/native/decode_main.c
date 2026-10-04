#include "patch_engine.h"
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
int main(int argc, char** argv) {
    if (argc != 5) { fprintf(stderr, "usage: lxupdate_decode OLD PATCH OUT TARGET_SIZE\n"); return 2; }
    int old = open(argv[1], O_RDONLY), patch = open(argv[2], O_RDONLY), out = open(argv[3], O_CREAT | O_RDWR, 0600);
    if (old < 0 || patch < 0 || out < 0) return 3;
    int result = lxupdate_merge(old, patch, out, strtoll(argv[4], NULL, 10), 180000);
    close(old); close(patch); close(out);
    if (result) fprintf(stderr, "decoder error: %d\n", result);
    return result ? 1 : 0;
}
