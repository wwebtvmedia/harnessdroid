#!/bin/bash
# Regenerates the vendored MicroPython package in app/src/main/cpp/.
#
# Run manually when bumping the vendored version - NOT from the Gradle build.
# Committers review the resulting diff of micropython/ and micropython_frozen.c
# so builds stay reproducible offline from what is committed.
#
# What it produces (all under app/src/main/cpp/):
#   micropython/           embed-port package (py/, port/, extmod/, genhdr/)
#                          minus the upstream stubs we replace (embed_util.c,
#                          mphalport.c, mphalport.h), plus the extmod files the
#                          embed package does not include (vfs*, modjson, ...)
#   micropython_frozen.c   frozen asyncio bytecode (mpy-cross + mpy-tool)
#
# Prereqs: gcc, make, python3, curl, xz.
set -e

VERSION="${MICROPYTHON_VERSION:-1.29.0}"
CPP_DIR="$(cd "$(dirname "$0")/../app/src/main/cpp" && pwd)"
WORK="${MICROPYTHON_WORKDIR:-/tmp/mpbuild}"
STAGE="$WORK/embproj"

mkdir -p "$WORK"
cd "$WORK"

echo "=== $0: MicroPython v$VERSION -> $CPP_DIR"

# 1. Source tarball ----------------------------------------------------------
TARBALL="micropython-$VERSION.tar.xz"
if [ ! -d "micropython-$VERSION" ]; then
    if [ ! -f "$TARBALL" ]; then
        echo "-- downloading $TARBALL"
        curl -fLO "https://github.com/micropython/micropython/releases/download/v$VERSION/$TARBALL"
    fi
    tar -xf "$TARBALL"
fi
TOP="$WORK/micropython-$VERSION"

# 2. mpy-cross (host bytecode compiler for the frozen .mpy files) -------------
if [ ! -x "$TOP/mpy-cross/build/mpy-cross" ]; then
    echo "-- building mpy-cross"
    make -C "$TOP/mpy-cross" -j"$(nproc)" >/dev/null
fi

# 3. Staging project: our port files + a Makefile that includes embed.mk.
#    SRC_QSTR drives the qstr pool: every C file that mentions MP_QSTR_*.
echo "-- staging embed project in $STAGE"
mkdir -p "$STAGE/extmod"
cp "$CPP_DIR/mpconfigport.h" \
   "$CPP_DIR/mphalport_android.h" "$CPP_DIR/mphalport_android.c" \
   "$CPP_DIR/embed_util_android.c" "$CPP_DIR/harness_module.c" \
   "$CPP_DIR/micropython_bootstrap.h" "$CPP_DIR/vm_shared.h" \
   "$CPP_DIR/micropython_jni.c" \
   "$STAGE/"
cat > "$STAGE/Makefile" <<EOF
MICROPYTHON_TOP = $TOP
SRC_QSTR = harness_module.c embed_util_android.c \\
	extmod/modos.c extmod/modjson.c extmod/modtime.c extmod/modselect.c \\
	extmod/modasyncio.c extmod/vfs.c extmod/vfs_posix.c extmod/vfs_posix_file.c \\
	extmod/vfs_reader.c
include \$(MICROPYTHON_TOP)/ports/embed/embed.mk
EOF
make -C "$STAGE" -j"$(nproc)" >/dev/null

# 4. Assemble the vendored package -------------------------------------------
echo "-- assembling $CPP_DIR/micropython/"
EMB="$STAGE/micropython_embed"
rm -rf "$CPP_DIR/micropython"
mkdir -p "$CPP_DIR/micropython/extmod" "$CPP_DIR/micropython/genhdr" "$CPP_DIR/micropython/shared/runtime"
cp -r "$EMB/py" "$CPP_DIR/micropython/py"
cp -r "$EMB/port" "$CPP_DIR/micropython/port"
# The embed package ships stub embed_util.c / mphalport.{c,h}; we replace them
# with our own (embed_util_android.c / mphalport_android.{c,h}) - drop the stubs.
rm -f "$CPP_DIR/micropython/port/embed_util.c" \
      "$CPP_DIR/micropython/port/mphalport.c" \
      "$CPP_DIR/micropython/port/mphalport.h"
# extmod files the embed package does not include but our config enables.
# (modvfs.c is the same file as vfs.c in v1.29; use vfs.c.)
for f in modasyncio.c modjson.c modos.c modselect.c modtime.c \
         vfs.c vfs.h vfs_posix.c vfs_posix.h vfs_posix_file.c vfs_reader.c \
         modplatform.h misc.h modtime.h vfs_rom.h virtpin.h; do
    cp "$TOP/extmod/$f" "$CPP_DIR/micropython/extmod/"
done
cp "$TOP/shared/runtime/gchelper.h" "$TOP/shared/runtime/gchelper_generic.c" \
   "$CPP_DIR/micropython/shared/runtime/"
cp "$EMB/genhdr/moduledefs.h" "$EMB/genhdr/mpversion.h" \
   "$EMB/genhdr/qstrdefs.generated.h" "$EMB/genhdr/root_pointers.h" \
   "$CPP_DIR/micropython/genhdr/"

# 5. Frozen asyncio -----------------------------------------------------------
echo "-- freezing asyncio"
rm -rf "$WORK/frz" && mkdir -p "$WORK/frz"
for f in "$TOP"/extmod/asyncio/*.py; do
    base="$(basename "$f" .py)"
    "$TOP/mpy-cross/build/mpy-cross" -O2 -s "asyncio/$base.py" "$f" -o "$WORK/frz/asyncio_${base}.py.mpy"
done
# -q feeds mpy-tool the raw qstr collection so the extra pool's qstr indices
# continue after the main pool's.
cp "$STAGE/build-embed/genhdr/qstrdefs.preprocessed.h" "$WORK/frz_qstrdefs.preprocessed.h"
MPYS=$(cd "$WORK/frz" && ls asyncio___init__.py.mpy asyncio_core.py.mpy asyncio_event.py.mpy \
    asyncio_funcs.py.mpy asyncio_lock.py.mpy asyncio_stream.py.mpy asyncio_task.py.mpy \
    asyncio_uasyncio.py.mpy | sed "s|^|$WORK/frz/|" | tr '\n' ' ')
python3 "$TOP/tools/mpy-tool.py" -f -mlongint-impl=mpz -mmpz-dig-size=16 \
    -q "$WORK/frz_qstrdefs.preprocessed.h" $MPYS \
    > "$CPP_DIR/micropython_frozen.c"

echo "-- done. Review the diff:"
echo "   git -C $(cd "$CPP_DIR/../.." && pwd) diff --stat -- app/src/main/cpp/micropython app/src/main/cpp/micropython_frozen.c"
