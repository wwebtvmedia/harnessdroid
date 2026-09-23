/*
 * MicroPython configuration for the harnessDroid embedded VM.
 *
 * The VM is a sandboxed plan executor: plans arrive as Python *source text*
 * produced by the LLM (local com.tree4five.gguf or a remote OpenAI-compatible
 * endpoint), run inside a VfsPosix chroot at the app's filesDir/micropython
 * directory, and may schedule cooperative asyncio services that outlive a
 * single plan. No networking, no threads, no subprocess: the interpreter is
 * deliberately minimal to keep libmicropython.so small.
 *
 * Part of the MicroPython embed port (MIT), see LICENSE.micropython.
 */
#ifndef MICROPY_INCLUDED_HARNESSDROID_MPCONFIGPORT_H
#define MICROPY_INCLUDED_HARNESSDROID_MPCONFIGPORT_H

// Common embed-port definitions (mp_off_t, alloca, mphal header name).
#include <port/mpconfigport_common.h>

// We ship our own mphalport (stdout capture, deadline-checked delays), not the
// stub one from the embed port.
#undef MICROPY_MPHALPORT_H
#define MICROPY_MPHALPORT_H "mphalport_android.h"

// py/mpconfig.h includes <limits.h> before this file, so SSIZE_MAX (which
// glibc hides behind feature macros) is never visible there; and it is used
// in a #if, so it must be a plain preprocessor literal. These match SSIZE_MAX
// on every ABI we build.
#ifndef MP_SSIZE_MAX
#if defined(__LP64__)
#define MP_SSIZE_MAX (0x7fffffffffffffffLL)
#else
#define MP_SSIZE_MAX (0x7fffffffL)
#endif
#endif

// ---- ROM budget: start from the absolute minimum, re-enable surgically -----

#define MICROPY_CONFIG_ROM_LEVEL            (MICROPY_CONFIG_ROM_LEVEL_MINIMUM)

// ---- Core -------------------------------------------------------------------

// Plans arrive as source text, so the compiler must stay in.
#define MICROPY_ENABLE_COMPILER             (1)
#define MICROPY_ENABLE_GC                   (1)
// vfs_posix requires finalisers.
#define MICROPY_ENABLE_FINALISER            (1)
// asyncio is the VM's internal scheduler: plans schedule services with
// async def/await, which ROM MINIMUM compiles out.
#define MICROPY_PY_ASYNC_AWAIT              (1)
// Without it the importer reduces to builtins-only (sys.path/.frozen are never
// scanned), so the frozen asyncio package would be unreachable.
#define MICROPY_ENABLE_EXTERNAL_IMPORT      (1)// Line numbers + normal error reporting feed the tracebacks we hand back to
// the LLM so it can fix its own plans in a follow-up turn.
#define MICROPY_ENABLE_SOURCE_LINE          (1)
#define MICROPY_ERROR_REPORTING             (MICROPY_ERROR_REPORTING_NORMAL)
// Deep recursion in a plan must raise, not segfault.
#define MICROPY_STACK_CHECK                 (1)
#define MICROPY_MEM_STATS                   (0)
#define MICROPY_PY_THREAD                   (0)
// ROM MINIMUM would give MICROPY_LONGINT_IMPL_NONE (ints overflow silently) -
// unacceptable for LLM plans that compute; mpz costs a few KB.
#define MICROPY_LONGINT_IMPL                (MICROPY_LONGINT_IMPL_MPZ)
// Match the frozen .mpy files (mpy-cross default dig size); mpz.h would pick
// 32 on x86_64 hosts and 16 on aarch64 devices otherwise.
#define MPZ_DIG_SIZE                        (16)
// Emitter-only features are pointless: frozen bytecode is bytecode-only.
#define MICROPY_EMIT_X64                    (0)
#define MICROPY_EMIT_X86                    (0)
// Stdout capture goes through mp_hal_stdout_tx_strn. With SYS_STDFILES on,
// print() writes to the sys.stdout stream object (fd 1 = /dev/null on
// Android) and the tool result loses all output; with it off, print() falls
// back to the plat print path, which our capture buffer owns.
#define MICROPY_PY_SYS_STDFILES             (0)

