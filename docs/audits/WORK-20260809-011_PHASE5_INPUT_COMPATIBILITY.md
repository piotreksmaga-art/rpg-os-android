# WORK-20260809-011 — Phase 5 Input Compatibility Audit

Status: READ-ONLY / PHASE 5 INPUT AUDIT

Work ID: `WORK-20260809-011`
Owner: `CHAT-2`
Role: PHASE 5 INPUT COMPATIBILITY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Runtime under audit: `91763b733d9ed3eaa3d804c77394fb7f87b7be3b` — `WORK-20260809-006 — add lossless Phase 4 legacy read-through`
Prior Phase 5 test contract: `bf45f37fea36c7f148852cca3bc717329b000e1f`

This audit changes documentation only. It does not implement Phase 5, modify Kotlin runtime, schema, migrations, repository APIs, MASTER, ROADMAP, or coordination.

---

## 1. Executive result

WORK-006 materially improves old-campaign compatibility and makes legacy values consumable through the Phase 4 typed surface. Most of the compatibility projection is aligned with the Phase 5 authority model:

- legacy `character_stats.current_value` is projected as `PlayerStat.baseValue`,
- safely identifiable current resource fields are projected as `PlayerResource.currentValue`,
- legacy max/effective/regeneration fields are excluded from authoritative base/current promotion,
- synthetic legacy UIDs are deterministic,
- the compatibility World Pack/UID namespace is reserved against normal World Pack registration,
- player reads remain campaign + character scoped.

However the current merge policy is UID-only. It has no semantic reconciliation between a typed definition and a legacy compatibility definition carrying the same logical key.

Therefore a mixed campaign can expose, for the same player:

```text
legacy character_stats: key = "strength"
-> StatDefinition(key="strength", worldPackUid="RPGOS-LEGACY-COMPAT", statUid=RPGOS-LEGACY-STAT-<hash>)
-> PlayerStat(statUid=legacyUid, baseValue=...)

plus

typed StatDefinition(key="strength", worldPackUid="WORLD-A", statUid="WORLD-A-STRENGTH")
-> PlayerStat(statUid="WORLD-A-STRENGTH", baseValue=...)
```

Because the UIDs differ, `mergeDefinitionsByUid()` and `mergeValuesByUid()` retain both records. A future resolver correctly keyed by stable UID would therefore see two different stat nodes. Without an explicit reconciliation/alias rule, Core cannot know whether they are truly separate concepts or duplicate representations of one semantic stat.

This is not safe as the final input contract for Phase 5.

---

## 2. Question 1 — legacy `character_stats.current_value` -> `PlayerStat.baseValue`

### Assessment: compatible, with an explicit compatibility boundary

`LegacyStatResourceCompatibility.playerStats()` reads each legacy row by source `entity_uid`, maps its `stat_key` to a deterministic synthetic UID, and constructs:

```text
PlayerStat(
  campaignId = current campaign,
  characterUid = source entity UID,
  statUid = deterministic legacy UID(stat_key),
  baseValue = legacy current_value,
  version = 1
)
```

This matches the accepted Phase 4/5 authority split in which `PlayerStat.baseValue` is authoritative persistent progression/base state and `ResolvedStat.effectiveValue` is derived.

The compatibility adapter does not apply modifiers and does not reinterpret any derived status field as a stat base.

Important qualification: the legacy column name `current_value` is historical naming. Under the compatibility contract it is explicitly projected as the persistent stat value, not as a Phase-5 effective value. The resolver must consume the typed `PlayerStat.baseValue` contract, never infer semantics from the legacy column name.

Result: **PASS for authority semantics.**

---

## 3. Question 2 — resource compatibility safety

### Assessment: generally safe and conservative

`LegacyStatResourceCompatibility.resourceColumns()` examines `character_status_snapshot` schema and promotes only columns whose structural naming can be interpreted as a current resource without universe-specific vocabulary.

Accepted generic shapes include:

