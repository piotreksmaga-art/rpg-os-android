# CHAT-5 — Phase 16 PlayerCommand Adversarial Oracle

Status: ADVERSARIAL MATRIX READY / FINAL VALIDATION PENDING

Repository: `piotreksmaga-art/rpg-os-android`
Role: READ-ONLY quality/adversarial auditor
Accepted Phase-15 runtime: `173e501fbe832980bb4eaf177c5ba34d93cd5f37`
Fresh master observed before report write: `9e3825e624bd108646aee01847d7f0f19d6dd20e`
Observed in-progress Phase-16 work: `WORK-20260810-067`
Architecture basis: `docs/audits/WORK-20260810-066_PHASE16_PLAYERCOMMAND_ARCHITECTURE.md`

This report defines the Phase-16 adversarial oracle only. It does not issue final PASS/FAIL, does not modify runtime, does not create Phase-17 artifacts and does not treat the currently observed in-progress commit as the final release candidate.

---

# 1. Canonical contract under attack

The oracle treats PlayerCommand as a transient, typed, immutable intent/request contract.

Hard separation:

```text
PlayerCommand
!= StatePatch
!= PlayerChangeSet
!= committed mutation
!= database transaction
!= event
!= FinancialTransaction
!= OwnershipRecord
!= DevelopmentProject fact
!= final authoritative state
```

Phase 16 owns structural identity, typing, deterministic serialization/fingerprinting and semantic command equality. It does not own execution, commit-time reference/authorization/domain validation, durable retry status or turn transaction semantics.

Identity oracle:

```text
(campaignUid, commandUid) identifies a logical command identity scope.
Same scoped UID + exact immutable semantic content => SAME_LOGICAL_COMMAND.
Same scoped UID + changed immutable semantic content => COMMAND_IDENTITY_CONFLICT.
Different campaign => distinct scoped command identity; never silent campaign rebind.
```

Canonical fingerprint/equality must cover every immutable semantic field defined by the contract: schema version, scoped command identity, actor, command kind, typed payload, semantic provenance, causation/correlation identities, requested effective order, preconditions and typed extensions.

---

# 2. Mandatory adversarial matrix

## CMD-ADV-01 — exact same command UID + exact payload

Construct two independently allocated commands with the same campaignUid, commandUid, actor, kind, full payload, provenance, requested order, preconditions and extensions.

Required:

```text
validate(A) = success
validate(B) = success
fingerprint(A) == fingerprint(B)
compare(A,B) == SAME_LOGICAL_COMMAND
serialize(A) == serialize(B)
```

Object identity must not matter.

## CMD-ADV-02 — same scoped UID + different immutable payload

Vary one immutable field at a time while keeping `(campaignUid, commandUid)` unchanged:

- payload amount/effort/ref;
- actor;
- commandKindUid;
- semantic provenance;
- requestedEffectiveOrder;
- precondition;
- extension;
- causationUid/correlationUid if included in semantic identity.

Required: deterministic `COMMAND_IDENTITY_CONFLICT`; never return caller payload as canonical and never silently normalize two commands into equality.

## CMD-ADV-03 — same command UID + different campaign

Construct exact semantic copies except `campaignUid=C` vs `campaignUid=D`.

Required by current architecture: `DISTINCT_COMMAND`, not same logical identity and never rebind to whichever campaign is active. Any final alternative contract must be explicit, deterministic and campaign-safe.

Round-trip test must preserve the original campaign exactly.

## CMD-ADV-04 — same scoped UID + different actor

Keep command/campaign UID and payload fixed; change actorKindUid and/or actorUid.

Required: identity conflict. Actor is semantic command content, not transport metadata.

Also test blank actor kind/UID => structural reject.

## CMD-ADV-05 — command kind / payload type mismatch

Examples:

```text
kind=TRAIN + LearnSkill payload
kind=TRANSFER_FUNDS + RecordProjectWork payload
kind=COMPLETE_PROJECT + TransferOwnership payload
```

Required: structural reject with no fallback codec, reflection guess or coercion.

## CMD-ADV-06 — unknown command kind

Use syntactically valid but unregistered kinds:

```text
RPGOS-COMMAND:UNKNOWN
WORLD:BLEACH:CUSTOM_RAW
STATE_PATCH
SQL
```

Required: reject as unknown. No generic Map/JSON/raw fallback.

## CMD-ADV-07 — unsupported schemaVersion

Test `0`, negative, current+1, large Int, and serialized malformed/non-integer values.

Required: deterministic structural reject. No best-effort downgrade/upgrade in Phase 16.

## CMD-ADV-08 — malformed typed target refs

