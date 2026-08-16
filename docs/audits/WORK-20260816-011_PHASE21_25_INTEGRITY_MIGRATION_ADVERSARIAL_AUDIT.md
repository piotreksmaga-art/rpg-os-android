# WORK-20260816-011 — Player Core Completion Phase 21–25 Integrity / Migration / Adversarial Pre-Audit

## 0. Audit identity and verdict

- **Work ID:** `WORK-20260816-011`
- **Program:** PLAYER CORE COMPLETION — PHASE 21–25
- **Role:** CHAT-3 — independent integrity / adversarial / migration / source-of-truth auditor
- **Mode:** READ-ONLY production runtime; evidence-only audit artifact permitted
- **Repository:** `piotreksmaga-art/rpg-os-android`
- **Branch:** `master`
- **Canonical accepted Phase-20 runtime:** `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`
- **Actual master HEAD at audit start / pre-write check:** `dc294fc655ee12ebffb2d2258cf50dd39cd165cf`
- **Current-master CI inspected:** `Validate RPG OS ALPHA`, run **#584**, run ID `31964196427`, `completed / success`, head SHA `dc294fc655ee12ebffb2d2258cf50dd39cd165cf`
- **Runtime drift from accepted Phase 20:** NONE. Current master is six commits ahead of the accepted Phase-20 runtime, but the inspected diff contains documentation/acceptance/roadmap/TEST-GM files only; no Kotlin runtime, schema, migration, or production-test delta was introduced after `38dafe5c…`.

### Final pre-implementation verdict

**READY WITH REQUIRED PRECONDITIONS**

This is not an acceptance of Phases 21–25 and does not mark any of them COMPLETE. It means CHAT-1 may implement the program sequentially only if the stop gates and required preconditions in this audit are treated as hard boundaries. In particular, Phase 23 must **not** pretend that a global committed unified ledger can already be atomically committed with player state before future TurnTransaction infrastructure exists.

---

## 1. Repository-first evidence and independent classification

The audit independently inspected the current repository rather than relying on prior conversational memory or CHAT-2 conclusions. Relevant canonical and runtime evidence included the current roadmap/master architecture, Phase-19 canonical scope/acceptance lineage, Phase-20 canonical acceptance, accepted Phase-17–20 proposal/orchestration/runtime code, progression/profile/legacy models, source-of-truth registry, finance/ownership authorities, CharacterPanelSnapshot reader, migrations, and representative tests.

Canonical accepted Phase 20 explicitly establishes a **pure, deterministic, side-effect-free progression proposal layer**, deterministic progression/grant/ledger-intent identities, fixed-point numeric policy, typed progression ledger **intent/evidence**, and integration before the single Phase-19 DRAFT_EFFECT_CHECK. It explicitly does **not** establish a committed progression ledger authority, Phase-21 behavior, a Phase-22 invariant engine, Phase-23 committed unified-ledger authority, TurnTransaction, global COMMIT, or retry/idempotency infrastructure.

### Independent Phase 21–25 classification

| Phase | Independent classification | Evidence-based interpretation |
|---|---|---|
| **21 — Diminishing Returns + passive progression hooks** | **MISSING, with reusable Phase-20 primitives** | Phase 20 already has deterministic fixed-point factors, canonical factor ordering, causal stimuli, stable identities, and dependency versions. It does not have accepted diminishing-return state, novelty/adaptation state, passive scheduler hooks, or time-skip orchestration. |
| **22 — Player Invariant Validator + No-Retrogression** | **MISSING, with partial structural validation elsewhere** | PlayerChangeSet has typed deltas and structural validation, while WorldRuleProvider provides world legality. There is no accepted global durable-regression legality validator. A blanket `delta < 0 => reject` would be wrong. |
| **23 — Unified Player ledgers + provenance integration** | **PARTIAL FOUNDATION / COMMITTED AUTHORITY DEFERRED** | `PlayerLedgerIntent` already exists; progression extends it as proposal evidence; finance already owns an authoritative domain ledger and derived balance projection; ownership has temporal authoritative records. A global committed ledger cannot safely become a second commit authority before TurnTransaction. |
| **24 — CharacterPanelSnapshot v2** | **PARTIAL and currently mixed with legacy reads** | `CharacterPanelSnapshot` and `CharacterPanelReader` exist, but the reader directly queries legacy tables such as `character_stats`, `character_skills`, `character_techniques`, inventory and `character_status_snapshot`. It is presentation/derived code, not a safe authority boundary. |
| **25 — PlayerSnapshotBuilder profiles** | **MISSING** | No production `PlayerSnapshotBuilder` or FULL/COMBAT/PROGRESSION/ECONOMY/SOCIAL/GM_CONTEXT profile implementation was found in the accepted runtime/current tree. Existing context/panel code is not equivalent to a canonical profile builder. |

---

## 2. Fundamental invariants for the entire program

Every implementation in Phases 21–25 must preserve all of the following:

1. `AI OUTPUT != COMMITTED REALITY`.
2. `FACT != BELIEF != NARRATIVE`.
3. Stable UID is identity; names/labels are not identity.
4. `AUTHORITATIVE > DERIVED > CACHE/PRESENTATION`.
5. `PlayerChangeSet` remains a proposal until future commit infrastructure legally commits it.
6. `PlayerDomainEngine.resolve()` and progression resolution remain free of authoritative mutation.
7. The same pinned World Pack authority remains in force through PRECHECK and final EFFECT_CHECK.
8. WorldRuleProvider remains read-only and cannot become a second progression engine or mutation authority.
9. Durable progression must not regress without an explicit legal cause and provenance.
10. Legal negative state changes must remain possible where the domain semantics permit them.
11. Passive/rebuild/snapshot/profile reads must never create durable gain.
12. Unknown historical provenance remains unknown; history must not be fabricated during migration.
13. Existing domain authority must not be duplicated into a new writable aggregate source of truth.
14. Design now must remain future-compatible with `failed turn -> rollback -> no partial mutation`, `retry -> no duplicate effects`, `crash -> last valid commit`, and `snapshot -> replay -> same authoritative state`.

