/*
 * Bootstrap Python source, executed once after mp_init.
 *
 * - mounts the sandbox directory (filesDir/micropython) as the VM's '/' via
 *   VfsPosix, so every path a plan sees lives inside the sandbox;
 * - wraps the file APIs to reject '..' (VfsPosix does not resolve it against
 *   the mount root, so it would walk out onto the device filesystem);
 * - creates the asyncio loop and the __harness_pump__ tick that Kotlin calls
 *   between plan execs, which is what keeps scheduled services alive;
 * - defines the plan()/every()/call_tool() helpers plans are documented to use.
 *
 * %s in the C template is replaced with the device sandbox path at VM start.
 */
#ifndef MICROPY_INCLUDED_HARNESSDROID_MICROPYTHON_BOOTSTRAP_H
#define MICROPY_INCLUDED_HARNESSDROID_MICROPYTHON_BOOTSTRAP_H

#define VM_BOOTSTRAP_TEMPLATE \
    "# Mount first: the default sys.path entry '' stats the sandbox on every\n" \
    "# import, and statting an unmounted VFS raises ENODEV. os itself resolves\n" \
    "# statically, before the path scan.\n" \
    "import os\n" \
    "os.mount(os.VfsPosix('%s'), '/')\n" \
    "os.chdir('/')\n" \
    "import gc, json, sys, time\n" \
    "import harness\n" \
    "import asyncio\n" \
    "#\n" \
    "# uasyncio's default exception handler prints to sys.stderr, which does\n" \
    "# not exist in this ROM build (fd 1/2 are not capturable) - a task error\n" \
    "# then crashes the handler itself and poisons every later pump tick.\n" \
    "# Route task errors to the captured stdout instead (setattr on the loop\n" \
    "# instance is allowed).\n" \
    "def __harness_task_error__(ctx):\n" \
    "    try:\n" \
    "        print('[asyncio task error]', ctx.get('exception', ctx.get('message', ctx)))\n" \
    "    except Exception:\n" \
    "        pass\n" \
    "\n" \
    "def __guard(p):\n" \
    "    s = str(p)\n" \
    "    if '..' in s:\n" \
    "        raise ValueError('path escapes the python sandbox: ' + repr(s))\n" \
    "    return p\n" \
    "\n" \
    "_open = open\n" \
    "def open(file, *a, **k):\n" \
    "    return _open(__guard(file), *a, **k)\n" \
    "\n" \
    "def __wrap(mod, fname):\n" \
    "    _f = getattr(mod, fname)\n" \
    "    def w(p, *a):\n" \
    "        return _f(__guard(p), *a)\n" \
    "    setattr(mod, fname, w)\n" \
    "for _n in ('listdir', 'mkdir', 'remove', 'rmdir', 'rename', 'stat', 'unlink', 'chdir'):\n" \
    "    try:\n" \
    "        __wrap(os, _n)\n" \
    "    except AttributeError:\n" \
    "        pass\n" \
    "\n" \
    "_loop = asyncio.new_event_loop()\n" \
    "_loop.call_exception_handler = __harness_task_error__\n" \
    "\n" \
    "def __harness_pump__(timeout_ms):\n" \
    "    async def _idle():\n" \
    "        await asyncio.sleep_ms(timeout_ms)\n" \
    "    _loop.run_until_complete(_idle())\n" \
    "\n" \
    "def plan(coro):\n" \
    "    return _loop.run_until_complete(coro)\n" \
    "\n" \
    "def every(seconds, coro_fn):\n" \
    "    async def _runner():\n" \
    "        while True:\n" \
    "            await asyncio.sleep_ms(int(seconds * 1000))\n" \
    "            _loop.create_task(coro_fn())\n" \
    "    _loop.create_task(_runner())\n" \
    "\n" \
    "_raw = harness._call_tool_raw\n" \
    "# Result of call_tool(). Subtlety: a plan may write `await call_tool(...)`\n" \
    "# even though the call is synchronous. On await MicroPython does a plain\n" \
    "# yield-from over the value; over a plain dict that yields its keys, which\n" \
    "# the scheduler ignores - the task is then never resumed and the service\n" \
    "# dies with no error anywhere. Raising StopIteration(self) from __next__\n" \
    "# makes the await expression evaluate to the result dict itself instead.\n" \
    "class _ToolResult(dict):\n" \
    "    def __iter__(self):\n" \
    "        return self\n" \
    "    def __next__(self):\n" \
    "        raise StopIteration(self)\n" \
    "def call_tool(name, args=None):\n" \
    "    raw = _raw(name, json.dumps(args or {}))\n" \
    "    try:\n" \
    "        return _ToolResult(json.loads(raw))\n" \
    "    except Exception:\n" \
    "        return _ToolResult({'ok': False, 'error': str(raw)})\n" \
    "\n" \
    "def __harness_status__():\n" \
    "    return {'heap_free': gc.mem_free(), 'heap_alloc': gc.mem_alloc()}\n"

#endif // MICROPY_INCLUDED_HARNESSDROID_MICROPYTHON_BOOTSTRAP_H
