/*
 * JNI surface of the harnessDroid MicroPython VM.
 *
 * Every entry point here is called on the single Kotlin-owned VM thread (the
 * MicroPython GC heap is not thread-safe) - the Kotlin engine owns the thread,
 * its work queue and the pump loop. Strings cross the boundary as byte arrays
 * holding UTF-8: JNI's NewStringUTF expects modified UTF-8 and would crash on
 * 4-byte sequences (emoji etc.) that plans legitimately print.
 *
 * MicroPython itself is MIT, see LICENSE.micropython.
 */
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "py/mpconfig.h"
#include "vm_shared.h"
#include "micropython_bootstrap.h"

// lifecyle implemented in embed_util_android.c
void vm_init(void *gc_heap, size_t gc_heap_size, void *stack_top);
void vm_deinit(void);
int vm_exec(const char *src, size_t timeout_ms, char **out_output, char **out_error);
void vm_request_cancel(void);
mp_uint_t vm_uptime_ms(void);
long vm_exec_count_get(void);
const char *vm_last_error_get(void);

static JavaVM *g_jvm = NULL;
static JNIEnv *g_env = NULL; // the VM thread's env, valid during each call
static jclass g_bridge_class = NULL;
static jmethodID g_call_tool_mid = NULL;
static jmethodID g_log_mid = NULL;

// ---------------------------------------------------------------- helpers --

static char *jbytes_to_cstr(JNIEnv *env, jbyteArray arr) {
    if (arr == NULL) {
        return NULL;
    }
    jsize len = (*env)->GetArrayLength(env, arr);
    char *out = malloc(len + 1);
    (*env)->GetByteArrayRegion(env, arr, 0, len, (jbyte *)out);
    out[len] = '\0';
    return out;
}

static jbyteArray cstr_to_jbytes(JNIEnv *env, const char *str) {
    if (str == NULL) {
        return NULL;
    }
    size_t len = strlen(str);
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)str);
    return arr;
}

// Minimal JSON string escaping; UTF-8 bytes pass through (JSON allows them).
static void json_escape_append(char **dst, size_t *dst_len, size_t dst_cap, const char *src) {
    for (const unsigned char *p = (const unsigned char *)src; *p != '\0'; p++) {
        char tmp[8];
        const char *rep = NULL;
        switch (*p) {
            case '"': rep = "\\\""; break;
            case '\\': rep = "\\\\"; break;
            case '\n': rep = "\\n"; break;
            case '\r': rep = "\\r"; break;
            case '\t': rep = "\\t"; break;
            default:
                if (*p < 0x20) {
                    snprintf(tmp, sizeof(tmp), "\\u%04x", *p);
                    rep = tmp;
                }
                break;
        }
        if (rep != NULL) {
            size_t rl = strlen(rep);
            if (*dst_len + rl < dst_cap) {
                memcpy(*dst + *dst_len, rep, rl);
                *dst_len += rl;
            }
        } else {
            if (*dst_len + 1 < dst_cap) {
                (*dst)[(*dst_len)++] = (char)*p;
            }
        }
    }
    (*dst)[*dst_len] = '\0';
}

// Build {"ok":..,"output":"..","error":".."} into a caller-freed buffer.
static char *build_result_json(int status, const char *output, const char *error) {
    size_t cap = strlen(output ? output : "") + strlen(error ? error : "") + 64;
    char *json = malloc(cap);
    size_t len = 0;
    if (status == 0) {
        len = strlen("{\"ok\":true,\"output\":\"");
        memcpy(json, "{\"ok\":true,\"output\":\"", len);
        json_escape_append(&json, &len, cap, output ? output : "");
        if (len + 3 < cap) {
            memcpy(json + len, "\"}", 3);
        }
    } else {
        len = strlen("{\"ok\":false,\"output\":\"");
        memcpy(json, "{\"ok\":false,\"output\":\"", len);
        json_escape_append(&json, &len, cap, output ? output : "");
        if (len + 11 < cap) {
            memcpy(json + len, "\",\"error\":\"", 11);
            len += 11;
        }
        json_escape_append(&json, &len, cap, error ? error : "");
        if (len + 2 < cap) {
            memcpy(json + len, "\"}", 3);
        }
    }
    return json;
}

// ----------------------------------------------------------- JNI up-calls --

char *vm_bridge_call_tool(const char *name, const char *args_json) {
    if (g_env == NULL || g_call_tool_mid == NULL) {
        return NULL;
    }
    jbyteArray jname = cstr_to_jbytes(g_env, name);
    jbyteArray jargs = cstr_to_jbytes(g_env, args_json);
    jbyteArray jres = (jbyteArray)(*g_env)->CallStaticObjectMethod(
        g_env, g_bridge_class, g_call_tool_mid, jname, jargs);
    char *out = NULL;
    if ((*g_env)->ExceptionCheck(g_env)) {
        (*g_env)->ExceptionClear(g_env);
        out = strdup("{\"ok\":false,\"error\":\"harness bridge exception\"}");
    } else if (jres != NULL) {
        out = jbytes_to_cstr(g_env, jres);
        (*g_env)->DeleteLocalRef(g_env, jres);
    }
    if (jname != NULL) (*g_env)->DeleteLocalRef(g_env, jname);
    if (jargs != NULL) (*g_env)->DeleteLocalRef(g_env, jargs);
    return out != NULL ? out : strdup("{\"ok\":false,\"error\":\"null tool result\"}");
}