---

# 3. Phase 21 adversarial audit — diminishing returns, novelty/adaptation, passive hooks

## 3.1 Main attack surface

Phase 21 can easily destroy determinism if diminishing returns or novelty depend on mutable observations that are not explicit inputs. The accepted Phase-20 engine currently derives deterministic identities from explicit campaign/character/source/target/factors/policy/World-Pack/dependency inputs and uses fixed-point arithmetic. A Phase-21 implementation must not introduce invisible memory such as `lastTrainingTime`, process-local counters, global maps, random calls, current wall clock, iteration order, UI load count, or “times evaluated” state.

### Required model

A repeated semantic stimulus may yield a different grant **only when a causal, authoritative input differs** and that difference is represented in deterministic evidence/fingerprints. Examples include an explicit adaptation state/version, a committed prior-training count, a committed novelty state, or a versioned World Pack rule input. “This was called for the second time” is not a legal cause.

Diminishing returns are a modifier of a gain proposal. They must not be implemented as a permanent negative delta against previously earned mastery/stat value. If the resulting factor is zero, the legal result is no grant, not retroactive loss.

## 3.2 Order dependence

Phase 20 already fixed factor permutation instability by canonicalizing factors using semantic numeric fields and fingerprint. Phase 21 must preserve that property. Any additional adaptation/novelty factors must either enter the same canonical factor family or have their own fully specified canonical order. Using insertion order, map iteration order, hash order, SQL row order without `ORDER BY`, or “first matching domain” is forbidden.

## 3.3 Numeric safety

MUST:

- continue fixed-point/versioned arithmetic;
- reject NaN/Infinity at any Double boundary before conversion;
- reject negative values unless the field semantics explicitly permit them;
- use checked integer arithmetic / BigInteger where multiplication may overflow;
- specify rounding once and include numeric policy UID/version in deterministic identity;
- fail closed on overflow/underflow where a non-zero semantic value collapses unexpectedly;
- prohibit factors that accidentally produce negative grants;
- distinguish zero grant from rejected/invalid stimulus.

A Phase-21 “smooth curve” implemented with platform floating-point exponentials/logarithms would be an unnecessary replay risk unless the algorithm and canonicalization are explicitly versioned and proven cross-run deterministic. Prefer exact/rational/fixed-point policy.

## 3.4 Passive progression hooks

“Passive hook” must mean a **deterministic proposal hook with an explicit causal source**, not “run progression when something is loaded”. Forbidden triggers include:

- campaign load/open;
- database migration;
- snapshot rebuild;
- CharacterPanel generation;
- PlayerSnapshotBuilder profile generation;
- cache invalidation;
- ContextBuilder calls;
- app resume/start;
- repeated render/composition;
- generic save/restore.

A future scheduler/time-skip processor may invoke passive-progression stimuli, but Phase 21 must not duplicate Phase 60 Time Skip Processor or build a hidden scheduler.

## 3.5 World Pack boundary

World Pack may supply **versioned policy/config/rule decisions**, but must not own a second stateful progression engine. Core must remain the deterministic calculation owner. A provider rule may reject/limit a result through the existing legality gate; it must not directly write mastery/stats or maintain hidden adaptation memory.

## 3.6 Phase-21 MUST EXIST NOW vs DEFERRED

### MUST EXIST NOW

- deterministic/versioned diminishing-return and novelty/adaptation input contract;
- explicit stable identity for any adaptation/novelty evidence used;
- canonical ordering/fingerprinting of every new factor;
- zero hidden wall-clock/process-local/random dependence;
- passive hook API that produces only proposal stimuli/results;
- explicit source/cause/provenance for passive stimuli;
- no mutation on hook evaluation;
- duplicate evaluation of identical input produces identical proposal identity/result;
- numeric boundary tests including overflow/zero/non-finite cases;
- integration before the existing final WorldRuleProvider EFFECT_CHECK.

### DEFERRED

- scheduler ownership;
- time-skip orchestration;
- global idempotent commit/dedupe;
- transaction rollback;
- persistent adaptation history if its only safe owner is the future committed transaction/event system. If persistent adaptation state is introduced earlier, it must itself use an existing authoritative state path and cannot be silently mutated by evaluation.

---

# 4. Phase 22 adversarial audit — Player Invariant Validator / No-Retrogression

## 4.1 No-Retrogression is not “no negative deltas”

A simple sign check would break legal gameplay. The validator must classify the semantic meaning of the change and its cause.

Legal negative/change examples may include:

- injury or lasting impairment;
- resource consumption;
- temporary modifier expiration;
- equipment removal and loss of derived bonuses;
- ownership transfer/inventory consumption;
- explicit evolution/respec transformation;
- an explicit future World Pack decay mechanic;
- a verified corruption repair/migration normalization path under dedicated migration authority;
- reversal/rollback semantics owned by later transaction infrastructure.

These are not all “durable progression regressions”. Resource consumption, removal of a temporary modifier, unequip, or derived-value decrease must not be treated as loss of permanently earned progression.

## 4.2 Required semantic categories

The Phase-22 validator should reason about at least:

- **durable earned progression** (stat base/mastery/progress where non-regression applies by default);
- **consumable/runtime state** (resources/counters, where negative delta is expected under legal operations);
- **derived state** (must be recomputed, not guarded as permanently earned value);
- **temporary modifier/equipment effects**;
- **explicit destructive/transformative authoritative changes** with legal cause;
- **migration/repair evidence**, which is not normal gameplay progression.

## 4.3 Legal durable regression requires cause

