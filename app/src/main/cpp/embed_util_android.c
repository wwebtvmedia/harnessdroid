/*
 * VM lifecycle and script execution for the harnessDroid embedded MicroPython.
 *
 * Replaces the embed port's embed_util.c, which swallows exceptions into a
 * printf and loops forever on fatal errors - neither is acceptable here:
 * every plan run must return a structured (output, traceback) pair to
 * Kotlin, and a fatal VM error must abort instead of spinning.
 *
 * Globals persist across vm_exec() calls because every exec runs in the
 * module __main__ globals; that is what lets a plan schedule asyncio services
 * that keep running (pumped from Kotlin) after the exec that created them.
 *
 * MicroPython itself is MIT, see LICENSE.micropython.
 */
#define _GNU_SOURCE
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>

#include "py/compile.h"
#include "py/gc.h"
#include "py/persistentcode.h"
#include "py/runtime.h"
#include "py/stackctrl.h"
#include "shared/runtime/gchelper.h"
#include "port/micropython_embed.h"
#include "mphalport_android.h"
#include "vm_shared.h"

// C-stack budget for Python-level recursion. Must stay well below the stack
// size of the dedicated VM thread (MICROPYTHON_VM_STACK_SIZE on the Kotlin
// side); deep plan recursion raises RecursionError instead of smashing it.
#define MP_STACK_LIMIT (32768)

// ------------------------------------------------------------- VM state ----

static void *vm_heap = NULL;
static size_t vm_heap_size = 0;
static mp_uint_t vm_started_ms = 0;
static long vm_exec_count = 0;
static char *vm_last_error = NULL;   // malloc'd, owned here
static pthread_t vm_pthread;

mp_uint_t vm_uptime_ms(void);
long vm_exec_count_get(void);
const char *vm_last_error_get(void);
void vm_last_error_set(const char *err);
bool vm_is_booted(void);

mp_uint_t vm_uptime_ms(void) {
    return vm_started_ms == 0 ? 0 : mp_hal_ticks_ms() - vm_started_ms;
}

long vm_exec_count_get(void) {
    return vm_exec_count;
}

const char *vm_last_error_get(void) {
    return vm_last_error;
}

void vm_last_error_set(const char *err) {
    free(vm_last_error);
    vm_last_error = err != NULL ? strdup(err) : NULL;
}

bool vm_is_booted(void) {
    return vm_heap != NULL;
}

// ------------------------------------------------- cancellation handling ---

void vm_raise_cancel(void) {
    if (MP_STATE_VM(vm_cancel_exception) != MP_OBJ_NULL) {
        nlr_raise(MP_STATE_VM(vm_cancel_exception));
    }
    // No exception allocated (should not happen): abort loudly rather than
    // continue a plan that must die.
    abort();
}

// Immediate stop for pure-Python CPU loops: SIGURG arrives while the bytecode
// VM runs, and we longjmp out with the pre-allocated TimeoutError - the same
// pattern the unix port uses for Ctrl-C. Guarded by vm_executing so a signal
// landing outside script execution can never longjmp over a missing nlr_buf.
static void vm_sigurg_handler(int signum) {
    (void)signum;
    // Inside a harness tool call the C stack runs through JNI frames we must
    // not longjmp over: defer, and the bridge site raises when it is back in
    // MicroPython C code.
    if (!vm_executing || vm_in_bridge) {
        vm_cancel_pending = true;
        return;
    }
    mp_obj_exception_clear_traceback(MP_STATE_VM(vm_cancel_exception));
    sigset_t mask;
    sigemptyset(&mask);
    // The signal is blocked inside its own handler; nlr_raise longjmps out, so
    // unblock it manually first (comment and pattern from unix_mphal.c).
    sigprocmask(SIG_SETMASK, &mask, NULL);
    nlr_raise(MP_STATE_VM(vm_cancel_exception));
}

void vm_install_cancel_signal(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = vm_sigurg_handler;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGURG, &sa, NULL);
}

void vm_request_cancel(void) {
    vm_cancel_pending = true;
    pthread_kill(vm_pthread, SIGURG);
}

// ---------------------------------------------------------- exec helpers ---

// Captures mp_obj_print_exception output into a fixed buffer.
typedef struct {
    char buf[2048];
    size_t len;
} exc_text_t;

static void exc_print_strn(void *data, const char *str, size_t len) {
    exc_text_t *t = (exc_text_t *)data;
    if (t->len + len < sizeof(t->buf)) {
        memcpy(t->buf + t->len, str, len);
        t->len += len;
        t->buf[t->len] = '\0';
    }
}

void vm_print_exception_text(mp_obj_t exc, char *out, size_t out_len) {
    exc_text_t t;
    t.buf[0] = '\0';
    t.len = 0;
    mp_print_t print = { &t, exc_print_strn };
    mp_obj_print_exception(&print, exc);
    snprintf(out, out_len, "%s", t.buf);
}

// -------------------------------------------------------------- lifecycle --

