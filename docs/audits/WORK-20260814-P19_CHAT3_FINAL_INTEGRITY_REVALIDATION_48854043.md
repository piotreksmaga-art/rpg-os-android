# WORK-20260814 — PHASE 19 CHAT-3 FINAL INTEGRITY / AUTHORITY-BOUNDARY REVALIDATION

ROLE: CHAT-3 — Independent Integrity Auditor

VALIDATED RUNTIME SHA: `48854043bdde9753830ffc20ff6a8e8a4d4299e1`

AUDIT ONLY. No production/test modification. No fixes. Phase 20 not started.

## Verdict

**PHASE 19 INTEGRITY REVALIDATION: FAIL**

One authority-boundary blocker was found: a supported normal caller can construct `PlayerResolutionContext` with `worldPackBinding = null`, and `PlayerDomainEngine.evaluateWorldRules()` treats null as an unconditional no-rule return without independently binding the context to the campaign's active World Pack. Therefore the engine itself cannot distinguish legitimate legacy/generic no-rule mode from an active-World-Pack campaign whose caller omitted/nullified the binding.

## 1. Target pinning

Fresh master at the initial audit gate was one commit ahead of target. `48854043..master` changed only `docs/audits/WORK-20260814-P19_CHAT1_WORLDRULEPROVIDER_IMPLEMENTATION_48854043.md`. Target is the merge base/ancestor. No production/test/runtime file changed after target.

**RUNTIME CHANGED AFTER TARGET: NO.**

## 2. Registry integrity

`WorldRuleProviderRegistry` defensively copies the input list and builds immutable map/set snapshots. Selection is by exact `worldPackUid`; exact `worldPackVersion` is then checked. Duplicate registrations for the same World Pack UID fail `DUPLICATE_WORLD_RULE_PROVIDER`, including same provider repeated and same World Pack UID with different versions. Source-list mutation after construction cannot alter registry behavior. Registration ordering does not select an arbitrary provider because duplicates fail and lookup is keyed.

Provider UID is recorded in decision identity but is not a registry key. Two providers with different World Pack UIDs may share a provider UID. I do not classify that alone as an integrity failure because selection authority is explicitly World Pack identity/version and decision identity also includes World Pack identity/version. Duplicate provider UID for the same World Pack cannot survive the duplicate World Pack key gate.

**REGISTRY INTEGRITY: PASS.**

## 3. Provider retained-state security

`validateProviderState()` walks the subclass hierarchy up to `WorldRuleProvider`, examines non-static fields, requires `final`, and only accepts primitive, enum, String and selected boxed scalar types. This rejects direct/inherited non-final counters and rejects retained collections, arrays, Atomic types, database/writer objects, and nested mutable objects even when final. Safe final scalar/String/enum configuration is accepted. This is appropriate for the declared trusted internal extension model and does not claim a JVM bytecode sandbox.

**PROVIDER STATE SECURITY: PASS.**

## 4. Request immutability and command detachment

Engine canonicalizes the incoming command through registry encode/decode before rule evaluation. It creates another encode/decode copy for the provider, records the canonical command fingerprint, and verifies the provider copy after evaluation. Caller-owned original command/payload mutation cannot retroactively change the already canonicalized provider request. Provider mutation of the supplied command is detected as `WORLD_RULE_PROVIDER_INPUT_MUTATED`.

`WorldRuleEffectSnapshot` freezes its top-level lists; current payload models are value-oriented typed payloads, and the engine fingerprints the effect snapshot before and after provider invocation. Decision evidence is defensively copied and decision records sort/copy evidence before storage.

**REQUEST IMMUTABILITY: PASS.**

**COMMAND DETACHMENT: PASS.**

## 5. Effect snapshot identity

Final target no longer uses payload `toString()`. Canonical effect fingerprint explicitly serializes every current `PlayerDomainChange` family and the current event/ledger/warning families.

High-risk tuples are preserved:

- asset: `assetKindUid + assetUid`;
- ownership: ownership record plus asset kind/uid, from-owner kind/uid, to-owner kind/uid, share units;
- finance: source, destination, amount, currency, transaction type;
- project: project UID, result kind, progress units, each evidence DomainRef kind+uid;
- equipment: subject, slot, operation, item instance;
- skill/technique: subject, typed UID and exact delta;
- condition/runtime: subject plus condition/runtime identity and operation/delta.