Any durable regression must carry a typed/structured legal cause that can survive future ledger/event integration. A free-text reason alone is insufficient. At minimum the proposal must be linkable to stable source command/event/rule/effect identity, target identity, campaign/character identity, and the rule/policy version that allowed the regression.

Unknown cause => reject/fail closed for a new gameplay regression. Unknown **legacy historical provenance** => preserve existing value/evidence; do not delete or “repair” it merely because modern provenance is absent.

## 4.4 WorldRuleProvider interaction

The invariant validator and WorldRuleProvider have different jobs:

- Core invariant validator checks structural/system-wide invariants and source-of-truth rules.
- WorldRuleProvider checks World-Pack-specific legality under the pinned authority.

Neither silently overrides the other. `Core reject OR World rule reject => rejected proposal / no authoritative mutation`. A World Pack cannot legalize a structurally impossible cross-campaign reference or overflow. Core cannot invent world-specific legality that belongs to WorldRuleProvider.

## 4.5 Fail-closed rules

Fail closed for:

- durable negative progression without typed legal cause;
- wrong campaign/character/target UID;
- unknown target semantics where the validator cannot determine whether regression is legal;
- arithmetic overflow;
- conflicting legal-cause identities;
- cause referring to an evidence object outside reference closure;
- mismatched World Pack policy identity;
- a rejected WorldRule effect check;
- validator exception/partial validation.

Fail closed means **no accepted PlayerChangeSet proposal**, no ledger/event side effects, and no direct store mutation.

## 4.6 Phase-22 MUST EXIST NOW vs DEFERRED

MUST NOW: semantic target classification, typed legal-regression cause/provenance contract, fail-closed validation, campaign isolation, explicit distinction between durable progression and resource/temporary/derived changes, deterministic validation, Phase17–20 regression gates.

DEFER: global rollback semantics, inverse transaction generation, crash recovery, transaction-level conflict resolution, future world-specific decay scheduler.

---

# 5. Phase 23 adversarial audit — unified player ledgers + provenance integration

## 5.1 Highest-risk conclusion

**A new global committed unified-ledger authority must not be introduced in Phase 23 before TurnTransaction.**

The repository already contains heterogeneous legal authority models:

- `PlayerLedgerIntent` is a proposal envelope.
- Phase-20 progression ledger payload is causal/proposal evidence only.
- Finance already has an authoritative `financial_ledger_transactions` history and a rebuildable `financial_account_balances` projection, with domain-local transactional/idempotent behavior.
- Ownership uses temporal authoritative records with provenance/supersession/closure semantics.
- Stats/skills/techniques hold authoritative current state in typed stores/legacy reconciliation structures.
- DevelopmentProject owns its own work/history semantics.

Flattening these into a new independently writable `unified_player_ledger` before an atomic turn commit exists would create at least one of:

1. duplicate persisted authority;
2. ledger/state split-brain;
3. append-before-state partial success;
4. state-before-ledger partial success;
5. false global idempotency guarantees;
6. provenance fabricated from incomplete legacy history.

## 5.2 Safe bounded Phase-23 contract

Phase 23 can safely satisfy roadmap intent by building **unified ledger/provenance contracts and deterministic intents/read projections**, not a second global commit engine.

Safe in Phase 23:

- expand the typed `PlayerLedgerIntentPayload` family;
- normalize stable provenance envelope fields across intents;
- add deterministic causal links between proposed changes and their ledger/event intents;
- define explicit ledger-kind ownership/authority classification;
- build read-only unified views over already-committed domain evidence where semantics are known;
- add codec compatibility for optional provenance fields;
- add reference closure and campaign-isolation validation;
- define future commit identities/dedupe keys without claiming they have global commit semantics;
- preserve domain-local authoritative ledgers (finance) rather than copying them into a new authority.

Unsafe before TurnTransaction:

- atomically committing progression + finance + ownership + stat/mastery state through a new global writer;
- making a global ledger row authoritative for current state while stores remain independently writable;
- backfilling “historical unified ledger” rows from current balances/mastery values;
- treating absence of ledger history as evidence that a current value did not happen;
- deleting or demoting current domain histories before transaction migration exists.

## 5.3 P20-CB-01 — required Phase-23 decision

Confirmed accepted-runtime fact:

`ProgressionStimulus.evidenceRefs` participates in reference closure but is not copied into `ProgressionEvaluationInput` or `ProgressionLedgerIntentPayload`.

### Recommendation

**Make this a Phase-23 requirement.** Evidence references that are intended to explain durable progression should be represented in the durable/proposal provenance envelope before unified provenance is considered complete.

Requirements:

- decide explicitly whether each evidence ref class is `LEGALITY_ONLY` or `DURABLE_PROVENANCE`;
- durable provenance refs must be copied into deterministic evaluation/result/ledger identity in a canonical order;
- reference UIDs and kinds must remain stable and campaign-valid;
- codec fields should be additive/backward compatible;
- historical Phase-20 ledger intents lacking refs remain `unknown/not-recorded`, not retroactively inferred;
- no migration may reconstruct evidenceRefs from narrative, names, timestamps, target values, or “likely” nearby events;
- if an older payload lacks the field, decoding must preserve that absence distinctly from an explicitly empty evidence set if that distinction matters semantically.

No retroactive provenance fabrication is permitted.

## 5.4 Duplicate/retry risks

Phase-23 stable identities must make later dedupe possible:

- ledger intent UID;
- source command UID;
- causal change UIDs;
- progression UID/grant UID for progression;
- domain operation UID/transaction UID where existing;
- campaign UID;
- subject/target UIDs;
- provenance schema/version;
- source event UID when actually known;
- deterministic payload fingerprint.

These identities are necessary now; **global retry commit semantics are deferred**.

## 5.5 Existing finance exception