- `current_resource_<key>`,
- `resource_<key>_current`,
- `current_<key>` when it has a max sibling or Phase 3 already classifies it RUNTIME,
- `<key>_current` when a max sibling exists,
- bare `<key>` only when a max sibling exists and Phase 3 classifies the bare field RUNTIME.

This avoids hardcoding chakra, reiatsu or any other World Pack mechanic in the compatibility adapter.

For status snapshots without `entity_uid`, compatibility requires exactly one row and exposes it only for the already-authoritative active player. It does not invent/select a first player.

Result: **PASS for conservative current-resource promotion**, subject to the semantic-duplicate blocker described later.

---

## 4. Question 3 — derived legacy fields remain excluded

### Assessment: PASS

Before evaluating current-resource shapes, the adapter rejects any column classified by `PlayerStatePolicy` as DERIVED.

The Phase 3 policy classifies at least:

- `effective_*`,
- `derived_*`,
- `max_*`,
- `regeneration`,
- `net_worth`,
- `combat_rating`

as DERIVED.

The compatibility code also explicitly documents that max/effective/regeneration columns are not promoted into `PlayerResource`.

Therefore:

```text
legacy max_*            != PlayerResource.currentValue
legacy effective_*      != PlayerStat.baseValue / PlayerResource.currentValue
legacy regeneration     != authoritative current resource
```

This is exactly what Phase 5 requires: maximums, regeneration and effective values must be rebuildable derived output rather than an authoritative resolver input.

Result: **PASS.**

---

## 5. Question 4 — deterministic synthetic legacy UIDs

### Assessment: PASS for stability/identity

Legacy stat/resource UIDs are generated as:

```text
RPGOS-LEGACY-STAT-<SHA-256(key UTF-8)>
RPGOS-LEGACY-RESOURCE-<SHA-256(key UTF-8)>
```

For the same exact key bytes this is deterministic across reopen/replay and does not depend on row order, database rowid, active player, campaign turn or process state.

This is suitable for resolver node identity and deterministic caching.

Important limitation: the identity is based on the exact legacy key string. It is intentionally not a semantic canonicalization mechanism. For example different labels/casing or a later typed World Pack UID do not automatically become aliases. That is correct for lossless compatibility, but it is also why semantic reconciliation is still required for mixed old/new data.

Result: **PASS for deterministic identity; NOT a semantic deduplication mechanism.**

---

## 6. Question 5 — `RPGOS-LEGACY-COMPAT` ownership isolation

### Assessment: PASS

The implementation reserves:

- World Pack UID `RPGOS-LEGACY-COMPAT`,
- stat UID prefix `RPGOS-LEGACY-STAT-`,
- resource UID prefix `RPGOS-LEGACY-RESOURCE-`.

Normal registration calls `requireWorldPackNamespaceAvailable()` and `requireDefinitionUidAvailable()`, rejecting attempts by a World Pack to register in those reserved identities.

Reads additionally fail loudly if persisted definitions/player values are found using reserved compatibility identities.

This prevents a normal World Pack from impersonating the compatibility projection and protects resolver provenance/identity assumptions.

Result: **PASS.**

---

## 7. Question 6 — can Phase 5 treat compatibility definitions like normal definitions?

### Assessment: YES mechanically, NO semantic special-casing by universe

Once exposed, compatibility objects conform to ordinary:

- `StatDefinition`,
- `ResourceDefinition`,
- `PlayerStat`,
- `PlayerResource`.

A generic resolver can therefore process them through the same type-level mechanics. No Naruto/Bleach branch is needed or allowed.

Compatibility definitions intentionally have no Phase-5 rule bindings unless later mapped:

- legacy stat `derivationRuleUid` is null,
- legacy resource `maxRuleUid` is null,
- legacy resource `regenerationRuleUid` is null.

A resolver should therefore treat them as ordinary primitive/base/current definitions for which no derived rule was declared, not infer a formula from the key text.

If a World Pack later supplies an explicit reconciliation/mapping, that mapping must live outside generic Core literals and must be versioned/provenance-bearing.

Result: **PASS mechanically, provided semantic reconciliation is completed before duplicate logical concepts are resolved together.**

---