void vm_init(void *gc_heap, size_t gc_heap_size, void *stack_top) {
    vm_pthread = pthread_self();
    (void)stack_top;
    // Anchor the C-stack check to the REAL top of this thread's stack, not a
    // caller frame: the JNI/Kotlin frames above nativeStart already consume
    // ~12 KB, and an entry point shallower than the one that took a local's
    // address (enqueue vs submit) measures negative usage against it - the
    // check then fails instantly and every plan dies in a bogus RecursionError.
    {
        pthread_attr_t attr;
        void *base = NULL;
        size_t size = 0;
        if (pthread_getattr_np(vm_pthread, &attr) == 0) {
            pthread_attr_getstack(&attr, &base, &size);
            pthread_attr_destroy(&attr);
        }
        if (base != NULL && size > 0) {
            mp_stack_set_top((char *)base + size); // descending stack
        } else {
            mp_stack_set_top(stack_top); // best effort fallback
        }
    }
    vm_install_cancel_signal();
    vm_cancel_pending = false;
    vm_deadline_ms = 0;
    MP_STATE_VM(vm_cancel_exception) = MP_OBJ_NULL;

    // MICROPY_STACK_CHECK compares against stack_limit, which is 0 unless the
    // port sets it - every mp_stack_check() would then raise RecursionError
    // (the ROM-MINIMUM embed port never hits this because it leaves the check
    // disabled). Keep the C recursion budget well inside the VM thread stack.
    mp_stack_set_limit(MP_STACK_LIMIT);
    gc_init(gc_heap, (uint8_t *)gc_heap + gc_heap_size);
    mp_init();

    // Pre-allocate the cancellation exception: the SIGURG handler must not
    // allocate. Done after mp_init so the GC heap is live. KeyboardInterrupt
    // is the traditional interrupt type (the unix port uses it for Ctrl-C);
    // TimeoutError does not exist as a native type at this ROM level.
    MP_STATE_VM(vm_cancel_exception) = mp_obj_new_exception_msg(&mp_type_KeyboardInterrupt,
        "plan cancelled or deadline exceeded");

    vm_heap = gc_heap;
    vm_heap_size = gc_heap_size;
    vm_started_ms = mp_hal_ticks_ms();
    vm_exec_count = 0;
    vm_last_error_set(NULL);
}

void vm_deinit(void) {
    if (vm_heap == NULL) {
        return;
    }
    mp_deinit();
    free(vm_heap);
    vm_heap = NULL;
    vm_heap_size = 0;
    vm_started_ms = 0;
    vm_last_error_set(NULL);
}

// Compile-and-run `src` in the persistent __main__ globals.
// Returns 0 on success, 1 on a Python exception (text in *out_error),
// 2 if the run was cancelled before it started. *out_output (caller frees)
// receives everything the plan printed.
int vm_exec(const char *src, size_t timeout_ms, char **out_output, char **out_error) {
    *out_output = NULL;
    *out_error = NULL;
    if (vm_heap == NULL) {
        return 2;
    }
    if (vm_cancel_pending) {
        // A cancel that landed between two execs: refuse to start.
        vm_cancel_pending = false;
        *out_error = strdup("cancelled");
        return 2;
    }

    vm_output_begin(16 * 1024);
    vm_deadline_ms = timeout_ms != 0 ? mp_hal_ticks_ms() + timeout_ms : 0;
    vm_executing = true;

    nlr_buf_t nlr;
    int status = 0;
    if (nlr_push(&nlr) == 0) {
        mp_lexer_t *lex = mp_lexer_new_from_str_len(MP_QSTR__lt_stdin_gt_, src, strlen(src), 0);
        qstr source_name = lex->source_name;
        mp_parse_tree_t parse_tree = mp_parse(lex, MP_PARSE_FILE_INPUT);
        mp_obj_t module_fun = mp_compile(&parse_tree, source_name, true);
        mp_call_function_0(module_fun);
        nlr_pop();
    } else {
        // Uncaught Python exception (including our cancellation): format the
        // traceback for the LLM instead of swallowing it.
        char exc_buf[2048];
        vm_print_exception_text(MP_OBJ_FROM_PTR(nlr.ret_val), exc_buf, sizeof(exc_buf));
        *out_error = strdup(exc_buf);
        vm_last_error_set(exc_buf);
        status = 1;
    }

    vm_executing = false;
    vm_deadline_ms = 0;
    vm_exec_count++;

    size_t out_len = vm_output_take(out_output);
    (void)out_len;
    return status;
}

// Run a garbage collection cycle (copied from embed_util.c).
void gc_collect(void) {
    gc_collect_start();
    gc_helper_collect_regs_and_stack();
    gc_collect_end();
}

// Fatal paths: the upstream stubs loop forever; here we abort so the Android
// crash report shows what happened instead of the app hanging silently. The
// log matters: MicroPython's own printf lands in the captured stdout buffer,
// which a following abort throws away.
void nlr_jump_fail(void *val) {
    __android_log_print(ANDROID_LOG_FATAL, "micropython",
        "nlr_jump_fail val=%p executing=%d in_bridge=%d cstack_usage=%u limit=%u",
        val, (int)vm_executing, (int)vm_in_bridge,
        (unsigned)mp_cstack_usage(), (unsigned)MP_STATE_THREAD(stack_limit));
    abort();
}

#ifndef NDEBUG
void __assert_func(const char *file, int line, const char *func, const char *expr) {
    (void)file;
    (void)line;
    (void)func;
    (void)expr;
    abort();
}
#endif