Finance already has domain-local committed ledger semantics and idempotent replay by transaction UID/command UID. Phase 23 must REUSE that authority. It must not wrap the same transfer in a second independently committed money ledger whose success can diverge from `financial_ledger_transactions`.

## 5.6 Ownership split-brain guard

Ownership history is temporal state with supersession/closure. A unified ledger may reference an ownership operation, but must not independently claim who owns an asset. Current ownership records remain the domain authority until a later explicitly designed migration changes authority atomically.

---

# 6. Phase 24 adversarial audit — CharacterPanelSnapshot v2

## 6.1 Hostile test

Required invariant:

> Delete every CharacterPanelSnapshot/cache/presentation artifact, rebuild from authoritative state, and compare authoritative state before/after. No authoritative value may disappear or change.

Current `CharacterPanelSnapshot` is an in-memory data object returned by a reader. It is not itself evidence of persisted authority. However, the current reader directly queries legacy surfaces (`character_stats`, `character_skills`, `character_techniques`, `character_inventory`, relationships/goals) and may read `character_status_snapshot` as a legacy status source. Therefore Phase 24 must not simply persist the current output and call it authoritative v2.

## 6.2 Current partial-implementation risks

- legacy table value may be shown even when typed/current authority differs;
- `character_status_snapshot` name encourages accidental authority promotion;
- snapshot strings can lose exact numeric semantics/type/version information;
- name-based `SkillLine`/`TechniqueLine` projection omits stable UIDs;
- presentation ordering/limit (`relationships ... LIMIT 30`) proves the object is intentionally incomplete;
- missing table/column is silently skipped, which is acceptable for presentation but not for authority restoration;
- identity/resource classification in `readLegacyStatus` uses name substring heuristics (`chakra`, `stamina`, `energy`), which must never become migration/domain semantics.

## 6.3 Required Phase-24 direction

- snapshot is DERIVED/PRESENTATION or explicitly disposable CACHE only;
- source fields must be read from canonical typed authorities/reconciliation readers, not raw legacy tables where a typed authority exists;
- stable UIDs should accompany labels where identity matters;
- exact numbers should retain canonical units/semantics in the internal snapshot model and only stringify at UI boundary;
- snapshot builder must be read-only;
- no “write snapshot back to state” operation;
- migration must never depend on snapshot presence;
- snapshot rebuild must not invoke passive progression;
- frontend must not mutate authoritative state by editing snapshot fields.

---

# 7. Phase 25 adversarial audit — PlayerSnapshotBuilder profiles

## 7.1 Profile semantics

Every profile is a deterministic projection of controlled authoritative/derived inputs:

- `FULL` = broad projection, **not a new authority**;
- `COMBAT` = combat-relevant slice, **not separate combat state**;
- `PROGRESSION` = progression-relevant slice, **not a progression database**;
- `ECONOMY` = economy projection, **not financial authority**;
- `SOCIAL` = social projection, **not NPC knowledge authority**;
- `GM_CONTEXT` = context projection, **not an omniscient collapse of FACT/BELIEF/NARRATIVE**.

## 7.2 Omission semantics

Absence from a profile means only “not included in this projection”. It must never mean “does not exist in reality”. No mutation, validation, migration, or deletion algorithm may use profile omission as domain absence.

Examples:

- a skill omitted from COMBAT still exists;
- an account omitted from PROGRESSION still exists;
- an NPC belief omitted from FULL cannot be reclassified as false/nonexistent;
- GM_CONTEXT omission cannot delete truth or memory.

## 7.3 Determinism requirements

Same authoritative input snapshot + same profile definition/version => byte/semantic-equivalent profile output, aside from explicitly excluded non-semantic serialization formatting.

Must canonicalize:

- collection ordering;
- map ordering;
- null/absent semantics;
- stable IDs;
- numeric units;
- truth-layer labels;
- profile schema/version.

No wall clock, rendering state, database row natural order, random values, or call count may affect output.

## 7.4 GM_CONTEXT truth isolation

GM_CONTEXT is especially sensitive. It must carry truth classification sufficient to distinguish at minimum canonical fact, belief/knowledge state, and narrative/presentation. It must not flatten “NPC believes X” into “X is fact”. Restricted knowledge must remain scoped to the correct knower/visibility context.

## 7.5 Rebuild rule

Profiles may be deleted/rebuilt repeatedly with zero authority mutation and zero passive progression. A profile builder called during migration must either be prohibited or be provably read-only; it must never become a migration source of truth.

---

# 8. Complete source-of-truth map for player domains relevant to 21–25

