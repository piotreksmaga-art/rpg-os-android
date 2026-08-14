# Phase 02 — Initial Device Baseline / Model Load

Status: **PARTIAL — LOAD TESTS COMPLETE, PERFORMANCE SUITE PENDING**

## Test conditions

- Device: Samsung Galaxy S24 SM-S921B
- Android: 16
- CPU backend
- Context: 4096
- Threads: 4
- ChatGPT and Termux remained active as realistic background load
- RPG OS was not running for the initial standalone load comparison

## Llama 3.2 3B

- Model load: PASS
- Load time: 4462 ms
- VmRSS after load: 2,439,504 kB
- VmHWM: 2,441,112 kB
- Runtime threads: 17
- Battery temperature before: ~35.9 C
- Battery temperature immediately after load: ~35.9 C
- Battery temperature after stop: ~36.4 C
- Approximate additional swap observed during initial load: ~123 MiB
- Health endpoint: PASS

## Qwen3 4B Instruct 2507

- Model load: PASS
- Load time: 6826 ms
- VmRSS after load: 3,073,312 kB
- VmHWM: 3,076,008 kB
- Runtime threads: 17
- Battery temperature before: ~35.7 C
- Battery temperature immediately after load: ~36.1 C
- Approximate additional swap observed during initial load: ~936 MiB
- Health endpoint: PASS

## Wireless ADB recovery

Initial connection `192.168.50.192:42801` became stale/offline during testing. Current Wireless Debugging endpoint changed to `192.168.1.237:46667`.

Recovery verification:

- connection state: `device`
- `ADB_OK`
- model: `SM-S921B`
- Android: `16`

This endpoint is runtime/device state and must not be treated as a permanent configuration value.

## Interpretation

Both models fit and load on the device at context 4096 under CPU-only execution. Qwen shows materially higher load latency, resident high-water memory and swap pressure than Llama in the initial load test. No winner is selected from this phase; quality, stability and canonical-state discipline remain primary criteria.
