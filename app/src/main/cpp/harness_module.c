/*
 * The `harness` Python module: the only native module exposed to plans.
 *
 * Plans use the Python-side wrappers defined by the bootstrap (call_tool,
 * log); these _-prefixed functions are the raw C bridge. call_tool blocks the
 * VM thread until Kotlin's ToolRegistry answers on another thread - that is
 * safe because the coroutine that launched the plan is suspended, and it is
 * what lets a plan drive device tools (permission guards, result caps and
 * forensic logging all apply to these nested calls as to any other).
 *
 * MicroPython itself is MIT, see LICENSE.micropython.
 */
#include <stdlib.h>

#include "py/runtime.h"
#include "mphalport_android.h"
#include "vm_shared.h"

static mp_obj_t mod_harness_call_tool_raw(mp_obj_t name_obj, mp_obj_t args_obj) {
    const char *name = mp_obj_str_get_str(name_obj);
    const char *args_json = mp_obj_str_get_str(args_obj);
    // Check before entering the bridge: a cancel requested between two tool
    // calls must not run one more tool.
    if (vm_cancel_pending) {
        vm_cancel_pending = false;
        vm_raise_cancel();
    }
    // vm_in_bridge tells the SIGURG handler to defer: the tool call runs in
    // Kotlin frames a longjmp must never unwind. Raise only once we are back
    // in MicroPython C code.
    vm_in_bridge = true;
    char *result = vm_bridge_call_tool(name, args_json);
    vm_in_bridge = false;
    if (vm_cancel_pending) {
        free(result);
        vm_cancel_pending = false;
        vm_raise_cancel();
    }
    if (result == NULL) {
        mp_raise_msg(&mp_type_RuntimeError, MP_ERROR_TEXT("harness bridge unavailable"));
    }
    mp_obj_t out = mp_obj_new_str(result, strlen(result));
    free(result);
    return out;
}
static MP_DEFINE_CONST_FUN_OBJ_2(mod_harness_call_tool_raw_obj, mod_harness_call_tool_raw);

static mp_obj_t mod_harness_log(mp_obj_t msg_obj) {
    const char *msg = mp_obj_str_get_str(msg_obj);
    vm_bridge_log(msg);
    return mp_const_none;
}
static MP_DEFINE_CONST_FUN_OBJ_1(mod_harness_log_obj, mod_harness_log);

static mp_obj_t mod_harness_now_ms(void) {
    return MP_OBJ_NEW_SMALL_INT((mp_int_t)mp_hal_ticks_ms());
}
static MP_DEFINE_CONST_FUN_OBJ_0(mod_harness_now_ms_obj, mod_harness_now_ms);

static mp_obj_t mod_harness_cancel_requested(void) {
    return mp_obj_new_bool(vm_cancel_pending);
}
static MP_DEFINE_CONST_FUN_OBJ_0(mod_harness_cancel_requested_obj, mod_harness_cancel_requested);

static const mp_rom_map_elem_t mp_module_harness_globals_table[] = {
    { MP_ROM_QSTR(MP_QSTR___name__), MP_ROM_QSTR(MP_QSTR_harness) },
    { MP_ROM_QSTR(MP_QSTR__call_tool_raw), MP_ROM_PTR(&mod_harness_call_tool_raw_obj) },
    { MP_ROM_QSTR(MP_QSTR__log), MP_ROM_PTR(&mod_harness_log_obj) },
    { MP_ROM_QSTR(MP_QSTR__now_ms), MP_ROM_PTR(&mod_harness_now_ms_obj) },
    { MP_ROM_QSTR(MP_QSTR__cancel_requested), MP_ROM_PTR(&mod_harness_cancel_requested_obj) },
};
static MP_DEFINE_CONST_DICT(mp_module_harness_globals, mp_module_harness_globals_table);

const mp_obj_module_t mp_module_harness = {
    .base = { &mp_type_module },
    .globals = (mp_obj_dict_t *)&mp_module_harness_globals,
};

MP_REGISTER_MODULE(MP_QSTR_harness, mp_module_harness);
