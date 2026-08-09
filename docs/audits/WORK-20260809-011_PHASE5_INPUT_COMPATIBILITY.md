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

## Original WORK-011 finding

WORK-006 made legacy data visible through the typed Phase 4 surface but merged typed and compatibility records by UID only. A mixed campaign could therefore expose a legacy definition/value and a typed World Pack definition/value with the same apparent semantic key as two independent resolver nodes. The original verdict was:

`PHASE 5 INPUT CONTRACT: BLOCKED`

Required unblock criterion was an explicit, deterministic, versioned and provenance-bearing legacy-to-typed reconciliation policy or a fail-loud ambiguity gate that preserves unknown legacy data without universe-specific Core literals.

---

# FOLLOW-UP GATE AFTER WORK-20260809-014

Runtime rechecked: `6bdde251a3ef293a0cfa85c818538da4cc1307eb` — `WORK-20260809-014 — reconcile legacy and typed stat/resource identities`.

## 1. Explicit reconciliation model

WORK-014 introduces explicit persisted mappings:

- `LegacyStatAlias`
- `LegacyResourceAlias`
- `legacy_stat_aliases`
- `legacy_resource_aliases`

Each alias is campaign-scoped and contains:

- deterministic legacy UID,
- canonical typed UID,
- owning `worldPackUid`,
- `mappingVersion >= 1`,
- nonblank provenance.

Canonical targets must exist as normal typed definitions and their actual World Pack owner must match the alias owner. Reserved legacy UIDs cannot be used as canonical targets.

Assessment: **PASS**.

## 2. Deterministic legacy identity

`LegacyCompatibilityIdentity` preserves deterministic SHA-256-based UIDs for exact legacy keys and keeps the reserved compatibility namespace:

- `RPGOS-LEGACY-COMPAT`
- `RPGOS-LEGACY-STAT-*`
- `RPGOS-LEGACY-RESOURCE-*`

World Packs still cannot register into the reserved namespace.

Assessment: **PASS**.

## 3. Unmapped mixed stat ambiguity

`reconcileStatDefinitions()` now checks every unmapped legacy stat against persisted typed definitions when canonical mixed reads are requested. If an unmapped legacy definition has the same key as a persisted typed definition, the read fails with an explicit unresolved semantic ambiguity error requiring an alias.

This does **not** merge by key. Key equality is used only as a conservative ambiguity detector. No representation is silently selected and no legacy bytes are destroyed.

Assessment: **PASS** for the required fail-loud contract.

## 4. Mapped mixed stat

After a valid `LegacyStatAlias` exists:

- the mapped legacy definition is suppressed from canonical mixed definitions,
- the legacy player value is projected to the canonical typed stat UID,
- if a real persisted typed player value already exists for that UID, the typed persisted representation remains canonical,
- unrelated unmapped legacy values remain visible,
- legacy source rows remain physically unchanged.

The resolver therefore receives at most one canonical node for the mapped logical stat.

Assessment: **PASS**.

## 5. Resource reconciliation

The resource path mirrors the stat path:

- explicit `LegacyResourceAlias`,
- campaign-scoped version/provenance,
- target World Pack ownership validation,
- fail-loud same-key ambiguity when unmapped,
- mapped legacy definition suppression,
- legacy current value projected to canonical typed resource UID,
- persisted typed current value remains canonical if present,
- no promotion of max/effective/regeneration into current authoritative input.

Assessment: **PASS**.

## 6. Same text key across World Packs

WORK-014 does not globally collapse typed definitions by key. World Pack-owned typed definitions retain stable UID identity. A legacy same-key candidate is considered unresolved until an explicit alias declares which typed UID, if any, supersedes it.

This is conservative but correct for Phase 5: Core does not infer that `same key == same concept`.

Assessment: **PASS**.

## 7. Phase 5 authority semantics

The reconciliation layer does not alter the accepted authority split:

- `PlayerStat.baseValue` remains authoritative persistent base/progression state,
- `PlayerResource.currentValue` remains authoritative current quantity,
- effective values, maximum resource values and regeneration remain future DERIVED outputs,
- reconciliation does not apply modifiers or mutate base progression,
- mapped legacy values are identity reconciliation, not derived-value computation.

Assessment: **PASS**.

## 8. Resolver preflight impact

The previous WORK-011 blocker is removed. Future Phase 5 input assembly can safely consume the Phase 4 typed reads under these rules:

1. mapped legacy/typed pairs collapse to the canonical typed UID;
2. unmapped same-key mixed pairs fail before resolver graph construction;
3. unmapped unrelated legacy definitions remain valid compatibility nodes;
4. alias `mappingVersion` and provenance should participate in the future resolver input/cache fingerprint;
5. no resolver-specific Naruto/Bleach special cases are required.

## 9. Tests inspected

The Phase 4 persistence test suite still covers legacy-only stats/resources, player/campaign isolation, reopen stability, unknown legacy data, derived-field exclusion and fail-loud legacy conflicts. WORK-014 adds reconciliation behavior in the runtime commit and migration marker `RPGOS-4.1-LEGACY-RECONCILIATION`.

For the Phase 5 implementation gate, retain explicit future tests for:

- unmapped mixed stat -> ambiguity error before resolver,
- mapped mixed stat -> exactly one canonical typed node,
- unmapped mixed resource -> ambiguity error,
- mapped mixed resource -> exactly one canonical typed node,
- alias version change -> input/cache fingerprint change,
- no key-only auto-collapse between typed definitions.

## 10. CI observation

GitHub connector combined-status and PR-workflow lookup returned no status entries for `6bdde251a3ef293a0cfa85c818538da4cc1307eb` at audit time. This report therefore does not independently claim CI success; coordinator/CHAT-3 should use the repository's actual Actions run evidence as the CI gate.

---

# Follow-up verdict

**PHASE 5 INPUT CONTRACT: READY**

The specific WORK-011 blocker is resolved by WORK-014. The reconciliation contract is explicit, deterministic, persisted, versioned, provenance-bearing, campaign-scoped and World-Pack-safe; mapped legacy representations are suppressed in favor of canonical typed identity, while unresolved same-key mixed state fails loudly instead of entering the future resolver as duplicate authoritative nodes.

This verdict clears the CHAT-2 input-compatibility gate only. Phase 5 implementation must still wait for the coordinator's formal Phase 4 completion decision and CHAT-3 final narrow revalidation.