| Domain/surface | Classification | Phase 21–25 rule |
|---|---|---|
| Player stat base/current typed state | AUTHORITATIVE current state | Changes only through legal proposal -> future commit. Diminishing returns cannot rewrite history. |
| Player resource state | AUTHORITATIVE current consumable/runtime domain | Legal negative changes are normal when caused; do not apply blanket No-Retrogression. |
| TalentProfile / PotentialProfile | AUTHORITATIVE profile inputs | Inputs to progression policy; not accumulated progression or ledger totals. |
| Progression domain definitions | AUTHORITATIVE World Pack definition metadata | Stable UID/World-Pack ownership required; definitions are not player balances. |
| Skill typed state/mastery/progress | AUTHORITATIVE current state subject to existing legacy reconciliation | Do not reconstruct from snapshots or new unified ledger. |
| Technique typed state/mastery/progress | AUTHORITATIVE current state subject to existing legacy reconciliation | Same. |
| Innate/evolution state | AUTHORITATIVE domain state | Transformations may legally alter derived capabilities; Phase22 must not mistake all decreases for illegal regression. |
| Inventory | AUTHORITATIVE domain state | Quantity loss can be legal; profile/panel is projection only. |
| Equipment/loadout | AUTHORITATIVE domain state | Unequip/removal can reduce derived stats without violating earned progression. |
| Ownership records | AUTHORITATIVE temporal ownership state/history | Unified ledger may reference, not replace, ownership authority. |
| Financial ledger transactions | AUTHORITATIVE finance history | Preserve as finance authority. |
| Financial account balances | DERIVED/REBUILDABLE projection | Must reconcile to authoritative finance ledger. |
| Asset/liability/obligation records | AUTHORITATIVE domain state/history | Do not duplicate into Phase23 mutable aggregate. |
| DevelopmentProject records/work/history | AUTHORITATIVE project-domain state/history | Progression may cite as cause; does not absorb project authority. |
| `PlayerChangeSet` | PROPOSAL | Never committed reality by itself. |
| `PlayerLedgerIntent` | PROPOSAL ledger intent | Not committed global history. |
| Phase-20 `ProgressionLedgerIntentPayload` | PROPOSAL/CAUSAL EVIDENCE | Not authoritative current progression balance. |
| Future committed unified player ledger | HISTORY/EVIDENCE / transaction output, design deferred | Must be atomically tied to authoritative state before it can claim commit completeness. |
| Legacy progression evidence/mappings | HISTORY/MIGRATION EVIDENCE | Preserve raw unknowns; explicit mappings only. |
| Legacy skill/technique/stat surfaces | LEGACY AUTHORITY/EVIDENCE under existing reconciliation | Never silently overwrite/reinterpret. |
| CharacterPanelSnapshot | DERIVED/PRESENTATION (or disposable cache) | Delete/rebuild safe; never restore authority from it. |
| PlayerSnapshotBuilder profiles | DERIVED/PRESENTATION/CONTEXT projection | Omission non-semantic; no mutation. |
| Narrative text | NARRATIVE | Never source for reconstructing durable progression/provenance. |
| NPC beliefs/knowledge | BELIEF/KNOWLEDGE state, not canonical fact | GM_CONTEXT must preserve separation. |

---

# 9. Legacy / migration map

## 9.1 Existing legacy-relevant data

The repository already preserves multiple kinds of legacy evidence, including:

- `LegacyProgressionEvidence` raw key/value/source/version/provenance;
- `LegacyProgressionMapping` explicit mapping to Talent/Potential domain;
- legacy skill mastery/XP and typed-skill reconciliation paths;
- legacy technique mastery/XP and typed-technique reconciliation paths;
- legacy stat/resource compatibility surfaces;
- `character_status_snapshot` presentation/legacy status data;
- legacy financial evidence and explicit migration opening-balance mapping;
- migration records/versioning.

## 9.2 Mandatory migration rules for 21–25

1. Unknown provenance stays unknown.
2. Do not synthesize historical causes, sourceEventUid, commandUid, adaptation state, novelty state, or ledger links.
3. Do not reinterpret old XP/mastery as a new generic Phase-21 progression unit without an explicit semantics mapping.
4. Preserve stable UIDs exactly; do not regenerate from names.
5. Preserve custom/unknown World Pack data even if Core cannot currently compute with it.
6. No silent numeric precision loss. Double-based legacy/profile values must cross an explicit deterministic conversion boundary only when computation actually needs it.
7. Old campaigns with no Phase-21/22/23 metadata must still load.
8. Absence of new ledger/provenance fields in old campaigns is not corruption.
9. Migration must not execute passive progression hooks.
10. Migration must not call profile/snapshot builders as an authority source.
11. Snapshot/profile rebuild must never write authoritative state.
12. Additive migrations must be idempotent/re-runnable and preserve raw evidence if interrupted.
13. No backfill of committed unified ledger rows from current state unless a future explicit migration can prove historical causes; current-state snapshots cannot manufacture history.

## 9.3 Recommended schema delta by phase

### Phase 21

Prefer **no schema delta** unless a truly authoritative adaptation/novelty state is required now. If introduced, it needs stable campaign+character+domain/target identity, explicit version/provenance, deterministic update semantics, and must not mutate during evaluation. Otherwise carry adaptation evidence as inputs and defer persistence.

### Phase 22

Prefer **no schema delta** for the validator itself. Add typed cause/provenance contracts at proposal level. Do not create a “no retrogression log” that becomes a second source of truth.

### Phase 23

Schema changes should be limited to additive provenance/compatibility fields or read-model support that does not claim global atomic commit. Do **not** create a globally authoritative committed player-ledger table unless the same work also has an authorized atomic commit owner; that owner is currently a later phase.

### Phase 24

No authoritative schema required for CharacterPanelSnapshot. If caching is introduced, mark it explicitly rebuildable/disposable and isolate it from backup/migration authority decisions.

### Phase 25

No authoritative schema required for projections. Persist profile definitions/config only if necessary; profile output should normally be rebuildable.

---

# 10. Mechanic collision map