For every payload/precondition/evidence-ref surface:

- blank kindUid;
- blank uid;
- whitespace-only values;
- partially missing serialized ref;
- wrong JSON scalar/object shape.

Required: structural reject.

Important boundary: existence/authorization is later; syntactically well-formed ghost references may structurally validate in Phase 16 and must not be mistaken for commit authorization.

## CMD-ADV-09 — raw SQL/table/column injection representation

Search public command envelope, payloads, extensions and preconditions for fields or generic containers capable of representing:

```text
table
column
sql
operation
where
values
ContentValues
SQLiteDatabase callback
arbitrary mutation map
```

Attempt serialized injection using these unknown keys.

Required: impossible by typed contract or deterministically rejected by strict decoder/version policy. Payload string values containing SQL-like text are inert data only and can never become mutation instructions.

## CMD-ADV-10 — StatePatch wrapped as command

Attempt:

- commandKindUid `STATE_PATCH`;
- extension carrying a StatePatch-equivalent operation;
- generic payload object with table/op/values;
- encoded nested `statePatch` field.

Required: impossible/rejected. Phase 16 cannot become a typed-looking alias for StatePatchEngine.

## CMD-ADV-11 — caller declares canonical result

Inspect all payloads and serialization for result-authority fields, including synonyms, not only exact names:

```text
finalBalance / resultingBalance
newStat / finalStat / effectiveValue
masteryAfter / finalMastery
ownershipRecordUid / owned=true
progressDelta / progressAfter
resultKind=BREAKTHROUGH
milestoneAchieved=true
requirementSatisfied=true
canonicalOutcomeUid
committedOrder
truthKind + truthUid as already-produced result
```

Requested amounts/effort/desired state are legitimate intent. Caller-declared authoritative result is forbidden.

## CMD-ADV-12 — commandUid conflated with event/transaction/domain UID

Use identical strings deliberately across:

- commandUid;
- causationUid;
- provenance sourceUid;
- financialTransaction-like target UID;
- projectUid;
- event UID strings.

Required: equality/serialization preserves distinct fields; no automatic identity aliasing or generated downstream record assumption exists in Phase 16.

String equality is allowed as caller data but must not imply semantic identity equivalence.

## CMD-ADV-13 — serialization changes semantic command

For every registered core command codec:

```text
cmd -> encode -> decode -> encode
```

Required:

```text
semantic equality preserved
canonical serialization byte/string deterministic
fingerprint unchanged
null/empty/list ordering semantics documented and stable
Long boundaries preserved exactly
```

Test Unicode, escaped text, null optionals, empty lists, large Long values and repeated encode/decode cycles.

## CMD-ADV-14 — unknown extension / unknown serialized field handling

Attack root envelope, actor, provenance, precondition, extension and every payload JSON with an additional unknown key.

Required contract must be explicit:

- reject unknown semantic fields; OR
- preserve them through a registered typed/versioned extension mechanism.

Forbidden: silently drop an unknown field and return a command that fingerprints as if the field never existed. That converts potentially new semantics into an older command without caller awareness.

Also test unknown extension kind and unsupported extension schema version.

## CMD-ADV-15 — arbitrary table/column precondition

Try to encode a precondition equivalent to:

```text
ExpectedColumnValue(table,column,value)
RawSqlPredicate(sql)
ExpectedRow(table,key,map)
```

Required: impossible/rejected. Allowed optimistic preconditions must remain typed domain expectations such as record version or lifecycle state.

## CMD-ADV-16 — construct/validate/serialize/fingerprint mutates DB

Take snapshots/counts/hashes of all accepted authoritative domain tables before and after:

```text
construct command
registry.validate
registry.encode
registry.decode
registry.fingerprint
identity.compare
```

Use existing and nonexistent actor/targets.

Required: zero SQLite writes and no creation of definitions, references, projects, ledger entries, events or command history.

This is a hard Phase-16 invariant.

## CMD-ADV-17 — registry kind collision

Construct custom registry inputs with:

- duplicate logical kind UID from two codecs;
- blank kind;
- same kind UID claimed by different payload classes;
- attempt to replace a core kind with a different codec through extension API if such API exists.

Required: deterministic rejection or an API shape that makes duplicate registration impossible. Never last-writer-wins silently.

Map construction itself can erase duplicate keys before registry sees them; final validation must therefore inspect the actual registration API and test collision at the earliest supported boundary.

## CMD-ADV-18 — deterministic parallel registry/fingerprint behavior

Run many parallel callers against the same immutable command and registry, and independently constructed equivalent registries when supported.

Required:

```text
one canonical serialization
one fingerprint
no shared mutable codec state
no ordering-dependent registry output
no nondeterministic identity result
```