Event intent identity includes actor, targets, causal change UIDs, effective order and payload. Ledger identity includes causal changes and all five financial transfer terms.

P19-29/30 independently allocate equivalent project drafts and verify equal semantic fingerprints, then alter evidence and verify inequality.

**EFFECT SNAPSHOT INTEGRITY: PASS.**

## 6. Canonical delimiter safety

Canonical rule identity uses explicit length-prefixed tokens: `<length>:<value>|`. Structural fields are emitted separately. Embedded `:`, `|`, backslash, Unicode, whitespace, numeric-looking prefixes, and separator-looking content remain inside a token's declared length and cannot shift field boundaries. Domain refs encode kind and UID as separate tokens. Nullable refs have explicit null/non-null markers. This is injective for the represented ordered field sequence subject to SHA-256 collision resistance.

**CANONICAL IDENTITY / DELIMITER SAFETY: PASS.**

## 7. Decision record integrity / stale reuse

Core constructs `WorldRuleDecisionRecord`; provider cannot directly supply a record. Fingerprint includes provider UID/version, World Pack UID/version, stage, request fingerprint, rule UID, allow/reason identity and sorted evidence UIDs. Evidence order is canonicalized, so semantically equivalent order-insensitive evidence produces the same identity while content changes do not.

Request fingerprint includes campaign, actor, command identity/fingerprint, context fingerprint, stage, World Pack UID/version and draft effect fingerprint. A record therefore cannot be replay-equivalent across different command, actor, campaign, World Pack version, stage, or different draft effect through the normal record-construction path. Records are not accepted back into the engine as authorization inputs.

**DECISION RECORD INTEGRITY: PASS.**

**STALE DECISION RESISTANCE: PASS.**

## 8. NULL binding bypass — BLOCKER

`PlayerResolutionContext.create(...)` is public and accepts `worldPackBinding: WorldPackRuleBinding? = null`.

`PlayerDomainEngine.evaluateWorldRules()` begins with:

`val binding = context.worldPackBinding ?: return null`

There is no independent campaign/active-World-Pack authority argument or lookup at the engine boundary that verifies whether null is legal for this campaign. The engine therefore trusts the caller to truthfully say whether World Pack rules are active.

The Phase-19 tests explicitly demonstrate the supported null path: P19-28 constructs a normal `PlayerDomainEngine` and resolves successfully with `context(worldRules = false)`, where the helper supplies null binding.

Legacy/generic no-rule mode can be a legitimate mode. The blocker is not the existence of that mode; it is the lack of an integrity constraint that prevents the same supported public context construction from being used for an actually active-rule campaign. The canonical engine cannot distinguish the two states and therefore does not itself fail closed on omission/removal of an active binding.

This violates the requested authority-boundary property for active World Pack mode.

**NULL BINDING BYPASS: FAIL.**

## 9. Authority boundary and failure atomicity

Provider/request/registry supported fields expose no SQLiteDatabase, DAO writer, repository/store writer, ledger writer, StatePatch, TurnTransaction or commit callback. Provider returns only typed legality decision, not a proposal or mutation capability.

Engine ordering is command structural/canonical validation -> context identity -> Phase-18 command references -> command rule precheck -> component -> mutation guard -> Phase-18 draft references -> draft rule check -> engine proposal assembly -> final ChangeSet validation. Thus reference failures occur before the corresponding provider stage.

Rule rejection returns no proposal. Missing provider/version mismatch/duplicate registry/provider exception/malformed decision are structural failures before proposal escape. Draft rejection occurs before proposal assembly. Within the supported capability model no authoritative writer is exposed, and existing authority-value tests confirm zero mutation for provider rejection/fault. The null-binding blocker remains a legality bypass, not a demonstrated mutation primitive.

**AUTHORITY BOUNDARY: PASS.**

**FAILURE ATOMICITY: PASS.**

**PROVIDER FAULT HANDLING: PASS.**

**ZERO AUTHORITATIVE MUTATION: PASS.**

## 10. Reference ordering / Phase-18 locks

