Title: SIGSEGV in libggml during ACH summarization (com.tree4five.gguf)

Summary
- Crash: native SIGSEGV (SEGV_MAPERR) observed inside `libggml.so` during model inference; crash site: `ggml_vec_dot_q5_0_q8_0` called from `ggml_graph_compute_thread` (OpenMP path).
- Device: Samsung (SM-P610-ish), Android 13 build `TP1A.220624.014`.
- Affects: `com.tree4five.gguf` LLM provider used by `harnessDroid` during ACH compression and normal inference.

Key evidence (collected on host)
- Extracted crash block: `/tmp/ggml_crash_block.txt` (includes backtrace and registers)
  - Example frames:
    - `signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)`
    - `#00 ... libggml.so (ggml_vec_dot_q5_0_q8_0+76)`
    - `#01 ... libggml.so (ggml_graph_compute_thread+66036)`
    - `#02 ... libggml.so (.omp_outlined.+116)`
    - `#03 ... libomp.so (__kmp_invoke_microtask+152)`
- Full device log: `/tmp/full_log.txt`
- Crash buffer: `/tmp/crash_log.txt`
- Collected bugreport: `/tmp/bugreport.zip` (full system state, logs, tombstones where available)

Memory snapshot (dumpsys meminfo) at time of crash / reproduction
- `com.tree4five.gguf` (pid 24258):
  - TOTAL PSS: ~977,746 KB (~978 MB)
  - Native Heap: ~176,567 KB
  - SwapPss: ~310,115 KB
  - Other mmap: ~473,374 KB
- `com.ai.harnessdroid` (pid 24076):
  - TOTAL PSS: ~269,984 KB
  - Native Heap: ~1,820 KB

What I tried already
- Reproduced crash via `./run_remote_test.sh` (instrumentation run) — instrumentation reports `Process crashed.` in `RealLLME2ETest`.
- Re-ran remote tests under `HARNESS_USE_MOCK_LLM=1` (mock LLM) — harness logic passes but does not trigger provider crash.
- Disabled ACH compression via `HARNESS_COMPRESSION_STRATEGY=none` and lowered ACH chunking to `HARNESS_ACH_MAX_CHUNK_CHARS=2000` — crash still occurred with real provider.
- Attempted to set CPU affinity with `taskset` on device PID — permission denied (no root).
- Collected `adb bugreport` to `/tmp/bugreport.zip` and saved `/tmp/ggml_crash_block.txt`, `/tmp/full_log.txt`, `/tmp/crash_log.txt`.

Minimal reproduction steps (recommended for provider team)
1. Install the provider APK used in test (the one on device) and `harnessDroid` APK.
2. On the same device model or similar memory-constrained device, run the harness instrumentation that performs ACH summarization and inference: `./run_remote_test.sh` (this installs provider + app + test apk and runs `RealLLME2ETest`).
3. Observe native crash in provider; check `logcat -b crash` and tombstones.

Suggested hypotheses
- libggml vectorized dot routine for quantized formats (`q5_0`/`q8_0`) dereferences invalid memory under multithreaded OpenMP execution (race, out-of-bounds, or use-after-free) — stack shows `.omp_outlined` and `libomp` involvement.
- Memory pressure on device may exacerbate the issue (large model mappings, swap usage); provider uses large native allocations and high swap PSS.

Suggested mitigations / next steps for provider maintainers
- Immediately: provide a low-memory / single-threaded provider build or accept `OMP_NUM_THREADS=1` env to limit OpenMP parallelism.
- Provide a provider build with ASAN/UBSAN-enabled native checks (if feasible) or enable additional runtime guards in `ggml_vec_dot_*` to validate indices/ptrs.
- Investigate `ggml_graph_compute_thread` code path and concurrency around quantized vector dot product for q5_0/q8_0; add bounds checks.
- If possible, add a graceful fallback to a safe non-quantized dot product or single-thread path on OOM or invalid mmap.

Attachments (on host)
- `/tmp/bugreport.zip` (full bugreport)
- `/tmp/full_log.txt` (full adb logcat dump)
- `/tmp/crash_log.txt` (crash buffer)
- `/tmp/ggml_crash_block.txt` (focused crash block)
- `/tmp/libggml_matches.txt` and `/tmp/libomp_matches.txt` (grep hits)

Contact & context
- Repo: harnessDroid (workspace at `/home/pc/sby/harnessDroid`)
- Test script used: `./run_remote_test.sh`
- Harness environment variables used during runs: `HARNESS_USE_MOCK_LLM`, `HARNESS_COMPRESSION_STRATEGY`, `HARNESS_ACH_MAX_CHUNK_CHARS`.

If you want, I can:
- Package a short issue body (GitHub) and attach `/tmp/bugreport.zip` and `PROVIDER_ggml_issue_REPORT.md` for filing with provider maintainers.
- Attempt root-required tombstone extraction (requires rooted device).
- Try launching provider with `OMP_NUM_THREADS=1` if the provider supports environment flags or a modified APK is provided.