void vm_bridge_log(const char *line) {
    if (g_env == NULL || g_log_mid == NULL) {
        return;
    }
    jbyteArray jline = cstr_to_jbytes(g_env, line);
    (*g_env)->CallStaticVoidMethod(g_env, g_bridge_class, g_log_mid, jline);
    if ((*g_env)->ExceptionCheck(g_env)) {
        (*g_env)->ExceptionClear(g_env);
    }
    if (jline != NULL) (*g_env)->DeleteLocalRef(g_env, jline);
}

// ---------------------------------------------------------- JNI entry points

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass cls = (*env)->FindClass(env, "com/ai/harnessdroid/python/MicropythonBridge");
    if (cls == NULL) {
        return JNI_ERR;
    }
    g_bridge_class = (*env)->NewGlobalRef(env, cls);
    g_call_tool_mid = (*env)->GetStaticMethodID(env, cls, "callTool",
        "([B[B)[B");
    g_log_mid = (*env)->GetStaticMethodID(env, cls, "log", "([B)V");
    if (g_call_tool_mid == NULL || g_log_mid == NULL) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_ai_harnessdroid_python_MicropythonNative_nativeStart(
        JNIEnv *env, jclass clazz, jbyteArray sandbox_dir_bytes, jint heap_kb) {
    (void)clazz;
    g_env = env;

    char *sandbox_dir = jbytes_to_cstr(env, sandbox_dir_bytes);
    size_t heap_size = (size_t)heap_kb * 1024;
    void *heap = malloc(heap_size);
    if (heap == NULL || sandbox_dir == NULL) {
        free(heap);
        free(sandbox_dir);
        return JNI_FALSE;
    }

    // stack_top is only a fallback: vm_init anchors the C-stack check on the
    // thread's real stack bounds via pthread_getattr_np (a caller-frame local
    // sits below the Kotlin/JNI frames, which broke the check on-device).
    int stack_top;
    vm_init(heap, heap_size, (void *)&stack_top);

    // Run the bootstrap (mount sandbox VFS, asyncio loop, pump definition).
    size_t boot_len = strlen(VM_BOOTSTRAP_TEMPLATE) + strlen(sandbox_dir) + 1;
    char *boot_src = malloc(boot_len);
    snprintf(boot_src, boot_len, VM_BOOTSTRAP_TEMPLATE, sandbox_dir);
    char *out = NULL;
    char *err = NULL;
    int status = vm_exec(boot_src, 5000, &out, &err);
    free(boot_src);
    free(out);
    free(err);
    free(sandbox_dir);

    if (status != 0) {
        vm_deinit();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_ai_harnessdroid_python_MicropythonNative_nativeExec(
        JNIEnv *env, jclass clazz, jbyteArray source_bytes, jint timeout_ms) {
    (void)clazz;
    g_env = env;
    char *source = jbytes_to_cstr(env, source_bytes);
    if (source == NULL) {
        return cstr_to_jbytes(env, "{\"ok\":false,\"error\":\"missing source\"}");
    }
    char *out = NULL;
    char *err = NULL;
    int status = vm_exec(source, (size_t)timeout_ms, &out, &err);
    free(source);
    char *json = build_result_json(status, out, err);
    free(out);
    free(err);
    jbyteArray res = cstr_to_jbytes(env, json);
    free(json);
    return res;
}

// One asyncio pump tick, run when no plan is executing. Output is discarded;
// a failure (e.g. a service raised) surfaces in last_error for the dialog.
JNIEXPORT void JNICALL
Java_com_ai_harnessdroid_python_MicropythonNative_nativePump(
        JNIEnv *env, jclass clazz, jint timeout_ms) {
    (void)clazz;
    g_env = env;
    char src[64];
    snprintf(src, sizeof(src), "__harness_pump__(%d)", (int)timeout_ms);
    char *out = NULL;
    char *err = NULL;
    vm_exec(src, 2000, &out, &err);
    free(out);
    free(err);
}

JNIEXPORT jbyteArray JNICALL
Java_com_ai_harnessdroid_python_MicropythonNative_nativeStatus(
        JNIEnv *env, jclass clazz) {
    (void)clazz;
    g_env = env;
    char json[512];
    long uptime = (long)vm_uptime_ms();
    long execs = vm_exec_count_get();
    const char *last_err = vm_last_error_get();
    if (last_err == NULL) {
        last_err = "";
    }
    // Escape not needed for the numeric fields; last_error is escaped loosely
    // (dialog-only, embedded as a JSON string field).
    char err_esc[256];
    size_t el = 0;
    json_escape_append(&err_esc, &el, sizeof(err_esc), last_err);
    snprintf(json, sizeof(json),
        "{\"booted\":true,\"uptime_ms\":%ld,\"exec_count\":%ld,\"last_error\":\"%s\"}",
        uptime, execs, err_esc);
    return cstr_to_jbytes(env, json);
}

JNIEXPORT void JNICALL
Java_com_ai_harnessdroid_python_MicropythonNative_nativeRequestCancel(
        JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    vm_request_cancel();
}

JNIEXPORT void JNICALL
Java_com_ai_harnessdroid_python_MicropythonNative_nativeStop(
        JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    g_env = NULL;
    vm_deinit();
}
