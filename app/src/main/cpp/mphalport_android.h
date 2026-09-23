/*
 * mphal for the harnessDroid embedded MicroPython VM (Android).
 *
 * Overrides the embed port's stub header: py/mphal.h includes whatever
 * MICROPY_MPHALPORT_H names, and mpconfigport.h points that at this file.
 * The prototypes below are the ones py/mphal.h would otherwise declare.
 */
#ifndef MICROPY_INCLUDED_HARNESSDROID_MPHALPORT_ANDROID_H
#define MICROPY_INCLUDED_HARNESSDROID_MPHALPORT_ANDROID_H

#include "py/mpconfig.h"

void mp_hal_set_interrupt_char(char c);
int mp_hal_stdin_rx_chr(void);
mp_uint_t mp_hal_stdout_tx_strn(const char *str, size_t len);
void mp_hal_stdout_tx_strn_cooked(const char *str, size_t len);
void mp_hal_delay_ms(mp_uint_t ms);
void mp_hal_delay_us(mp_uint_t us);
mp_uint_t mp_hal_ticks_ms(void);
mp_uint_t mp_hal_ticks_us(void);

// Needed by extmod/vfs_posix*.c (the unix port defines the same in its
// mphalport.h). EINTR just retries the syscall; every other error raises the
// caller's OSError with the live errno. No scheduler pump: the only signal
// this VM sees is our own SIGURG cancellation, which is checked explicitly
// rather than through blocked syscalls.
#include <errno.h>
#define MP_HAL_RETRY_SYSCALL(ret, syscall, raise) { \
    for (;;) { \
        (ret) = (syscall); \
        if ((ret) == -1) { \
            int err = errno; \
            if (err == EINTR) { \
                continue; \
            } \
            raise; \
        } \
        break; \
    } \
}

#endif // MICROPY_INCLUDED_HARNESSDROID_MPHALPORT_ANDROID_H