P19-04 and P19-05 use a provider that throws if invoked and verify UNKNOWN_REFERENCE / WRONG_CAMPAIGN_REFERENCE return with no rule decisions. Production ordering confirms command reference validation precedes command precheck. Draft reference validation precedes draft provider evaluation.

Phase-18 classification locks remain: equipment slot B definition identity; ownership record D local identity; asset/fromOwner/toOwner A full typed references; finance account/currency references remain typed and campaign-scoped.

**PHASE-18 REFERENCE ORDERING: PASS.**

## 11. Proposal identity and determinism

`assembleProposal()` derives `changeSetUid` from canonical encoded command, context fingerprint, component identity/version and each decision fingerprint in evaluation order. Same command/context/component/decisions gives the same proposal UID. Meaningful decision differences change decision fingerprints and therefore proposal identity. Stage is included in decision identity, so command/draft decisions cannot collide merely by sharing rule/reason/evidence. Evidence is sorted before decision fingerprinting, making evidence ordering irrelevant where the decision contract treats it as a set.

**PROPOSAL IDENTITY: PASS.**

**DETERMINISM: PASS.**

## 12. PlayerChangeSet schema

Phase 19 does not change the PlayerChangeSet schema/codec. Existing `ChangeSetProvenance.worldRuleProviderUid` is populated from rule decisions; transient `WorldRuleRequest`, effect snapshots and full decision records do not leak into serialized proposal schema. Existing serialization/fingerprint roundtrip regression remains green in exact CI.

**PLAYERCHANGESET SCHEMA: PASS.**

## 13. Numeric / Phase-17 and Phase 3–18 locks

Representative focused tests retain:

- ExactLongDelta zero rejection;
- ProjectProgressDelta zero legality;
- OwnershipShare full exact scale;
- composite conflict identity delimiter hardening;
- full OwnedAssetRef kind+UID identity;
- exact five-field finance/ledger matching;
- PlayerChangeSet serialization and fingerprint roundtrip.

The complete canonical JVM task also passed in exact CI at target.

**PHASE 3–18 REGRESSION: PASS.**

## 14. Test quality

`WorldRuleProviderPhase19Test` contains P19-01..28 and `WorldRuleProviderDeterminismRegressionTest` adds P19-29/30. The key ordering tests use an exploding provider, so they cannot pass if provider invocation occurs early. Rule rejection/fault tests distinguish typed rejection from structural failure. P19-29/30 compare production fingerprints of independently allocated drafts and do not compute an expected hash with a copied production encoder.

The main Phase-19 test uses Robolectric because its zero-mutation fixture uses Android SQLite; this is intentional rather than an ordinary-JVM Android API accident. No evidence of disabled P19 tests was found in the inspected suite.

Coverage is not complete for every adversarial case named in this audit prompt (for example explicit delimiter strings and every retained-state shape are stronger static implications than dedicated P19 tests), but production semantics are sufficiently inspectable for those PASS conclusions. The null-binding bypass is not covered as a failure because P19-28 intentionally treats null as legal generic mode.

**TEST QUALITY: PASS.**

## 15. Exact CI

GitHub Actions run #452 / ID `31801538074`:

- head SHA: `48854043bdde9753830ffc20ff6a8e8a4d4299e1`;
- status: `completed`;
- conclusion: `success`;
- Validate project: success;
- Run JVM unit tests: success;
- Build signed ALPHA APK: success;
- Prepare release files: success;
- Upload Actions artifact: success;
- existing GitHub Release asset update: success.

No local JVM rerun was performed by CHAT-3.

**FULL JVM: NOT-RUN** locally.

**EXACT CI: PASS.**

## New blockers

1. **ACTIVE-WORLD-PACK NULL-BINDING AUTHORITY BYPASS** — `PlayerResolutionContext.create()` permits a normal supported caller to provide `worldPackBinding = null`, and `PlayerDomainEngine` interprets null as no rules without independently verifying campaign active-World-Pack state. A caller can therefore suppress provider evaluation for an active-rule campaign unless an external layer enforces a constraint not present at this engine boundary.

## Final CHAT-3 verdict

**FAIL**.

This report does not globally accept Phase 19. Phase 20 remains blocked.
