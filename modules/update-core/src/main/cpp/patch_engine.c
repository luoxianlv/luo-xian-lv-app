#include "patch_engine.h"
#include "patch.h"
#include "checksum_plugin.h"
#define ZSTD_STATIC_LINKING_ONLY
#include "zstd.h"
#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static atomic_bool cancelled;
static _Atomic int64_t cancelled_job;
static atomic_bool decode_failed;
static size_t budget_used;
typedef union { max_align_t alignment; size_t size; } AllocationHeader;
static void* budget_alloc(void* ignored, size_t size) {
    (void)ignored;
    const size_t limit = 32 * 1024 * 1024;
    if (size > limit - sizeof(AllocationHeader) || budget_used > limit - size - sizeof(AllocationHeader)) return NULL;
    AllocationHeader* block = malloc(sizeof(AllocationHeader) + size);
    if (!block) return NULL;
    block->size = size + sizeof(AllocationHeader); budget_used += block->size;
    return block + 1;
}
static void budget_free(void* ignored, void* address) {
    (void)ignored;
    if (!address) return;
    AllocationHeader* block = (AllocationHeader*)address - 1;
    budget_used -= block->size; free(block);
}
typedef struct { int fd; int64_t deadline_ms, job_token; } FdInput;
typedef struct { int fd; int64_t deadline_ms; uint64_t size; int64_t job_token; } FdOutput;
static int64_t now_ms(void) {
    struct timespec value;
    clock_gettime(CLOCK_MONOTONIC, &value);
    return value.tv_sec * (int64_t)1000 + value.tv_nsec / 1000000;
}
static int stopped(int64_t deadline, int64_t token) {
    return (token ? atomic_load(&cancelled_job) == token : atomic_load(&cancelled)) || now_ms() >= deadline;
}
void lxupdate_cancel(void) { atomic_store(&cancelled, 1); }
void lxupdate_cancel_job(int64_t token) { if (token) atomic_store(&cancelled_job, token); }
void lxupdate_finish_job(int64_t token) {
    if (token) atomic_compare_exchange_strong(&cancelled_job, &token, 0);
}
static hpatch_BOOL fd_read(const hpatch_TStreamInput* stream, hpatch_StreamPos_t pos,
        unsigned char* begin, unsigned char* end) {
    FdInput* file = stream->streamImport;
    if (pos > stream->streamSize || (uint64_t)(end - begin) > stream->streamSize - pos) return hpatch_FALSE;
    while (begin < end) {
        if (stopped(file->deadline_ms, file->job_token)) return hpatch_FALSE;
        ssize_t count = pread(file->fd, begin, (size_t)(end - begin), (off_t)pos);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return hpatch_FALSE;
        begin += count; pos += count;
    }
    return hpatch_TRUE;
}
static hpatch_BOOL fd_write(const hpatch_TStreamOutput* stream, hpatch_StreamPos_t pos,
        const unsigned char* begin, const unsigned char* end) {
    FdOutput* file = stream->streamImport;
    if (pos > file->size || (uint64_t)(end - begin) > file->size - pos) return hpatch_FALSE;
    while (begin < end) {
        if (stopped(file->deadline_ms, file->job_token)) return hpatch_FALSE;
        ssize_t count = pwrite(file->fd, begin, (size_t)(end - begin), (off_t)pos);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return hpatch_FALSE;
        begin += count; pos += count;
    }
    return hpatch_TRUE;
}

