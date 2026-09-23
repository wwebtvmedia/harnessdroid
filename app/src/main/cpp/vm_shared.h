/*
 * Internal state shared by the harnessDroid MicroPython VM translation units
 * (mphalport_android.c, embed_util_android.c, harness_module.c,
 * micropython_jni.c). Not installed as a public header.
 */
#ifndef MICROPY_INCLUDED_HARNESSDROID_VM_SHARED_H
#define MICROPY_INCLUDED_HARNESSDROID_VM_SHARED_H

#include <stdbool.h>
#include <stddef.h>
#include "py/mpconfig.h"
#include "py/obj.h"

// Cancel / deadline bookkeeping. Set by the JNI layer (or the SIGURG handler),
// checked by mp_hal_delay_ms and by the harness module.
extern volatile bool vm_cancel_pending;       // cancellation was requested
extern volatile mp_uint_t vm_deadline_ms;     // absolute ticks_ms deadline, 0 = none

// Raises the pre-allocated cancellation exception. Only legal while a script
// is executing (nlr context live) - guarded by vm_executing.
void vm_raise_cancel(void);

// True while vm_exec() has an nlr context on the C stack (and the SIGURG
// handler is therefore allowed to longjmp).
extern volatile bool vm_executing;

// True while a plan is blocked inside the harness -> Kotlin tool bridge. A
// longjmp out of that would unwind through JNI frames; the handler must defer
// to a flag and the bridge site raises at the next safe point instead.
extern volatile bool vm_in_bridge;

// Output capture (stdout of the VM), implemented in mphalport_android.c.
void vm_output_begin(size_t cap);
void vm_output_reset(void);
size_t vm_output_take(char **out);            // hands over the buffer, resets

// JNI bridge entry point used by the harness Python module; defined in
// micropython_jni.c. Returns a malloc'd UTF-8 JSON string (caller frees) or
// NULL if the bridge is unavailable.
char *vm_bridge_call_tool(const char *name, const char *args_json);
void vm_bridge_log(const char *line);

#endif // MICROPY_INCLUDED_HARNESSDROID_VM_SHARED_H
