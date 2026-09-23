// Host-side stub for <android/log.h>: log calls go to stderr.
#ifndef HOST_ANDROID_LOG_STUB_H
#define HOST_ANDROID_LOG_STUB_H
#include <stdio.h>
#include <stdarg.h>
#define ANDROID_LOG_INFO  4
#define ANDROID_LOG_FATAL 7
static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void)prio;
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[%s] ", tag);
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
    return 1;
}
#endif