This is a transient concurrency/determinism test, not a SQLite race test.

---

# 3. DevelopmentProject smuggling matrix

PlayerCommand for Phase-15 projects must carry intent/evidence refs only.

## P16-PROJECT-ADV-01 — progress result smuggling

Attempt to encode canonical `progressDeltaUnits`, `progressAfter`, percentage complete or equivalent via RecordProjectWork command.

Required: no typed field and no raw-extension bypass that acts as canonical result.

`effortUnitsIntent`, requested resource use and method/evidence refs are allowed intent.

## P16-PROJECT-ADV-02 — milestone truth smuggling

Attempt `milestoneAchieved=true`, achievement UID, achieved order or caller-declared canonical milestone result.

Required: absent/rejected. Command may request milestone evaluation with project/milestone/evidence/source-work refs only.

## P16-PROJECT-ADV-03 — fake durable outcome

Attempt to have CompleteProject command contain a final Truth/Technique/Skill/Item/Asset UID asserted as already canonical.

Required: command represents completion intent/evidence only. It must not create or validate final durable outcome identity in Phase 16.

## P16-PROJECT-ADV-04 — final lifecycle truth

Desired lifecycle state in a lifecycle command is intent, not proof. `requestedStatusUid=COMPLETED` must not be modeled/serialized as committed status or bypass Phase-15 lifecycle authority.

## P16-PROJECT-ADV-05 — Phase-15 blocker regression boundary

Milestone `sourceWorkRef` can be structurally well formed but stale/wrong-project/future. Phase 16 must not claim it is authoritative evidence. Later execution must revalidate with Phase-15 SQLite guards. The command contract should make this distinction explicit in docs/API naming.

---

# 4. StatePatch-equivalence audit

Final validation must enumerate every public PlayerCommand-related type and reject any Phase-16 surface that exposes:

- table names as mutation destinations;
- column names;
- generic op names (`INSERT/UPDATE/DELETE/SET`);
- raw SQL;
- `ContentValues`;
- arbitrary key/value mutation maps;
- SQLite/database callbacks;
- StatePatch/StatePatchOperation as a payload;
- generic `Any?` payload fallback.

Typed `DomainRef(kindUid, uid)` is not a StatePatch primitive; it is an identity reference and must remain semantically inert until later resolution.

---

# 5. Persistence / migration oracle

Phase 16 architecture explicitly chooses a transient contract.

Search schema, migrations, production code and tests for any new:

```text
player_commands
command_history
command_execution
command_status
command_inbox
command_queue
command_outbox
command_replay
```

or equivalent persisted table/ledger.

Also search migration code for legacy synthesis/backfill of commands from StatePatch, chat, UI actions, project history, event history or domain records.

Required Phase-16 result:

```text
no command persistence migration
no execution status ledger
no synthetic legacy command history
```

Optional commandUid fields that already exist as provenance/evidence on older domain records do not constitute a Phase-16 command store.

---

# 6. Serialization strictness oracle

This is a dedicated high-risk area because semantic identity is fingerprinted from canonical serialization.

For each object boundary (root, actor, provenance, payload, precondition, extension):

1. remove each required field one at a time;
2. replace type (string -> object/number/null, number -> string/double);
3. add an unknown field;
4. duplicate semantic information under an alternate unknown field;
5. reorder JSON properties;
6. reorder semantically ordered lists and determine whether order is part of contract;
7. exercise `Long.MIN_VALUE`, `Long.MAX_VALUE`, zero and invalid sign per payload;
8. use escaped Unicode/control characters in text intent.

Required:

- malformed required content rejects;
- semantically equivalent property ordering canonicalizes deterministically;
- semantically different content never fingerprints equal because the decoder silently discarded it;
- numeric representation remains exact.

A final implementation that ignores unknown root/payload semantic fields without a documented compatibility rule should be treated as a likely release blocker under CMD-ADV-14.

---

# 7. Registry / codec completeness oracle

For every constant exposed by `PlayerCommandKinds`, require exactly one registered typed codec and validator.

For every registered codec require:

```text
kindUid unique
payloadType unique/appropriate for kind
encode accepts only its payload type
decode returns exactly that payload type
validation covers structural invariants of that payload
encode/decode round-trip equality
unknown/malformed payload rejected
```

Cross-product attack: for N core kinds, feed representative payloads from every other kind. All off-diagonal combinations must reject.

Registry construction order must not change fingerprint/serialization semantics.

---

# 8. No-result-field audit by domain

Final validation must inspect payload field names and semantics across all command families, not only project commands.

