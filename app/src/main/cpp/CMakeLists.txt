# Native build of the harnessDroid MicroPython VM (see micropython/ for the
# vendored v1.29.0 source and LICENSE.micropython).
#
# The 16 KB page-alignment linker flags are required for arm64 devices with
# 16 KB kernels (Android 15+ requirement for apps shipping native libs); the
# NDK in use (r26) does not pass them automatically - r27+ does.
cmake_minimum_required(VERSION 3.22.1)
project(harnessdroid_native LANGUAGES C)

set(MPY ${CMAKE_CURRENT_SOURCE_DIR}/micropython)

# Vendored MicroPython core (py/) and the extmod subset our config enables:
# VFS + VfsPosix sandbox filesystem, and the json/time/select/os modules the
# frozen asyncio package and the harness bridge speak to. Everything else
# stays out of the ROM.
file(GLOB MPY_PY_CORE ${MPY}/py/*.c)
set(MPY_EXTMOD
    ${MPY}/extmod/modasyncio.c
    ${MPY}/extmod/modjson.c
    ${MPY}/extmod/modos.c
    ${MPY}/extmod/modselect.c
    ${MPY}/extmod/modtime.c
    ${MPY}/extmod/vfs.c
    ${MPY}/extmod/vfs_posix.c
    ${MPY}/extmod/vfs_posix_file.c
    ${MPY}/extmod/vfs_reader.c
)

add_library(micropython SHARED
    mphalport_android.c
    embed_util_android.c
    harness_module.c
    micropython_jni.c
    micropython_frozen.c
    ${MPY_PY_CORE}
    ${MPY_EXTMOD}
    ${MPY}/shared/runtime/gchelper_generic.c
)

target_include_directories(micropython PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${MPY}
    ${MPY}/port
    ${MPY}/genhdr
)

# gnu11 (not c11): the vendored py core uses GNU `asm` register syntax.
# Hidden visibility: only the JNIEXPORT entry points stay exported.
target_compile_options(micropython PRIVATE
    -O2
    -std=gnu11
    -fvisibility=hidden
    -Wno-implicit-fallthrough
)

target_link_libraries(micropython PRIVATE m log)

target_link_options(micropython PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