## 8. Question 7 — mixed typed + legacy campaign

### Assessment: BLOCKER for Phase 5 input contract

This is the decisive issue.

`StatResourceStore.statDefinitions()` merges persisted and compatibility definitions by `statUid` only.

`StatResourceStore.playerStats()` merges persisted and compatibility player values by `statUid` only.

The same pattern exists for resources with `resourceUid`.

There is no check/alias/reconciliation on:

- logical `key`,
- World Pack mapping,
- migration provenance,
- explicit legacy -> typed definition alias,
- source precedence saying typed representation supersedes a named legacy semantic.

Because synthetic compatibility UIDs are deliberately different from typed World Pack UIDs, the following state is legal under the current read contract:

```text
legacy key "strength" -> legacy UID L
new typed key "strength" -> typed UID T
L != T
```

The merged read returns both `L` and `T`.

The same applies to a resource such as a legacy current key and a new typed resource definition with the same semantic key.

### Why the resolver cannot safely solve this by itself

A generic `DerivedValueResolver` must trust stable UID identity. It must not collapse definitions merely because display/key strings happen to match, because:

- different World Packs may legally reuse a key for different concepts,
- two definitions can legitimately share a human-readable semantic label but have different rules/units/scopes,
- Core cannot know whether legacy `strength` is exactly equivalent to the active World Pack's typed `strength`,
- automatic key-based collapse would violate the stable-UID model and could discard real state.

Conversely, resolving both independently can double-represent the same real statistic and lead to incorrect formulas, panel state, modifier targeting or progression continuity.

Therefore the input producer must establish an explicit reconciliation policy before Phase 5 treats the mixed graph as canonical.

### Required resolution contract

At least one of the following must exist before Phase 5 implementation consumes mixed data as authoritative input:

1. **Explicit legacy alias/mapping:** a versioned mapping declares `legacy UID -> typed definition UID`, with compatibility/provenance rules and conflict handling.
2. **Audited typed supersession:** when a typed definition/value is proven to be the migrated canonical representation of a specific legacy key, the read path suppresses only that mapped legacy projection and preserves all unmapped legacy facts.
3. **Fail-loud ambiguity:** if both representations exist and no mapping proves equivalence/non-equivalence, Phase-5 input assembly rejects the ambiguous mixed state rather than silently resolving both as one character graph.

What is forbidden:

- implicit key equality in Core,
- silently preferring typed data solely because it is newer,
- silently preferring legacy data,
- deleting the legacy fact before mapping is proven,
- allowing both into formulas that expect one logical stat/resource while pretending no ambiguity exists.

### Classification

This is best classified as **Phase 4 compatibility debt that is a blocking precondition for Phase 5 input assembly**. WORK-006 successfully provides lossless read-through, but lossless coexistence is not yet semantic reconciliation.

It does not require implementing modifier arithmetic or DerivedValueResolver to fix.

---

## 9. Resolver input safety matrix

| Input aspect | Result | Phase 5 assessment |
|---|---|---|
| legacy stat numeric value -> `PlayerStat.baseValue` | PASS | Compatible with authoritative persistent base semantics |
| source player identity | PASS | Uses source `entity_uid`; active-player lookup only guards unscoped single-row status snapshot |
| campaign identity | PASS | Projected value carries requested campaign ID and store is campaign scoped |
| legacy current-resource promotion | PASS | Conservative structural classification, universe-neutral |
| max fields | PASS | Not promoted to authoritative current/base |
| effective fields | PASS | Not promoted |
| regeneration fields | PASS | Not promoted |
| deterministic legacy stat UID | PASS | SHA-256 of exact key with reserved prefix |
| deterministic legacy resource UID | PASS | SHA-256 of exact key with reserved prefix |
| reserved namespace | PASS | World Pack/definition registration guard exists |
| normal generic resolver type compatibility | PASS | Compatibility produces ordinary Phase 4 contract objects |
| Naruto/Bleach special cases | PASS | None required in compatibility/resolver Core |
| persisted vs legacy same UID collision | PASS / FAIL-LOUD | Reserved identities make collision invalid |
| persisted vs legacy same semantic key, different UID | **BLOCKER** | Both can be emitted; no semantic reconciliation/alias contract |
| resolver graph uniqueness by logical concept | **BLOCKED** | Cannot be guaranteed for mixed campaigns |