## Stats/resources
Allowed: requested effort/amount/method/ref.
Forbidden: final base/effective/max/current value.

## Skill/Technique
Allowed: stable target UID, practice/use intent, method.
Forbidden: resulting mastery/xp/learned flag/final technique creation record.

## Inventory/Equipment
Allowed: desired acquire/transfer/consume/equip action.
Forbidden: caller-declared resulting inventory quantity, possession truth, equipped authoritative row or derived modifiers.

## Ownership
Allowed: requested asset/party/share terms.
Forbidden: caller-created OwnershipRecord, legal-owner truth, validFrom/validUntil result.

## Finance
Allowed: requested accounts/currency/exact amount.
Forbidden: final balance, transaction success/status, caller-defined FinancialTransaction record identity as automatic output.

## Asset/Liability
Allowed: requested terms/refs/amount.
Forbidden: canonical AssetRecord/Obligation state, valuation truth, settlement result.

## DevelopmentProject
Allowed: intent/evidence refs.
Forbidden: progress/result/milestone/requirement/outcome truth.

---

# 9. Future execution race gates — NOT Phase-16 release gates

These must be documented now but enforced later at authoritative execution/commit layers. A pure transient immutable Phase-16 contract is not expected to solve them with SQLite or locks.

## CMD-RACE-01 — exact retry

Two execution attempts of the same scoped command UID + exact semantic fingerprint.

Future requirement (Phase 18/28): one committed effect; retries deterministically return same/ALREADY_COMMITTED outcome.

Phase-16 requirement: identity/fingerprint must be stable enough to support this.

## CMD-RACE-02 — conflicting retry

Same scoped command UID + conflicting immutable command content.

Future requirement: deterministic identity conflict; never second committed effect.

Phase-16 requirement: conflict must already be detectable semantically.

## CMD-RACE-03 — stale expected version

Command carries ExpectedRecordVersion=N; target becomes N+1 before execution.

Future requirement: revalidate at execution/commit and reject/re-resolve. Phase-16 validation must not mark precondition as authoritative success.

## CMD-RACE-04 — target changes before execution

Target exists/active at command creation then changes before execution.

Future requirement: authoritative domain resolver rechecks current committed state. Phase-16 structural validation must not cache a truth decision.

## CMD-RACE-05 — ambiguous previous execution outcome

Caller loses response after commit and retries.

Future requirement: persisted command execution identity/outcome at Phase 18/28/transaction architecture. Phase 16 must not create a premature incomplete execution ledger to solve it.

---

# 10. Final validation protocol after CHAT-1 result SHA

When a final Phase-16 runtime SHA is explicitly declared:

1. refresh master and identify whether later commits are report-only or runtime;
2. validate exactly the declared runtime SHA, not current HEAD by assumption;
3. verify exact CI head SHA and complete unit/build result;
4. compare candidate against accepted Phase-15 runtime `173e501fbe832980bb4eaf177c5ba34d93cd5f37`;
5. inspect all PlayerCommand model/registry/codec/validator/serialization files;
6. enumerate all command kinds and codec registration;
7. execute CMD-ADV-01..18;
8. execute DevelopmentProject smuggling matrix;
9. verify StatePatch separation;
10. verify zero Phase-16 command persistence/migration/backfill;
11. verify construct/validate/encode/decode/fingerprint/compare cause zero DB mutations;
12. run deterministic parallel fingerprint/registry tests;
13. rerun Phase-15 regression suite or verify exact CI includes it;
14. document CMD-RACE-01..05 as future Phase-18/28 execution gates, not Phase-16 failures unless Phase 16 falsely claims commit protection;
15. issue exactly one final Phase-16 adversarial verdict requested by the coordinator.

No final Phase-16 PASS/FAIL is issued by this oracle.

---

# 11. Current in-progress implementation observations (non-verdict)

At matrix preparation, master HEAD is `9e3825e624bd108646aee01847d7f0f19d6dd20e`, an in-progress `WORK-20260810-067` correction. The observed implementation is split into typed model, registry and core codec files and does not expose a `player_commands` persistence table in repository search. Existing test code already exercises exact/conflicting identity, campaign/actor semantics, kind/payload mismatch, unknown kind, ref shape, StatePatch field leakage, serialization/fingerprint determinism, project intent-only payloads, no-mutation and typed preconditions.

These observations are useful coverage hints only. They are not final evidence because no final runtime SHA was supplied for Phase 16.

One high-priority attack for final validation is strict decode behavior for unknown fields: the final candidate must not silently discard semantically meaningful unknown root/payload fields unless the version/extension contract explicitly defines that behavior.

# ADVERSARIAL MATRIX READY / FINAL VALIDATION PENDING
