# CHAT-7 TEMP GM LIVE SEMANTIC RETEST #2 — manual review

Date: 2026-08-16
Work item: WORK-20260815-001
Semantic branch tested: `chat7-temp-gm-semantic-hardening`
Required semantic HEAD: `9d90148ac34db9ac024e2163aaa19ef7934fa082`
Device evidence commit: `260fa86f3f0f9722af625567c7d5a3e13f4fc523`
Device evidence path: `temp-gm/results/device/2026-08-16_09-08-50-chat7-live-semantic-retest2/`

## Preflight
- Provider: `BIELIK_4_5B_V3`
- Provider state: READY
- llama.cpp endpoint: 127.0.0.1:8768
- bridge endpoint: 127.0.0.1:8765
- provider timeout: 180 s (unchanged)
- Android timeout: 210 s (unchanged)
- maxTokens: 1024 (unchanged)

## Mechanical results
- HTTP 200: 9/10
- canonical safety on successful responses: 9/9 (`canonicalMutation=false`, no forbidden mutation fields)
- natural stops: 7/10
- max-token stops: 2/10
- one request failed at ~180.176 s with bridge HTTP 502, matching the provider's 180 s generation boundary and therefore reproducing the provider-timeout defect in live runtime.
- generation time among successful requests: min 27.167 s, median 72.839 s, max 142.106 s
- effective output throughput among successful requests: min 5.146 tok/s, median 6.786 tok/s, max 7.331 tok/s

## Strict semantic verdict
LIVE SEMANTIC = FAIL.

The strict task policy requires 10/10 semantic PASS and zero internal/test-label leakage. The run fails immediately because cases 01-08 contain `TEST_FAILURE`; case 07 also contains `TEST_FALLBACK`. These are explicit forbidden internal/test labels, so INTERNAL_CONTEXT_ISOLATION fails regardless of whether individual narrative sentences otherwise preserve player agency.

Examples from raw evidence:
- CASE 01 repeats `TEST_FAILURE` and reaches 1024 output tokens instead of producing concise player-facing narration.
- CASE 02 contains repeated `TEST_FAILURE` blocks.
- CASE 03 reaches 1024 output tokens and contains `TEST_FAILURE`.
- CASE 04-08 also contain `TEST_FAILURE`; CASE 07 additionally contains `TEST_FALLBACK`.
- CASE 09 is the only successful response with no mechanical leak marker.
- CASE 10 fails at ~180.176 s with HTTP 502 before a semantic response is available.

Because a single clear internal-context/test-label leak is a semantic FAIL, the aggregate result cannot be READY FOR CHAT-6.

## Timeout implication
This run also provides direct live evidence that 180 s is not a sufficient provider timeout for all current maxTokens=1024 TEMP requests: CASE 10 failed at approximately the provider boundary. Do not ask CHAT-6 to increase Android timeout in isolation. A coherent provider/client/output contract still must be selected after semantic behavior is stabilized.

No timeout, maxTokens, Android, Phase 19, Phase 20+, runtime model parameters, or canonical state logic were changed as part of this review.
