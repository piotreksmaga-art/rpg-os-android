# AB-01 — Polish General Prose — QWEN3_4B

Status: **PASS**

## Prompt intent

Generate a short Polish RPG scene about a young shinobi at dawn near a wet forest. The model was instructed not to resolve the situation for the player and not to add statistics.

## Token usage

- prompt tokens: 150
- completion tokens: 220
- total tokens: 370

## Manual quality assessment

- POLISH: 9/10
- NARRATIVE: 8/10
- COHERENCE: 9/10
- CONTEXT OBEDIENCE: 9/10

Canonical-state and NPC-knowledge categories are intentionally not scored from AB-01 because this scenario does not exercise them sufficiently.

## Observations

- Natural and coherent Polish prose.
- No switch to English.
- The scene remained unresolved for the player.
- No player statistics were invented.
- Minor stylistic awkwardness was visible but did not materially reduce readability.
- The generation reached the configured 220-token completion limit; later tests should check whether the model tends to consume the full output budget.

## Verdict

**PASS** for AB-01. This is not a model-selection decision; the remaining AB suite, device performance, 10-turn stability and RPG OS coexistence tests are still required.