---

## 10. Required Phase 5 preflight invariants

Before calling `DerivedValueResolver`, future input assembly must prove:

1. every stat/resource node has one stable UID identity;
2. no unresolved legacy/typed pair is known or suspected to represent the same logical mechanic;
3. any legacy -> typed alias is explicit, versioned and provenance-bearing;
4. alias resolution cannot be inferred from universe-specific literal names in Core;
5. unresolved ambiguity fails before formula/dependency graph construction;
6. legacy max/effective/regeneration values never enter the authoritative base/current set;
7. compatibility values remain source-attributable to `RPGOS-LEGACY-COMPAT`;
8. current resources remain observed authoritative current quantities; resolver never writes them;
9. reconciliation never rewrites `PlayerStat.baseValue` as a side effect of resolution;
10. cache fingerprints include the reconciliation/mapping version in addition to rule-provider version.

---

## 11. Required future tests added to the Phase 5 gate

### C01 — legacy-only stat

Legacy `strength=10`; no typed equivalent.

Expected: exactly one resolver stat node with legacy UID and base 10.

### C02 — typed-only stat

Typed `strength=12`; no legacy row.

Expected: exactly one typed node.

### C03 — mixed same key without mapping

Legacy `strength=10` + typed `strength=12`, different UIDs, no alias.

Expected before resolver: deterministic ambiguity error. Resolver is not invoked.

### C04 — mixed same key with explicit alias

Legacy UID mapped to typed UID with declared supersession/migration provenance.

Expected: exactly one canonical stat node according to mapping policy; no double modifier application.

### C05 — same key but explicitly distinct concepts

A mapping/rule explicitly declares the legacy and typed definitions non-equivalent.

Expected: both nodes may survive with distinct stable identities and explicit provenance.

### C06 — mixed resource same semantic key

Equivalent to C03/C04 for `PlayerResource`.

### C07 — World Pack update

Mapping version changes after a pack update.

Expected: Phase-5 input fingerprint changes and derived cache invalidates.

### C08 — no key-only auto-collapse

Two different World Pack definitions share display/key text.

Expected: Core does not merge them without an explicit mapping.

### C09 — legacy unknown/custom key

Unknown legacy key with no typed counterpart.

Expected: preserved as compatibility node; not dropped merely because no rule binding exists.

### C10 — compatibility derived-field exclusion

Fixture contains current, max, effective and regeneration fields.

Expected authoritative input contains only current resource field; derived fields never enter base/current sets.

---

## 12. Relationship to WORK-006 / Phase 4 validation

WORK-006 solved the previous blocking problem in which an old campaign could have real legacy data while typed reads returned empty lists. It now exposes that data losslessly through Phase 4 contract objects.

That is a major improvement and may be sufficient for Phase 4's own lossless old-campaign compatibility gate depending on CHAT-3/CHAT-5 final validation.

This audit is narrower and asks whether the merged read is already safe as the canonical input graph for Phase 5. On that narrower question the mixed semantic duplicate case is unresolved.

Therefore this report does **not** claim that WORK-006 should be reverted or that legacy read-through is architecturally wrong. It identifies one required reconciliation layer/contract before Phase-5 formula resolution begins.

---

# Final verdict

**PHASE 5 INPUT CONTRACT: BLOCKED**

Blocking condition:

> The current compatibility merge is UID-only and can expose a legacy definition/value and a typed World Pack definition/value with the same logical semantic key as two independent resolver nodes. No explicit alias/supersession/ambiguity-rejection contract currently reconciles that mixed state.

Required unblock criterion:

> Before Phase 5 implementation consumes mixed campaigns, introduce an explicit, deterministic, versioned and provenance-bearing legacy-to-typed reconciliation policy (or fail-loud ambiguity gate) that preserves unknown legacy data and does not use universe-specific Core literals.
