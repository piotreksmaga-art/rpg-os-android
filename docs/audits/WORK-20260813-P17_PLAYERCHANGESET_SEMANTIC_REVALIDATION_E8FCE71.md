# WORK-20260813 — Phase 17 PlayerChangeSet Semantic Revalidation

Role: CHAT-2 — Semantic Auditor
Validated runtime SHA: `e8fce7187a92ffee846b9f60b06809343051a045`
Fresh master at validation start: `e8fce7187a92ffee846b9f60b06809343051a045`
Runtime changed after target: NO

## Verdict

`PHASE 17 SEMANTIC REVALIDATION: PASS`

No release-blocking semantic defect was found in the exact Phase-17 runtime candidate.

## Composite target identity hardening

Production replaces delimiter-concatenated conflict identities with a shared `compositeConflictKey(discriminator, components...)` for all actual composite semantic target families in the registry: STAT, RESOURCE, SKILL, TECHNIQUE, INNATE, INVENTORY, EQUIPMENT, ASSET, OWNED_ASSET (OwnershipChange), CONDITION and RUNTIME.

The function retains the legacy discriminator-separated form only when every component after the first is colon-free. This remains injective even when the first component contains colons because the remaining fixed number of colon-free suffix components can be uniquely recovered from the right. If any later component contains `:`, the function switches to CK1 length-prefixed representation containing discriminator length/value, component count, and each component length/value.

The legacy and CK1 namespaces cannot collide: legacy keys begin with the fixed family discriminator plus `:`, whereas hardened keys begin with `CK1|`. Different families cannot collide in either representation because the discriminator is explicit. Representation selection depends only on the tuple content and therefore the same target deterministically receives the same key.

Independent inspection found no false-positive or false-negative collision for colons, pipes, backslashes, whitespace, Unicode, multiple delimiters, encoded-looking substrings, or unequal component lengths. Same tuple still produces the same conflict identity and therefore conflicts as required.

Single-component identities such as Financial account conflicts, ownershipRecordUid and DevelopmentProject UID remain prefix-separated and do not require composite encoding.

## Earlier Phase-17 hotfixes

Asset identity remains the full `OwnedAssetRef(assetKindUid, assetUid)` across model, codec, conflict semantics, round-trip and fingerprint.

Financial/ledger consistency remains fail-closed: causal financial ledger terms must exactly match fromAccountUid, toAccountUid, amountMinor, currencyUid and transactionTypeUid. A single FinancialChange UID cannot be represented by multiple causal financial ledger intents. Independent FinancialChanges remain legal, standalone ledger proposals remain legal, and dangling/non-financial causal references remain rejected.

## PlayerChangeSet semantic boundary

PASS for immutable defensive collections, typed domain changes, universe-agnostic Core, proposal-only/non-authoritative meaning, stable UID/reference semantics, no StatePatch authority, no DB writer, no direct commit, no persistence authority, deterministic order/conflict semantics, exact Long/fixed-scale ownership proposal values, and Phase 3–16 separation.

Construction, validation, conflict-key derivation, encode, decode, fingerprint and identity comparison remain read-only with respect to authoritative state.

## Serialization / identity

Canonical legal encode -> decode -> encode remains deterministic. Conflict-key hardening does not alter canonical payload serialization: conflict keys are derived validation metadata, not serialized domain facts. Therefore legal semantic payload identity/fingerprint changes only when the ChangeSet semantic content changes.

Strict serialization protections from earlier runtime remain active: unknown fields, duplicate object keys including escaped-equivalent keys, wrong String/numeric scalar types, quoted numeric coercion, unsupported schema version, unknown change kind, payload mismatch and malformed references fail closed.

## Regression suite

The new composite conflict regression suite covers all composite families listed above, the original STAT delimiter alias, same-tuple conflicts, legacy alias shapes, asset Hotfix3 alias, Unicode/pipes/backslashes/spaces, full OwnedAssetRef round-trip, financial Hotfix2 uniqueness, deterministic canonical serialization/fingerprint, zero DB mutation and representative Phase 3–16 regression.

## Full JVM / CI

A local `./gradlew :app:testDebugUnitTest` run was attempted, but this audit environment cannot resolve `github.com`, so the repository could not be cloned locally. This is an environment limitation, not a test failure.

Exact CI was independently verified instead:

- GitHub Actions run #371
- run ID `31666619184`
- head SHA `e8fce7187a92ffee846b9f60b06809343051a045`
- conclusion `SUCCESS`
- Validate project: SUCCESS
- full JVM unit tests: SUCCESS
- signed ALPHA APK: SUCCESS
- release preparation: SUCCESS
- Actions artifact upload: SUCCESS
- existing release asset update: SUCCESS
- overall workflow: SUCCESS

## Final gate summary

- Composite target semantics: PASS
- Legacy/CK1 separation: PASS
- Asset identity: PASS
- Financial/ledger semantics: PASS
- Immutability: PASS
- World-agnostic boundary: PASS
- Proposal-only boundary: PASS
- Serialization: PASS
- Fingerprint: PASS
- Numeric safety: PASS
- Zero authoritative mutation: PASS
- Phase 3–16 regression: PASS
- Full JVM: PASS via exact CI evidence
- Exact CI: PASS

NEW BLOCKERS: NONE

Phase 17 is not globally accepted by this report alone. Phase 18 remains blocked until the independent CHAT-2/CHAT-3/CHAT-5 acceptance condition is satisfied for the exact same runtime SHA.
