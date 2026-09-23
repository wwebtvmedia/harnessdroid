/*
 * Host-side smoke test for the harnessDroid MicroPython VM sources.
 * Compiles the same C files as the Android build (minus the JNI layer,
 * which this file replaces) and runs the bootstrap + a few plans.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>

#include "vm_shared.h"
#include "micropython_bootstrap.h"

void vm_init(void *gc_heap, size_t gc_heap_size, void *stack_top);
void vm_deinit(void);
int vm_exec(const char *src, size_t timeout_ms, char **out_output, char **out_error);
void vm_request_cancel(void);
const char *vm_last_error_get(void);

char *vm_bridge_call_tool(const char *name, const char *args_json) {
    printf("[bridge] call_tool(%s, %s)\n", name, args_json);
    return strdup("{\"ok\":true,\"result\":\"mock-tool-answer\"}");
}

void vm_bridge_log(const char *line) {
    printf("[bridge] log(%s)\n", line);
}

static int failures = 0;

static void run_plan(const char *label, const char *src, size_t timeout_ms) {
    char *out = NULL;
    char *err = NULL;
    int status = vm_exec(src, timeout_ms, &out, &err);
    printf("=== %s -> status=%d\n", label, status);
    if (out && out[0]) printf("--- output:\n%s", out);
    if (err) printf("--- error:\n%s\n", err);
    if (status != 0) failures++;
    free(out);
    free(err);
}

// Same as run_plan but silent unless the tick fails; service output is
// expected on some ticks and is shown, not counted as a failure.
static void run_plan_quiet(const char *src, size_t timeout_ms) {
    char *out = NULL;
    char *err = NULL;
    int status = vm_exec(src, timeout_ms, &out, &err);
    if (status != 0) {
        printf("--- pump tick status=%d output:\n%s", status, out ? out : "");
        if (err) printf("--- pump error:\n%s\n", err);
        failures++;
    } else if (out && out[0]) {
        printf("--- tick output:\n%s", out);
    }
    free(out);
    free(err);
}

int main(void) {
    size_t heap_size = 64 * 1024; // same as the on-device default
    void *heap = malloc(heap_size);
    int stack_top;
    vm_init(heap, heap_size, &stack_top);

    // Bootstrap (mount /tmp/mpsandbox as VM root).
    size_t bl = strlen(VM_BOOTSTRAP_TEMPLATE) + 64;
    char *boot = malloc(bl);
    snprintf(boot, bl, VM_BOOTSTRAP_TEMPLATE, "/tmp/mpsandbox");
    char *out = NULL, *err = NULL;
    int st = vm_exec(boot, 5000, &out, &err);
    printf("=== bootstrap -> status=%d\n", st);
    if (err) printf("--- error:\n%s\n", err);
    if (st != 0) failures++;
    free(boot); free(out); free(err);

    run_plan("print/compute",
        "print('hello', 1+1, sorted([3,1,2]))\n"
        "import sys\nprint('py', sys.version.split(' ')[0])\n", 5000);

    run_plan("json", "import json\nprint(json.dumps({'a': 1}))\n", 5000);

    run_plan("filesystem",
        "import os\n"
        "try:\n    os.mkdir('data')\nexcept OSError:\n    pass\n"
        "open('data/test.txt', 'w').write('persisted')\n"
        "print(os.listdir('data'))\n"
        "print(open('data/test.txt').read())\n", 5000);

    run_plan("sandbox escape rejected",
        "try:\n"
        "    open('../escape.txt', 'w')\n"
        "    print('ESCAPED - BUG')\n"
        "except ValueError as e:\n"
        "    print('blocked ok:', e)\n", 5000);

    run_plan("asyncio + call_tool",
        "import asyncio, harness\n"
        "async def main():\n"
        "    print('calling tool')\n"
        "    r = call_tool('get_os_info', {})\n"
        "    print('tool said:', r)\n"
        "plan(main())\n", 5000);

    // Deadline enforcement: status 1 with the pre-allocated KeyboardInterrupt
    // is the DESIGNED outcome (and 'NOT REACHED' must never be printed).
    {
        char *o = NULL, *e = NULL;
        int st = vm_exec("import time\nprint('sleeping')\ntime.sleep_ms(3000)\nprint('NOT REACHED')\n", 700, &o, &e);
        printf("=== timeout (sleep past deadline) -> status=%d\n", st);
        if (st != 1 || e == NULL || strstr(e, "KeyboardInterrupt") == NULL
            || (o != NULL && strstr(o, "NOT REACHED") != NULL)) {
            printf("--- FAIL: expected deadline kill with KeyboardInterrupt\n");
            failures++;
        }
        free(o); free(e);
    }

    run_plan("vm still usable after timeout", "print('alive')\n", 5000);

    // Traceback quality is a designed outcome: status must be 1 with the
    // exception type visible (the LLM needs it to fix its own plan).
    {
        char *o = NULL, *e = NULL;
        int st = vm_exec("def f():\n    return 1/0\nf()\n", 5000, &o, &e);
        printf("=== traceback for the LLM -> status=%d\n", st);
        if (st != 1 || e == NULL || strstr(e, "ZeroDivisionError") == NULL) {
            printf("--- FAIL: expected status 1 + ZeroDivisionError traceback\n%s\n", e ? e : "");
            failures++;
        } else {
            printf("--- traceback ok:\n%s\n", e);
        }
        free(o); free(e);
    }

    // CPU loop + SIGURG cancel from another thread is covered on-device;
    // here we just verify request-cancel between execs is refused cleanly.
    // status==2 is the DESIGNED outcome, so this one is not a run_plan failure.
    {
        vm_request_cancel();
        char *o = NULL, *e = NULL;
        int st = vm_exec("print('NOT REACHED')\n", 1000, &o, &e);
        printf("=== cancel before exec refused -> status=%d\n", st);
        if (st != 2) { printf("--- FAIL: expected refusal (2)\n"); failures++; }
        // The refused exec consumed the flag; nothing to clear.
        free(o); free(e);
    }

    run_plan("globals persistence",
        "try:\n    counter = counter + 1\nexcept NameError:\n    counter = 1\n"
        "print('counter', counter)\n", 5000);
    run_plan("globals persistence 2",
        "counter = counter + 1\nprint('counter now', counter)\n", 5000);

    // Scheduler: arm every() from one exec, then pump from C like Kotlin does.
    // First with a short timer and no wall-clock pacing, then with the exact
    // timer of the on-device test (1 s) and real 100 ms sleeps between ticks.
    run_plan("scheduler arm",
        "global n\n"
        "n = 0\n"
        "async def beat():\n"
        "    global n\n"
        "    n += 1\n"
        "    print('beat fired', n)\n"
        "every(0.05, beat)\n"
        "print('armed')\n", 5000);
    for (int i = 0; i < 60; i++) {
        run_plan_quiet("__harness_pump__(20)\n", 2000);
    }
    run_plan("scheduler result", "print('n =', n)\n", 5000);

    run_plan("scheduler arm 1s",
        "m = 0\n"
        "async def tock():\n"
        "    global m\n"
        "    m += 1\n"
        "    print('tock fired', m)\n"
        "every(1, tock)\n"
        "print('armed')\n", 5000);
    for (int i = 0; i < 30; i++) {
        run_plan_quiet("__harness_pump__(20)\n", 2000);
        usleep(100000); // 100 ms wall clock, like the on-device pump cadence
    }
    run_plan("scheduler result 1s", "print('m =', m)\n", 5000);

    // await call_tool(...) guard: a plan that awaits the sync result must get
    // the dict (via StopIteration(self) from __next__), not a silent hang.
    run_plan("await-safe tool result",
        "class _ToolResult(dict):\n"
        "    def __iter__(self):\n"
        "        return self\n"
        "    def __next__(self):\n"
        "        raise StopIteration(self)\n"
        "async def b():\n"
        "    r = await _ToolResult({'result': 'Android x'})\n"
        "    print('awaited:', r['result'])\n"
        "plan(b())\n"
        "print('done, no hang')\n", 5000);

    vm_deinit();
    printf("\nfailures=%d\n", failures);
    return failures != 0;
}
