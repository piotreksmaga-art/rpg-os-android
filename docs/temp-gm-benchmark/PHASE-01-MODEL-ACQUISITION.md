# Phase 01 — Model Acquisition

Status: **PASS**

## llama.cpp

- Revision: `9b05354ec6fb58b4e665e9a39ebc40285c015638`
- Build target: Android aarch64
- Compiler: Clang 21.1.8
- CPU backend enabled
- Vulkan disabled
- CUDA disabled
- BLAS disabled
- OpenMP disabled
- `llama-cli`: PASS
- `llama-server`: PASS

## Model A — LLAMA_3_2_3B

- Family: Llama 3.2
- Variant: 3B Instruct
- Format: GGUF
- Quantization: Q4_K_M
- File: `Llama-3.2-3B-Instruct-Q4_K_M.gguf`
- Observed local size: ~1.9 GiB
- SHA-256 expected: `6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff`
- SHA-256 observed: `6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff`
- Checksum: PASS

## Model B — QWEN3_4B

- Family: Qwen3
- Variant: 4B Instruct 2507
- Format: GGUF
- Quantization: Q4_K_M
- File: `Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf`
- Observed local size: ~2.4 GiB
- SHA-256 expected: `2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e`
- SHA-256 observed: `2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e`
- Checksum: PASS

## Storage boundary

Both model binaries are stored outside the RPG OS Git working tree under the Termux TEMP GM workspace. Combined observed model directory size was ~4.3 GiB after download. GGUF binaries are not to be committed.

## Phase verdict

**PASS** — both selected A/B model files were acquired and checksum-verified before inference testing.
