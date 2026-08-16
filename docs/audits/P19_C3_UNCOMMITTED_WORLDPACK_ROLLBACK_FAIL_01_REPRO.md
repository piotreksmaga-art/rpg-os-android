# P19-C3-UNCOMMITTED-WORLDPACK-ROLLBACK-FAIL-01 — deterministic reproduction

Status: PRE-FIX REPRODUCED

Rejected baseline SHA: `de76637abb15210d5fd3078bb42da5374a08260b`

Reproducer branch: `chat1/p19-uncommitted-worldpack-repro`

Reproducer head: `09bcd8374ecddd7ce853f289e0f4858a60381492`

GitHub Actions run: `31943397657`

Result: `SUCCESS`.

The reproducer succeeds only when the exact vulnerable production-path sequence is observed:

1. committed World Pack `WORLD-A@1` occupies canonical live target;
2. prepared `WORLD-A@2` is activated;
3. `afterActivation` / persistence callback throws;
4. rollback attempts canonical A2 -> `.failed-*`;
5. injected production file-op returns `false` for that quarantine rename;
6. replacement returns rollback failure while canonical live target remains valid `WORLD-A@2` and `.rollback-*` retains committed A1;
7. a fresh `CanonicalSelectionWorldPackAuthoritySource.currentAuthority()` observation returns `WORLD-A@2`.

Therefore on the rejected SHA a valid but UNCOMMITTED failed-new World Pack can become canonical Phase-19 authority.

This finding is narrowly in Phase-19 scope because it directly violates the canonical authority invariant. It does not reclassify general crash recovery, Snapshot, Save/Load, Backup, Branching, `createCampaign()` snapshotting or RestoreManager synchronization.
