/*
 * mphal implementation for the harnessDroid embedded MicroPython VM.
 *
 * Everything print() writes lands in an in-memory capture buffer that the JNI
 * layer hands back to Kotlin after each exec. mp_hal_delay_ms enforces the
 * per-plan wall-clock deadline and the cancellation flag, which kills any
 * plan that sleeps or awaits past its budget; pure-Python CPU loops are
 * stopped by the SIGURG handler in embed_util_android.c instead.
 *
 * Part of the harnessDroid MicroPython integration; MicroPython itself is MIT
 * (see LICENSE.micropython).
 */
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

// Deliberately NOT including py/mphal.h: it drags in extmod/virtpin.h via an
// unconditional fallback, which we neither have nor need.
#include "py/mpconfig.h"
#include "mphalport_android.h"
#include "vm_shared.h"
#include <android/log.h>

volatile bool vm_cancel_pending = false;
volatile mp_uint_t vm_deadline_ms = 0;
volatile bool vm_executing = false;
volatile bool vm_in_bridge = false;

// ---------------------------------------------------------------- output ---

static char *vm_out_buf = NULL;
static size_t vm_out_len = 0;
static size_t vm_out_cap = 0;
static bool vm_out_truncated = false;

void vm_output_begin(size_t cap) {
    // Reuse the buffer across execs: the pump runs every ~20 ms and must not
    // malloc/free 16 KB each tick.
    if (vm_out_buf == NULL || vm_out_cap + 64 < cap + 64) {
        free(vm_out_buf);
        vm_out_buf = malloc(cap + 64);
    }
    vm_out_buf[0] = '\0';
    vm_out_len = 0;
    vm_out_cap = cap;
    vm_out_truncated = false;
}

void vm_output_reset(void) {
    // Keep the allocation; the pump reuses it every tick.
    vm_out_len = 0;
    vm_out_cap = 0;
    vm_out_truncated = false;
}

size_t vm_output_take(char **out) {
    *out = NULL;
    if (vm_out_buf == NULL) {
        return 0;
    }
    if (vm_out_truncated && vm_out_len + 32 < vm_out_cap + 64) {
        const char *marker = "\n[output truncated]\n";
        strcat(vm_out_buf, marker);
        vm_out_len = strlen(vm_out_buf);
    }
    // Hand out a fresh copy; the capture buffer stays cached for the pump.
    char *copy = malloc(vm_out_len + 1);
    memcpy(copy, vm_out_buf, vm_out_len + 1);
    size_t n = vm_out_len;
    *out = copy;
    vm_out_len = 0;
    vm_out_buf[0] = '\0';
    return n;
}

static void vm_out_write(const char *str, size_t len) {
    if (vm_out_buf == NULL || vm_out_truncated) {
        return;
    }
    if (vm_out_len + len + 1 > vm_out_cap) {
        vm_out_truncated = true;
        return;
    }
    memcpy(vm_out_buf + vm_out_len, str, len);
    vm_out_len += len;
    vm_out_buf[vm_out_len] = '\0';
}

// ------------------------------------------------------------- mp_hal API ---

void mp_hal_set_interrupt_char(char c) {
    (void)c; // no keyboard; cancellation goes through vm_request_cancel()
}

int mp_hal_stdin_rx_chr(void) {
    // No stdin in the sandbox (input() is compiled out anyway).
    return 0;
}

mp_uint_t mp_hal_stdout_tx_strn(const char *str, size_t len) {
    vm_out_write(str, len);
    return len;
}

void mp_hal_stdout_tx_strn_cooked(const char *str, size_t len) {
    // Keep it raw: Kotlin renders the result in a JSON string field.
    (void)mp_hal_stdout_tx_strn(str, len);
}

static mp_uint_t ticks_monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (mp_uint_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

mp_uint_t mp_hal_ticks_ms(void) {
    return ticks_monotonic_ms();
}

mp_uint_t mp_hal_ticks_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (mp_uint_t)(ts.tv_sec * 1000000 + ts.tv_nsec / 1000);
}

// time.ticks_cpu() is compiled in by modtime.c; report process CPU time (best
// effort, and it overflows like every other ticks counter - plans should use
// ticks_diff()).
mp_uint_t mp_hal_ticks_cpu(void) {
    struct timespec ts;
    clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &ts);
    return (mp_uint_t)(ts.tv_sec * 1000000 + ts.tv_nsec / 1000);
}

static void vm_check_cancel(void) {
    if (vm_cancel_pending) {
        vm_cancel_pending = false;
        vm_raise_cancel();
    }
    if (vm_deadline_ms != 0 && ticks_monotonic_ms() >= vm_deadline_ms) {
        vm_raise_cancel();
    }
}

void mp_hal_delay_ms(mp_uint_t ms) {
    mp_uint_t start = ticks_monotonic_ms();
    for (;;) {
        vm_check_cancel(); // raises if the plan must die
        mp_uint_t now = ticks_monotonic_ms();
        if (now - start >= ms) {
            return;
        }
        struct timespec ts = { .tv_sec = 0, .tv_nsec = 200000 }; // 0.2 ms slice
        nanosleep(&ts, NULL);
    }
}

void mp_hal_delay_us(mp_uint_t us) {
    mp_uint_t start = mp_hal_ticks_us();
    for (;;) {
        vm_check_cancel();
        if (mp_hal_ticks_us() - start >= us) {
            return;
        }
        struct timespec ts = { .tv_sec = 0, .tv_nsec = 1000 };
        nanosleep(&ts, NULL);
    }
}