| Existing mechanism | Collision with 21–25 | Classification / action |
|---|---|---|
| Phase-20 `ProgressionEngine` fixed-point factors/canonical ordering | Phase21 diminishing returns/novelty | **REUSE**. Extend deterministic factor/input policy; do not create a second progression calculator. |
| WorldRuleProvider | Phase21/22 world-specific progression legality | **REUSE boundary**. World Pack supplies legality/policy, never mutation or hidden progression state. |
| PlayerChangeSet structural validation | Phase22 invariant checks | **REUSE + extend deliberately**. Do not duplicate generic structure checks. |
| Existing legacy stat/skill/technique stores | Phase21/22 progression targets | **DO NOT REPLACE**. Preserve reconciliation and stable UID semantics. |
| Financial ledger | Phase23 unified ledgers | **REUSE AS DOMAIN AUTHORITY**. Never duplicate money commits in a second ledger. |
| Financial balance projection | Phase23/25 economy projection | **REUSE as derived**, rebuildable. |
| Ownership temporal records | Phase23 unified provenance | **REUSE AS DOMAIN AUTHORITY**; unified ledger may reference only. |
| DevelopmentProject work/history | Phase21 progression cause | **REUSE as causal evidence**, do not absorb project lifecycle/progress. |
| `CharacterPanelSnapshot` / reader | Phase24 | **MUST REPLACE/REWORK WITHIN 24 where it reads raw legacy authority unsafely**; snapshot role remains derived. Do not mutate legacy in this work. |
| `character_status_snapshot` legacy presentation surface | Phase24 | **DO NOT PROMOTE**. Read only as compatibility presentation until removed in authorized cleanup. |
| ContextBuilder/current context models | Phase25 GM_CONTEXT | **REFACTOR/REUSE LATER** only after truth-layer guarantees; do not treat current context as canonical PlayerSnapshotBuilder. |
| Migration code | Phase21 passive hooks / Phase24/25 builders | **DO NOT TOUCH AS EXECUTION TRIGGER**. Migration must not create progression. |
| SourceOfTruthRegistry / typed-only protection | Phase23/24/25 | **REUSE/HARDEN if authorized**; no generic StatePatch path may write typed authorities or caches as authority. |

---

# 11. Failure matrix

| Scenario | Expected behavior NOW (21–25 design) | Future behavior after TurnTransaction | Owning phase | Required test |
|---|---|---|---|---|
| Same training stimulus replayed | Same deterministic proposal identities/result; no mutation; no “second-call” adaptation | Commit dedupes same logical operation, no duplicate gain | 21 + future 27/28 | identical input twice => identical result/fingerprints; stores unchanged |
| Reordered factors | Identical canonical result and identity chain | Same | 21 / preserve P20 | permutation property test |
| Passive hook evaluated twice | Two evaluations yield same proposal; zero store writes | At most one committed effect for same source identity | 21 + future idempotency | call-count=2, authority unchanged |
| Retry after process interruption | Phase21–25 must not assume success/append; proposal can be recomputed | Transaction recovery yields zero-or-one committed turn | 23 + future 27–29 | simulated interruption around proposal construction; no direct stores changed |
| Legal injury causes stat loss | Accepted only with typed legal cause + world legality + correct target semantics; still proposal | Atomic state+event/ledger commit | 22 | legal injury negative durable stat case passes validator |
| Illegal unexplained stat regression | FAIL CLOSED / reject / no mutation | No commit | 22 | negative durable delta with missing/unknown cause fails |
| Resource consumption | Legal negative resource delta with legal command; not treated as progression regression | Atomic commit | 22 | resource-negative case not falsely blocked |
| Equipment removal lowers derived stat | Remove equipment; recompute derived value; do not create fake durable stat regression | Atomic commit/recompute | 22/24/25 | unequip causes derived decrease without base-stat mutation |
| Ledger intent generated but later state mutation fails | Intent remains proposal only; must not be separately persisted as committed truth | Entire transaction rolls back | 23 + future 27 | no standalone committed unified row before transaction |
| State changes but future ledger commit fails | **Must not be possible through new Phase23 global writer**; do not introduce such writer now | Entire transaction rolls back | 23 + future 27 | architectural test forbids state/ledger split path |
| Finance transfer represented in unified view | Reuse existing financial transaction authority; do not second-commit money | Unified transaction references domain commit atomically | 23 | exactly one financial authoritative transaction per operation |
| Old campaign has no new provenance fields | SAFE LOAD; absence represented as unknown/not-recorded | Future commit adds provenance only for new operations | 23/migration | old fixture loads without backfill fabrication |
| P20 historical intent lacks evidenceRefs | Preserve as unknown/not-recorded | New commits carry refs after schema/contract version | 23 | backward codec test distinguishes missing field and no invented refs |
| Duplicated stable UID with different semantic payload | FAIL CLOSED conflict | Transaction dedupe/conflict rejects | 21/23 | same UID/different content rejected |
| Wrong campaign UID | FAIL CLOSED before calculation/projection | No commit | 21/22/23 | cross-campaign target/evidence tests |
| Wrong World Pack/domain | FAIL CLOSED; no fallback by name | No commit | 21/22 | mismatched domain pack test |
| Deleted CharacterPanelSnapshot/cache | Authoritative state unaffected; rebuild matches | Same | 24 | delete/rebuild equality test |
| Stale CharacterPanelSnapshot | Must never override stores; rebuild/refresh only | Same | 24 | stale snapshot cannot restore older mastery/stat |
| FULL vs COMBAT disagreement on included same field | Same source field must have same value/version; omission is allowed, contradiction is not | Same | 25 | cross-profile common-field consistency test |
| COMBAT omits noncombat skill | Omission means not projected, not nonexistent | Same | 25 | omitted entity still present in authority/FULL |
| GM_CONTEXT exposes belief as fact | FAIL profile validation or preserve explicit BELIEF classification | Same | 25 | fact/belief/narrative isolation fixture |
| Profile builder called during migration | Read-only/no mutation; preferably forbidden invocation | Same | 25/migration | migration fixture asserts zero progression/store writes |
| Profile builder called repeatedly | Same profile output; zero state changes | Same | 25 | repeated projection determinism test |
| Load/rebuild triggers passive progression | FORBIDDEN; zero gain | Same | 21/24/25 | load/snapshot/profile rebuild leaves progression state byte-equivalent |
| Negative/overflow diminishing-return arithmetic | FAIL CLOSED; no grant | No commit | 21 | min/max/overflow/underflow suite |
| Unknown custom World Pack progression data | Preserve; reject calculation if semantics required but unknown | Future versioned handler may interpret explicitly | 21/migration | custom domain survives migration/load unchanged |
| WorldRuleProvider rejects progression effect | No accepted progression change/ledger intent committed; resolution reject semantics preserved | No commit | 21/22 + P19 | final EFFECT_CHECK rejection regression |
| Snapshot serialization rounds exact value | Snapshot may format for UI but cannot become restore source; internal exact units required where compared | Same | 24/25 | exact large/fractional policy values survive projection semantics |