typedef struct {
    const hpatch_TStreamInput* source;
    hpatch_StreamPos_t position, end;
    ZSTD_DStream* decoder;
    ZSTD_inBuffer input;
    uint64_t remaining;
    size_t last_result;
    unsigned char buffer[65536];
} ZstdReader;
static hpatch_BOOL zstd_type(const char* type) { return strcmp(type, "zstd") == 0; }
static hpatch_decompressHandle zstd_open(hpatch_TDecompress* plugin, hpatch_StreamPos_t size,
        const hpatch_TStreamInput* source, hpatch_StreamPos_t begin, hpatch_StreamPos_t end) {
    (void)plugin;
    if (begin >= end) return NULL;
    ZstdReader* reader = budget_alloc(NULL, sizeof(ZstdReader));
    if (!reader) return NULL;
    memset(reader, 0, sizeof(ZstdReader));
    reader->source = source; reader->position = begin; reader->end = end; reader->remaining = size;
    ZSTD_customMem memory = { budget_alloc, budget_free, NULL };
    reader->decoder = ZSTD_createDStream_advanced(memory); reader->input.src = reader->buffer;
    if (!reader->decoder || ZSTD_isError(ZSTD_initDStream(reader->decoder))
            || ZSTD_isError(ZSTD_DCtx_setParameter(reader->decoder, ZSTD_d_windowLogMax, 23))) {
        ZSTD_freeDStream(reader->decoder); budget_free(NULL, reader); return NULL;
    }
    reader->last_result = 1;
    return reader;
}
static int zstd_input(ZstdReader* reader) {
    if (reader->input.pos != reader->input.size) return 1;
    size_t count = sizeof(reader->buffer);
    if (count > reader->end - reader->position) count = reader->end - reader->position;
    reader->input.size = count; reader->input.pos = 0;
    if (count && !reader->source->read(reader->source, reader->position, reader->buffer, reader->buffer + count)) return 0;
    reader->position += count;
    return 1;
}
static hpatch_BOOL zstd_part(hpatch_decompressHandle handle, unsigned char* begin, unsigned char* end) {
    ZstdReader* reader = handle;
    uint64_t requested = end - begin;
    if (requested > reader->remaining) return hpatch_FALSE;
    ZSTD_outBuffer output = { begin, (size_t)requested, 0 };
    while (output.pos < output.size) {
        if (!zstd_input(reader)) return hpatch_FALSE;
        size_t old_input = reader->input.pos, old_output = output.pos;
        reader->last_result = ZSTD_decompressStream(reader->decoder, &output, &reader->input);
        if (ZSTD_isError(reader->last_result) || (old_input == reader->input.pos && old_output == output.pos)) return hpatch_FALSE;
        // 协议只允许一个完整 Zstd frame，拒绝多 frame 拼接及尾随数据。
        if (reader->last_result == 0 && (output.pos != output.size || requested != reader->remaining)) return hpatch_FALSE;
    }
    reader->remaining -= requested;
    return hpatch_TRUE;
}
static hpatch_BOOL zstd_close(hpatch_TDecompress* plugin, hpatch_decompressHandle handle) {
    (void)plugin;
    ZstdReader* reader = handle;
    if (!reader) return hpatch_TRUE;
    int success = reader->remaining == 0;
    // 最后一段输出填满时可能仍需消费 frame checksum；零长度输出可推进尾部。
    while (success && reader->last_result != 0) {
        if (!zstd_input(reader)) { success = 0; break; }
        unsigned char extra;
        ZSTD_outBuffer output = { &extra, 1, 0 };
        size_t old_input = reader->input.pos;
        reader->last_result = ZSTD_decompressStream(reader->decoder, &output, &reader->input);
        if (ZSTD_isError(reader->last_result) || output.pos != 0 || old_input == reader->input.pos) success = 0;
    }
    success = success && reader->position == reader->end && reader->input.pos == reader->input.size;
    ZSTD_freeDStream(reader->decoder); budget_free(NULL, reader);
    if (!success) atomic_store(&decode_failed, 1);
    return success ? hpatch_TRUE : hpatch_FALSE;
}
static hpatch_TDecompress zstd_plugin = { zstd_type, zstd_open, zstd_close, zstd_part };
static hpatch_BOOL diff_info(winpatch_listener_t* listener, const hpatch_windowDiffInfo* info,
        hpatch_TDecompress** decompressor, hpatch_TChecksum** checksum, hpatch_BOOL* check_new,
        hpatch_BOOL* check_old, hpatch_BOOL* check_diff, unsigned char** cache, unsigned char** cache_end) {
    (void)listener; (void)checksum;
    if (info->maxWindowOldSize > 2 * 1024 * 1024 || info->maxStepMemSize > 256 * 1024
            || info->checksumByteSize != 0 || info->extraDataSize != 0 || !zstd_type(info->compressType)
            || info->maxSubCoverCount > 256 * 1024) return hpatch_FALSE;
    size_t bytes = (size_t)(info->maxWindowOldSize + info->maxStepMemSize + hpatch_kStreamCacheSize * 4);
    *cache = budget_alloc(NULL, bytes); if (!*cache) return hpatch_FALSE;
    *cache_end = *cache + bytes; *decompressor = info->compressedSize ? &zstd_plugin : NULL;
    *check_new = *check_old = *check_diff = hpatch_FALSE;
    return hpatch_TRUE;
}
static void diff_finish(winpatch_listener_t* listener, unsigned char* begin, unsigned char* end) {
    (void)listener; (void)end; budget_free(NULL, begin);
}
static int merge(int base_fd, int patch_fd, int output_fd, int64_t target_size,
        int64_t timeout_ms, int64_t job_token) {
    if (target_size <= 0 || target_size > 2LL * 1024 * 1024 * 1024 || timeout_ms < 1000 || timeout_ms > 180000) return -1;
    struct stat base, patch, target;
    if (fstat(base_fd, &base) || fstat(patch_fd, &patch) || fstat(output_fd, &target)
            || !S_ISREG(base.st_mode) || !S_ISREG(patch.st_mode) || !S_ISREG(target.st_mode)
            || base.st_size <= 0 || patch.st_size <= 0 || base.st_size > 2LL * 1024 * 1024 * 1024
            || patch.st_size > 2LL * 1024 * 1024 * 1024) return -2;
    if ((base.st_dev == target.st_dev && base.st_ino == target.st_ino)
            || (patch.st_dev == target.st_dev && patch.st_ino == target.st_ino)) return -3;
    if ((fcntl(base_fd, F_GETFL) & O_ACCMODE) != O_RDONLY || (fcntl(patch_fd, F_GETFL) & O_ACCMODE) != O_RDONLY
            || (fcntl(output_fd, F_GETFL) & O_ACCMODE) == O_RDONLY) return -4;
    atomic_store(&decode_failed, 0);
    budget_used = 0;
    int64_t deadline = now_ms() + timeout_ms;
    FdInput base_file = { base_fd, deadline, job_token }, patch_file = { patch_fd, deadline, job_token };
    FdOutput target_file = { output_fd, deadline, target_size, job_token };
    hpatch_TStreamInput old_stream = { &base_file, base.st_size, fd_read };
    hpatch_TStreamInput patch_stream = { &patch_file, patch.st_size, fd_read };
    hpatch_TStreamOutput target_stream = { &target_file, target_size, NULL, fd_write };
    hpatch_windowDiffInfo info;
    if (!getWindowDiffInfo(&info, &patch_stream, 0) || info.newDataSize != (uint64_t)target_size
            || info.oldDataSize != (uint64_t)base.st_size || info.windowDataPos > (uint64_t)patch.st_size
            || (info.compressedSize ? info.compressedSize : info.uncompressedSize) != (uint64_t)patch.st_size - info.windowDataPos) return -5;
    if (ftruncate(output_fd, 0)) return -6;
    winpatch_listener_t listener = { NULL, diff_info, diff_finish };
    TWindowPatchResult result = patch_window_diff(&listener, &target_stream, &old_stream, &patch_stream, 0, 1);
    if (result != kWindowPatch_ok) return 100 + result;
    if (atomic_load(&decode_failed)) return -8;
    if (stopped(deadline, job_token) || fstat(output_fd, &target) || target.st_size != target_size || fsync(output_fd)) return -7;
    return 0;
}
int lxupdate_merge(int base_fd, int patch_fd, int output_fd, int64_t target_size, int64_t timeout_ms) {
    atomic_store(&cancelled, 0);
    return merge(base_fd, patch_fd, output_fd, target_size, timeout_ms, 0);
}
int lxupdate_merge_job(int base_fd, int patch_fd, int output_fd, int64_t target_size,
        int64_t timeout_ms, int64_t token) {
    if (!token) return -1;
    // reserve 后到达的取消必须保留，不能在进入 native 时重置。
    int result = merge(base_fd, patch_fd, output_fd, target_size, timeout_ms, token);
    lxupdate_finish_job(token);
    return result;
}