// ---- Builtins a plan realistically uses (computation + text + files) --------

// ROM MINIMUM disables floats, which turns int/int `/` into a TypeError -
// unacceptable for plans that compute. Single precision keeps the cost low.
#define MICROPY_PY_BUILTINS_FLOAT           (1)
#define MICROPY_FLOAT_IMPL                  (MICROPY_FLOAT_IMPL_FLOAT)
#define MICROPY_PY_BUILTINS_ENUMERATE       (1)
#define MICROPY_PY_BUILTINS_MIN_MAX         (1)
#define MICROPY_PY_BUILTINS_SORTED          (1)
#define MICROPY_PY_BUILTINS_STR_SPLITLINES  (1)
// TimeoutError no longer exists at this ROM level in v1.29; the bootstrap
// injects a Python-level TimeoutError(OSError) into builtins instead.
// open() on the sandbox VFS.
#define MICROPY_PY_BUILTINS_OPEN            (1)
#define MICROPY_PY_BUILTINS_INPUT           (0)
#define MICROPY_PY_BUILTINS_HELP            (0)

// ---- Modules ----------------------------------------------------------------

#define MICROPY_PY_GC                       (1)  // gc.mem_free() for the status dialog
#define MICROPY_PY_SYS                      (1)  // sys.path, sys.exit, sys.stdout
#define MICROPY_PY_SYS_MAXSIZE              (1)
// Seeds sys.path with ["", ".frozen"]; without it the import machinery never
// looks for the frozen asyncio package.
#define MICROPY_PY_SYS_PATH_ARGV_DEFAULTS   (1)
#define MICROPY_PY_SYS_PLATFORM             "harnessdroid"
#define MICROPY_PY_MICROPYTHON              (1)  // micropython.mem_info/stack_use
#define MICROPY_PY_IO                       (1)
#define MICROPY_PY_IO_FILEIO                (1)
#define MICROPY_PY_TIME                     (1)  // ticks_ms/sleep_ms; asyncio needs them
#define MICROPY_PY_JSON                     (1)  // the harness.call_tool bridge speaks JSON
#define MICROPY_PY_SELECT                   (1)  // asyncio/core.py does `import sys, select`
#define MICROPY_PY_OS                       (1)  // os.listdir/mkdir/remove + os.mount/VfsPosix
#define MICROPY_PY_OS_STATVFS               (0)
#define MICROPY_PY_ASYNCIO                  (1)  // C part (_asyncio); the py part is frozen
#define MICROPY_PY_URANDOM                  (0)
#define MICROPY_PY_STRUCT                   (0)
#define MICROPY_PY_COLLECTIONS              (0)
#define MICROPY_PY_RE                       (0)
#define MICROPY_PY_SOCKET                   (0)  // no network in the VM, on purpose
#define MICROPY_PY_SSL                      (0)

// ---- Sandboxed virtual filesystem ------------------------------------------

#define MICROPY_VFS                         (1)
#define MICROPY_VFS_POSIX                   (1)
// Lets the importer open sandbox .py files through the mounted VFS (and gives
// py/lexer.c its mp_lexer_new_from_file).
#define MICROPY_READER_VFS                  (1)

// ---- Frozen asyncio (pre-compiled .mpy, generated by tools/fetch_micropython.sh)

#define MICROPY_MODULE_FROZEN_MPY           (1)
#define MICROPY_PERSISTENT_CODE_LOAD        (1)
#define MICROPY_QSTR_EXTRA_POOL             mp_qstr_frozen_const_pool

// GC root pointers: our pre-allocated cancellation exception is declared in
// genhdr/root_pointers.h (see the comment there).

#endif // MICROPY_INCLUDED_HARNESSDROID_MPCONFIGPORT_H
