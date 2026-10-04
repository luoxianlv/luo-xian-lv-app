cmake_minimum_required(VERSION 3.22.1)
project(lxupdate_patch C)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(ZSTD_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/vendor/zstd/lib")
file(GLOB ZSTD_COMMON "${ZSTD_ROOT}/common/*.c")
file(GLOB ZSTD_DECODE "${ZSTD_ROOT}/decompress/*.c")
add_library(lxupdate_decoder STATIC patch_engine.c vendor/hdiffpatch/libHDiffPatch/HPatch/patch.c
    ${ZSTD_COMMON} ${ZSTD_DECODE})
target_include_directories(lxupdate_decoder PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}" PRIVATE
    vendor/hdiffpatch/libHDiffPatch/HPatch "${ZSTD_ROOT}" "${ZSTD_ROOT}/common")
target_compile_definitions(lxupdate_decoder PRIVATE _IS_USED_MULTITHREAD=0 _HPATCH_IS_USED_MULTITHREAD=0
    ZSTD_MULTITHREAD=0 ZSTD_DISABLE_ASM=1 ZSTD_LEGACY_SUPPORT=0 ZSTD_HEAPMODE=1
    _FILE_OFFSET_BITS=64 _POSIX_C_SOURCE=200809L)
target_compile_options(lxupdate_decoder PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
if(ANDROID)
    add_library(lxupdate_patch SHARED patch_jni.c)
    target_link_libraries(lxupdate_patch PRIVATE lxupdate_decoder log)
    target_link_options(lxupdate_patch PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
else()
    add_executable(lxupdate_decode "${CMAKE_CURRENT_SOURCE_DIR}/../../test/native/decode_main.c")
    target_link_libraries(lxupdate_decode PRIVATE lxupdate_decoder)
endif()