---

# 12. Retry / concurrency boundary — assumptions 21–25 MUST NOT make

Phases 21–25 must **not assume**:

- a `PlayerChangeSet` has been committed because resolution returned success;
- ledger intent persistence and state persistence are atomic globally;
- unique UIDs alone provide global idempotency without a commit coordinator;
- process-local locks are sufficient for multi-domain commits;
- “write ledger first, then state” is safe;
- “write state first, then ledger” is safe;
- a snapshot/profile represents a transaction boundary;
- current database state plus narrative can reconstruct missing event/ledger causality;
- a repeated passive hook invocation corresponds to elapsed in-world time;
- current wall clock can establish canonical in-world elapsed time;
- finance’s domain-local transaction wrapper generalizes to the whole player without an explicit cross-domain transaction.

### Future compatibility contract

To keep future guarantees implementable:

- all proposal effects need stable deterministic identity;
- every durable mutation proposal needs explicit causation/provenance identity;
- all cross-domain references need campaign isolation and stable UID validation;
- no phase may create a second authoritative balance/state copy;
- committed histories must be append-only or explicitly superseding/reversing according to domain semantics;
- snapshot/profile outputs must remain disposable;
- domain writers must remain distinguishable so future TurnTransaction can coordinate them rather than reverse-engineering side effects.

---

# 13. Internal stop gates for CHAT-1

## STOP GATE — Phase 21

STOP if any of the following is observed:

- same semantic input produces different result without a changed explicit causal state/version;
- diminishing returns depend on wall clock, map order, SQL natural order, random source, or call count;
- passive hook writes stores or runs on load/rebuild/render;
- World Pack becomes a second progression calculator/writer;
- numeric policy uses unversioned non-deterministic floating arithmetic;
- Phase20 identity/canonicalization tests regress.

Do not proceed to Phase 22 to compensate.

## STOP GATE — Phase 22

STOP if:

- validator bans every negative delta;
- legal injury/resource/equipment cases cannot be represented distinctly;
- unexplained durable regression can pass;
- WorldRuleProvider and invariant validator can silently override each other;
- cross-campaign or unknown-target semantics fail open;
- validation performs mutation.

Do not proceed to Phase 23 to “record” an invalid change.

## STOP GATE — Phase 23

STOP if:

- implementation requires a new global committed ledger writer before TurnTransaction;
- the same finance/ownership/progression fact becomes independently writable in two places;
- ledger/state can be partially persisted through a new Phase23 path;
- legacy history is backfilled using guesses;
- P20-CB-01 is “fixed” by inventing historical evidenceRefs;
- source command/change/ledger identities cannot support later dedupe;
- existing finance authority is duplicated rather than referenced.

## STOP GATE — Phase 24

STOP if:

- deleting snapshot data loses authoritative player information;
- snapshot rebuild mutates any player state or triggers progression;
- raw legacy snapshot fields become preferred over typed authority;
- stringified snapshot data is used for migration or restore authority;
- frontend/presentation writes can mutate authoritative values through snapshot.

## STOP GATE — Phase 25

STOP if:

- profile output is persisted as a new state authority;
- repeated builds differ without explicit input changes;
- omission is treated as nonexistence;
- GM_CONTEXT collapses belief/narrative into fact;
- profile generation causes progression or other mutation;
- FULL/COMBAT/etc maintain independent mutable copies of the same player value.

Phase 24/25 must never hide defects in Phase 21–23 by producing “correct-looking” presentation output from inconsistent authority.

---

# 14. Required adversarial acceptance tests

The following tests must exist or equivalent coverage must be demonstrated before each phase is accepted. Where the feature does not yet exist, these tests should fail/not compile against the intended contract before implementation and pass afterward.

## Phase 21 tests

- factor permutation invariance preserved from Phase 20;
- diminishing-return factor canonicalization permutation property;
- identical stimulus/evidence/version => identical complete result identity chain;
- explicit adaptation-state version change may change result and fingerprints deterministically;
- repeated evaluation count alone cannot change result;
- passive hook is side-effect free;
- passive hook double evaluation has same proposal identity;
- no wall-clock dependency test using changed system time;
- load/reopen/snapshot/profile calls do not trigger progression;
- NaN/Infinity/overflow/underflow/negative invalid-input suite;
- zero-result semantics distinct from rejection;
- World Pack mismatch/cross-campaign fail closed;
- WorldRule final effect rejection produces no accepted progression proposal.

## Phase 22 tests

- legal injury-caused durable regression accepted as proposal;
- unexplained durable regression rejected;
- resource consumption accepted;
- temporary modifier expiry/unequip derived decrease does not corrupt base progression;
- legal evolution/respec fixture with explicit cause;
- future-decay policy can be represented without hard-coding universal prohibition;
- cross-campaign cause/target rejected;
- unknown target semantics fails closed;
- validator exception leaves zero mutation;
- WorldRule rejection and Core invariant rejection both independently reject; neither can override the other.

## Phase 23 tests

- no new direct committed unified-ledger writer exists before transaction authorization;
- progression ledger intent remains proposal-only;
- financial unified view references exactly the existing committed finance transaction, not a duplicate money row;
- ownership unified view does not decide ownership independently;
- duplicate ledgerIntentUid/different payload conflicts;
- same sourceCommandUid/deterministic proposal replay stable;
- campaign isolation across all ledger refs;
- P20-CB-01 new payload carries durable evidence refs canonically when available;
- old payload lacking evidence refs decodes without invented refs;
- legacy current values do not generate fabricated historical ledger rows;
- provenance schema/version roundtrip;
- deterministic ordering/read projection.

