#!/bin/bash
# Host-side smoke test for the harnessDroid MicroPython VM.
#
# Compiles the same C sources as the Android build (the JNI layer is replaced
# by the stubs at the top of test_vm.c) and runs the bootstrap plus a battery
# of plans: compute, JSON, sandbox VFS, the '..' escape guard, tool round trip,
# deadline kill, traceback quality, globals persistence and the asyncio
# scheduler pumped from C the way PythonEngine pumps it on-device.
#
# Usage: tools/host_vm_smoke/build_and_run.sh   (needs gcc and make-free; the
# MicroPython sources are the vendored copy under app/src/main/cpp/micropython)
set -e
HD="$(cd "$(dirname "$0")/../.." && pwd)/app/src/main/cpp"
MP=$HD/micropython
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

gcc -std=gnu11 -O1 -g -D_GNU_SOURCE -Wno-error \
    -I"$HERE" -I"$HD" -I"$MP" -I"$MP/port" -I"$MP/genhdr" \
    -o "$HERE/test_vm" test_vm.c \
    $HD/mphalport_android.c $HD/embed_util_android.c $HD/harness_module.c \
    $HD/micropython_frozen.c \
    $MP/py/*.c \
    $MP/extmod/modasyncio.c $MP/extmod/modjson.c $MP/extmod/modos.c \
    $MP/extmod/modselect.c $MP/extmod/modtime.c \
    $MP/extmod/vfs.c $MP/extmod/vfs_posix.c $MP/extmod/vfs_posix_file.c \
    $MP/extmod/vfs_reader.c \
    $MP/shared/runtime/gchelper_generic.c -lm

rm -rf /tmp/mpsandbox && mkdir -p /tmp/mpsandbox
"$HERE/test_vm"
