# CHAT-7 Qwen3.5 thinking test without request max_tokens

Date: 2026-08-15
Device: Samsung Galaxy S24 SM-S921B, Android 16, 8 GB RAM
Runtime: llama.cpp CPU
Model: Qwen3.5-4B Q4_K_M
Context configured on llama-server: 4096

## Result

Status: FAIL FOR INTERACTIVE RPG USE / TIMEOUT

The client intentionally omitted `max_tokens` to observe the model's default thinking behavior. `curl` returned code 28 (timeout). Measured elapsed time reported by the shell was 1,153,128 ms (~19.2 min). Because the request used non-streaming output and timed out, no response JSON file was produced.

llama.cpp log evidence shows the model continued generating internal output up to approximately `n_gen = 2907` tokens before the task was cancelled. Average generation rate degraded from about 3.7 tok/s to about 3.3 tok/s, with severe late-run short-window slowdowns below 1 tok/s. The server logged `cancel task, id_task = 223` after the client timeout/cancellation.

## Interpretation

The earlier `/gm/turn` test with `max_tokens=220` returned HTTP 200 and `canonicalMutation=false`, but an empty `narrative`, while usage showed all 220 completion tokens consumed. The no-`max_tokens` test demonstrates that default thinking can continue for thousands of generated tokens and is unsuitable as the default interactive TEMP GM mode on this device.

This is not classified as a model-load failure. The model remained running and generating. It is a practical latency/response-mode failure for interactive RPG use.

## Next test

Run an otherwise equivalent direct chat-completions request with Qwen3.5 thinking explicitly disabled (`chat_template_kwargs.enable_thinking=false`) and measure:
- visible Polish content,
- completion tokens,
- total latency,
- generation rate,
- stability.

No canonical RPG OS mutation occurred.