## Phase 24 tests

- `CharacterPanelSnapshot delete/rebuild -> no data loss`;
- rebuild makes zero writes to authoritative tables;
- stale snapshot cannot override current state;
- typed authority wins over conflicting legacy presentation data;
- stable UID identity preserved in v2 internal representation;
- exact numeric values remain semantically exact internally;
- missing optional domain/table yields incomplete presentation, not state deletion;
- snapshot rebuild does not trigger passive progression.

## Phase 25 tests

- same input -> identical FULL profile;
- same input -> identical each specialized profile;
- common included fields agree across profiles;
- omissions do not imply domain deletion/nonexistence;
- profile deletion/rebuild changes no authority;
- GM_CONTEXT preserves FACT/BELIEF/NARRATIVE and knowledge scope;
- ECONOMY uses finance authority/projection correctly without becoming writer;
- PROGRESSION uses progression/current-state inputs without becoming ledger/state authority;
- repeated calls and migration-context calls create zero mutation;
- stable ordering/serialization test with deliberately permuted input collections.

## Permanent regression gate across 17–20

Every Phase 21–25 candidate must retain representative permanent tests proving:

- PlayerChangeSet remains proposal;
- PlayerDomainEngine resolution performs zero authoritative mutation;
- reference closure remains complete;
- one pinned World Pack authority per resolution;
- same pinned authority for PRECHECK/EFFECT_CHECK;
- WorldRuleProvider remains read-only;
- progression-generated effects remain before final DRAFT_EFFECT_CHECK;
- Phase-20 deterministic identity/canonical factor regression remains green;
- progression ledger stays proposal evidence until later authorized commit architecture.

---

# 15. Specific integrity blockers / required preconditions

No repository defect currently requires modification **before Phase 21 can begin**, but the following architectural preconditions are mandatory for the 21–25 program:

### PRECONDITION PC-21-01 — causal adaptation state

Any stateful novelty/adaptation mechanism must expose its state/version as explicit deterministic input. Hidden evaluation memory is forbidden.

### PRECONDITION PC-22-01 — semantic negative-change classification

No-Retrogression must distinguish durable earned progression from resources, derived effects, temporary modifiers, equipment and legal transformations. A sign-only validator is an acceptance blocker.

### PRECONDITION PC-23-01 — bounded ledger scope before TurnTransaction

Phase 23 must remain at intent/provenance/read-projection level plus existing domain-local authoritative ledgers. **No new globally committed unified player ledger authority** may be introduced before an authorized atomic commit owner exists.

### PRECONDITION PC-23-02 — P20-CB-01 disposition

Phase 23 must explicitly classify progression `evidenceRefs` as legality-only vs durable provenance. Durable refs must propagate prospectively; old missing refs remain unknown/not-recorded.

### PRECONDITION PC-24-01 — snapshot cannot be authority

CharacterPanelSnapshot v2 must be demonstrably disposable. Current raw legacy-reader behavior must not be promoted as source-of-truth semantics.

### PRECONDITION PC-25-01 — truth-aware projection semantics

GM_CONTEXT and other profiles must retain truth/knowledge classifications and omission semantics. A profile may never be used to infer that omitted reality does not exist.

If CHAT-1 cannot satisfy any precondition within the owning phase without crossing forbidden scope, it must STOP and return the decision to the coordinator rather than implementing a compensating shortcut.

---

# 16. Recommended forbidden scope for CHAT-1

For the Player Core Completion implementation sequence, CHAT-1 should not:

- implement TurnTransaction/global commit/rollback/idempotency/crash recovery;
- create a new globally authoritative unified ledger writer;
- move finance away from its accepted authoritative ledger in Phase 23;
- turn progression ledger intents into committed truth;
- create a second PlayerDomainEngine or World-Pack progression engine;
- add scheduler/time-skip orchestration as “passive hooks”;
- write progression during load/migration/snapshot/profile generation;
- reinterpret legacy mastery/XP/provenance automatically;
- fabricate sourceEventUid/evidenceRefs/commandUid for historical data;
- treat CharacterPanelSnapshot or any PlayerSnapshot profile as save/restore authority;
- use presentation strings/names as stable identities;
- collapse FACT/BELIEF/NARRATIVE in GM_CONTEXT;
- repair TEST-GM findings as part of this runtime work;
- implement Phase 26+ under the guise of integration.

---

# 17. Coordinator recommendation

Proceed sequentially, not as one broad implementation batch:

1. **Phase 21** — extend the accepted deterministic ProgressionEngine contract only; prove deterministic causal adaptation/passive-hook behavior and stop-gate tests.
2. **Phase 22** — add semantic invariant validation with explicit legal-regression cause; preserve WorldRuleProvider separation.
3. **Phase 23** — unify provenance/ledger **contracts and read semantics**, not global commit authority; resolve P20-CB-01 prospectively with backward compatibility.
4. **Phase 24** — make CharacterPanelSnapshot v2 a typed, stable-UID, exact-value derived projection over canonical readers; deletion/rebuild must be harmless.
5. **Phase 25** — introduce deterministic specialized projections with explicit omission and truth-layer semantics.

Run an independent stop-gate review after each phase. Do not allow later presentation/profile phases to mask authority inconsistencies introduced earlier.

## FINAL VERDICT

**READY WITH REQUIRED PRECONDITIONS**

The current accepted Phase-20 runtime and current documentation-only master drift provide a viable base for Player Core Completion. The program is safe to start only if Phase 23 remains bounded before TurnTransaction, Phase 22 does not become a blanket negative-delta ban, Phase 21 preserves explicit causal determinism, P20-CB-01 is handled prospectively without fabricated history, and Phase 24/25 remain strictly disposable deterministic projections rather than new authorities.

**PHASES 21–25 remain NOT COMPLETE / NOT ACCEPTED.**
