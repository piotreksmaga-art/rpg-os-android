# CHAT-7 semantic runtime failure + timeout measurement

Date: 2026-08-16
Work item: WORK-20260815-001
Device evidence commit: `ee8656cc620dca1e09389d5e5325a67ecab6fcad`

## Executed contract tests
`GM_SEM_01..10` prompt/contract assertions executed: **10/10 PASS**.

## Live model review
The corresponding live Bielik outputs are **NOT a semantic PASS**.

Observed problems include:
- repeated/meta `TEST_FALLBACK` / `TEST_FAILURE` text instead of clean narration;
- GM_SEM_08 invented an undeclared PLAYER teleport action while the player only observed a closed door — direct PLAYER_AGENCY_VIOLATION;
- GM_SEM_09 leaked/echoed internal read-only context rather than narrating the NPC response;
- several outputs repeated contradictory alternative NPC outcomes and diagnostic-looking text.

ACTION_DIRECTION_REVERSAL was not reproduced in the exact GM_SEM_01 fixture: PLAYER remained the attacker and NPC reacted by dodging. However the player-agency invariant is still failing globally because of GM_SEM_08.

Per task rule, physical CHAT-6 semantic retest is blocked until the semantic prompt/contract is hardened again.

## Timeout measurements under unchanged contract
Current provider timeout: 180 s
Current Android timeout: 210 s
Current maxTokens: 1024

Measured runs:
1. prompt=993, completion=67, elapsed=12.174 s, effective completion rate=5.50 tok/s, natural end, no timeout.
2. prompt=1010, completion=1024, elapsed=127.846 s, effective completion rate=8.01 tok/s, hit maxTokens, no timeout.
3. prompt=1009, completion=177, elapsed=25.124 s, effective completion rate=7.05 tok/s, natural end, no timeout.

The three controlled runs do not reproduce the 180 s timeout. One request reached the full 1024-token limit in 127.846 s. This shows 180 s is sufficient for the measured distribution, but it does not prove sufficiency at the separately observed lower ~4 tok/s tail. At 4 tok/s, 1024 generated tokens alone are ~256 s before prompt/transport overhead.

## Timeout contract decision status
No timeout/provider code is changed in this commit because semantic live validation failed and the task requires fixing only semantic prompt/contract before another physical retest.

Provisional smallest stable direction after semantic PASS: retain provider 180 s + Android 210 s and cap TEMP narrative output below 1024 only if a shorter output budget is justified by the hardened stop-point semantics and a follow-up measurement. No output cap has been implemented yet.

## Semantic hardening v2
The prompt is simplified and hardened to:
- USER-only PLAYER action source;
- exact actor/action/target preservation;
- NPC autonomy preserved;
- no invented capabilities/actions;
- no internal context/system/test-label leakage;
- 2–5 short sentences by default;
- explicit stop before next player turn;
- canonicalMutation remains false.

Next step: run the updated semantic branch on Bielik, inspect live GM_SEM outputs, and only if they pass proceed to CHAT-6 physical retest and finalize the timeout/output contract.
