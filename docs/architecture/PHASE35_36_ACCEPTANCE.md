# Phase 35–36 Acceptance — Canon Divergence / Schema Versioning

Status: **ACCEPTED / COMPLETE**  
Coordinator acceptance date: **2026-08-19**  
Accepted runtime SHA: `7cb61d3cdc42e0c20f2688181d054e55eacbfd8f`

## Exact-SHA validation evidence

- Workflow: `Validate RPG OS ALPHA`
- Run ID: `32241299329`
- Job ID: `96032227097`
- Conclusion: `success`
- JVM unit tests: `success`
- Signed validation APK build: `success`
- Immutable validation artifact upload: `success`
- Artifact ID: `9361064715`
- Artifact name: `RPG-OS-VALIDATION-1.2.0-alpha5-hybrid145-7cb61d3cdc42e0c20f2688181d054e55eacbfd8f`
- Artifact digest: `sha256:73da8802468e0302c5f4548bda9a2240d5c44a17ceed209b27e10c6e11f84b90`

The validation checkout, JVM suite, signed APK and immutable artifact all refer to the same accepted runtime SHA.

## Phase 35 accepted boundary

Phase 35 — Canon Divergence is accepted with the following invariants:

- canon divergence is a typed, campaign-scoped durable record, separate from campaign truth and from World Pack source material;
- a normal gameplay divergence is recorded only through the canonical committed-turn mutation path and is bound to committed transaction, turn and event identity;
- canon-consistent outcomes do not create divergence records;
- rollback and idempotent retry do not create duplicate divergence history;
- stored divergence preserves the World Pack UID/version and expected-vs-actual values that applied when the divergence was committed;
- verified administrative import is recovery-only and does not fabricate unknown historical provenance;
- World Pack replacement or rollback cannot rewrite already committed divergence truth or canonical event history.

## Phase 36 accepted boundary

Phase 36 — Schema Versioning + migration safety + legacy provenance is accepted with the following invariants:

- persistent schema families have explicit current versions and readiness verification;
- unsupported future schema versions fail closed before migration mutation;
- migration ordering and plan fingerprinting are deterministic, with ambiguity/cycles rejected;
- migration attempts use durable PREPARED/RUNNING/APPLIED/FAILED evidence and interrupted-attempt recovery semantics;
- material migrations require a verified protected Phase-33 snapshot rather than silently mutating material state without recovery evidence;
- ordinary read/readiness paths remain side-effect free; migration is owned by the explicit administrative bootstrap boundary;
- legacy history that was never recorded remains unknown rather than receiving invented provenance;
- runtime initialization serializes recovery/migration against gameplay turn commits and revalidates mutation/evidence guards before the campaign is considered ready.

## Post-audit hardening accepted

The accepted runtime includes the Phase 30–36 post-audit hardening that verifies, among other boundaries:

- same-command retry returns the original receipt and does not duplicate events, causal relations, replay payloads or receipts;
- commit/replay evidence cannot be forged through raw or ordinary administrative SQL;
- the canonical `commitTurn` writer contract declares every current canonical sink family, including CANON_DIVERGENCE;
- recovery and gameplay turns are serialized in both directions by the campaign lifecycle lock;
- snapshot retention preserves non-empty canonical commit evidence;
- snapshot delete failure/orphan reconciliation remains campaign scoped;
- World Pack replacement and failed compatibility rollback preserve committed divergence, campaign truth and events.

## Coordinator disposition of superseded work

PR #51 (`6e60624dd4c138e0553fa21ad06ebda09439f5ea`) was closed without merge as superseded. Its branch was an older diverged implementation line and must not be resurrected or force-merged into the accepted runtime.

## Next gate

The next implementation block is **Phase 37 — NPC Knowledge model + acquisition provenance**.

Phase 37 must start with the canonical sequence:

`READ FULL ARCHITECTURE + MAPA PLIKÓW -> AUDIT FIRST -> classify COMPLETE / PARTIAL / MISSING / BLOCKED -> minimal implementation -> targeted tests -> compatibility -> full JVM -> PR -> exact-SHA CI -> coordinator acceptance`

This acceptance record does not itself start Phase 37 and does not change the rule that later phases become COMPLETE only after exact-SHA coordinator acceptance.
