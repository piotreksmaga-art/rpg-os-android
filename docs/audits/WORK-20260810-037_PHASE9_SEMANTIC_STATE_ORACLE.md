# WORK-20260810-037 — Phase 9 Semantic / State-Machine Oracle

Status: FINAL SEMANTIC RECHECK / READ-ONLY RUNTIME

Work ID: `WORK-20260810-037`
Owner: `CHAT-2`
Role: PHASE 9 SEMANTIC / STATE-MACHINE ORACLE
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase 8 runtime: `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`
Audited Phase 9 runtime only: `d796d374f92d94477542da5f753ee411b633076b`
Original oracle commit: `90e584a73e7ee0066f952508328de42e22f6fdf8`

This final recheck is read-only with respect to application runtime. No Kotlin, schema, migration, tests, or authoritative runtime state were modified by CHAT-2. This document consolidates the oracle and records the final comparison against the exact requested Phase-9 result commit.

---

## 1. Canonical semantic oracle

Phase 9 must preserve these independent authorities:

- origin/species identity != clan identity,
- identity != innate feature ownership,
- innate ownership != evolution state,
- unlocked form != active form,
- evolution stage != temporary transformation,
- Talent/Potential/Skill/Technique remain separate Phase 6–8 authorities.

A World Pack may relate these concepts only through stable UID-addressed explicit definitions/mappings/rules. Text labels are not identity.

### Identity/origin

`PlayerOrigin` represents persistent character identity/origin relationship. It must not imply `PlayerInnateFeature`. `clan_uid` is legacy/canon evidence and must not itself grant a bloodline feature.

### Innate ownership

`PlayerInnateFeature` is durable owned state. Temporary suppression, form deactivation, injury, resource depletion, modifier lifecycle, or other runtime condition must not silently remove it. Temporary effects cannot create persistent ownership.

### Evolution

Evolution is a World-Pack-defined graph/track. Stage identity is stable UID identity, not label ordering or a global numeric level. Historical attainment and current stage are separate facts. A current-stage change requires an explicit transition. Cross-path movement requires explicit cross-path semantics.

Rollback/reversal from B to previously attained A is not legal merely because A exists in history. It requires an explicit legal transition/reversal semantic or later legal domain mutation path.

### Forms

Canonical lifecycle:

```text
LOCKED
  -> UNLOCKED + INACTIVE
  -> ACTIVE
  -> INACTIVE
  -> ACTIVE ...
```

Unlock is persistent authority. Activity is current/reversible state. Deactivation must never delete the unlock.

### Temporary transformation

Temporary form/transformation effects belong to runtime/derived mechanics. Activation may affect Phase-5 effective targets, but deactivation must remove those effects while preserving authoritative Phase 3–8 bases and Phase-9 ownership/history.

---

## 2. Requirement oracle

Three semantic phases must remain distinct:

1. `UNLOCK` requirement — decides whether durable ownership/unlock may be granted.
2. `TRANSITION` requirement — decides whether persistent evolution transition may be committed.
3. `ACTIVATION` requirement — decides whether a reversible form may currently activate/remain active.

They may eventually share generic rule infrastructure, but they are not interchangeable.

Consequences are different:

- failed unlock requirement => no durable grant,
- failed transition requirement => current evolution stage remains unchanged,
- failed activation requirement => activation is blocked/terminated according to World Pack rules, but existing unlock and evolution history remain.

A temporary condition becoming false after a legal permanent unlock/transition must not retroactively erase that committed persistent state unless a separate explicit revocation/reversal mechanic exists.

---

## 3. Exact runtime inspected

Only runtime commit:

`d796d374f92d94477542da5f753ee411b633076b`

was used for this final semantic verdict.

The Phase-9 runtime contains distinct typed models for:

- `OriginDefinition` / `PlayerOrigin`,
- `InnateFeatureDefinition` / `PlayerInnateFeature`,
- `EvolutionPathDefinition`,
- `EvolutionStageDefinition`,
- `EvolutionTransitionDefinition`,
- `PlayerEvolutionState`,
- `PlayerEvolutionStage`,
- `FormDefinition`,
- `PlayerFormUnlock`,
- `PlayerActiveForm`,
- explicit legacy evidence/mapping records,
- generic Phase-5 form modifier bindings.

This is structurally aligned with the oracle: identity, feature ownership, evolution progression/history and form activation are not collapsed into one value.

---

## 4. Final semantic recheck matrix

| Gate | Runtime result | Verdict |
|---|---|---|
| origin/species identity != clan identity | `PlayerOrigin` is typed independently; legacy `clan_uid` remains evidence | PASS |
| identity != innate ownership | separate `player_origins_v2` and `player_innate_features`; no implicit origin->feature grant | PASS |
| innate ownership != evolution state | separate player innate/evolution state models and persistence | PASS |
| unlocked form != active form | separate `PlayerFormUnlock` and `PlayerActiveForm` | PASS |
| temporary transformation != permanent evolution | form activation produces Phase-5 modifier lifecycle; evolution state is separate | PASS |
| deactivation preserves unlock | persistence test explicitly deactivates and retains A/B unlock rows | PASS |
| evolution requires explicit transition | `transitionEvolution` consumes `transitionUid`, validates current source and rejects missing/wrong transition | PASS |
| historical stages survive transition | attained A and B are both retained after A->B | PASS |
| cross-path transition explicit | registration/runtime reject cross-path unless `crossPathAllowed` | PASS |
| rollback requires legal transition semantics | direct reuse of A->B from current B fails; no direct arbitrary stage setter exposed in audited flow | PASS WITH GAP |
| temporary condition cannot erase persistent unlock/history | form lifecycle never deletes unlock/attained-stage authority | PASS |
| Phase 3–8 no-retrogression | active form changes effective targets through generic Phase-5 modifiers; stat/Skill/Technique bases remain unchanged; Talent/Potential unchanged | PASS |
| legacy labels/clan_uid automatic grant forbidden | legacy labels are preserved as evidence; no typed state appears until explicit mapping | PASS |
| explicit legacy mapping canonicalizes once | idempotent mapping application creates one canonical target while legacy bytes remain | PASS |
| UNLOCK / TRANSITION / ACTIVATION requirement semantics enforced | runtime does not provide three enforceable requirement contracts | **FAIL** |

---

## 5. Blocking semantic finding — requirement contracts are incomplete

The final runtime does not satisfy the oracle's required semantic separation of `UNLOCK`, `TRANSITION`, and `ACTIVATION` requirements as enforceable state-machine gates.

### 5.1 Transition requirement is persisted but not enforced

`EvolutionTransitionDefinition` contains:

```text
requirementRuleUid
```

and transition registration persists it.

However `transitionEvolution(...)` validates transition identity, source/current-stage correctness and cross-path legality, then commits the state transition. In the audited runtime it does not evaluate `requirementRuleUid` before mutating persistent evolution state.

Therefore a transition carrying a non-null requirement binding can still be committed without proof that the transition requirement passed.

This conflicts with the oracle invariant:

```text
failed transition requirement
=> current stage remains unchanged
```

### 5.2 Activation requirement is persisted but not enforced

`FormDefinition` contains:

```text
activationRuleUid
```

and registration persists it.

`activateForm(...)` verifies durable form unlock, definition activity and exclusivity. In the audited runtime it does not evaluate `activationRuleUid` before creating active form state / Phase-5 form modifier effects.

Therefore a form carrying an activation requirement can become active without proof that the activation requirement passed.

This conflicts with the oracle invariant that activation eligibility is a distinct current-state gate and may fail while the durable unlock remains intact.

### 5.3 Unlock requirement has no equivalent explicit runtime contract

The audited Phase-9 model exposes `requirementRuleUid` for transitions and `activationRuleUid` for forms, but no equivalent explicit unlock-requirement binding on `FormDefinition`/unlock operation (nor a generic typed unlock requirement object covering feature/form/path/stage grants).

`unlockForm(...)` validates identity, scope and definition existence and then persists the unlock. It therefore cannot distinguish:

```text
legal unlock after UNLOCK requirement success
```

from:

```text
direct durable unlock with no requirement evaluation
```

within Phase-9 runtime itself.

This is the decisive semantic mismatch requested by the final recheck.

---

## 6. Rollback/reversible semantic note

`EvolutionTransitionDefinition` contains a `reversible` flag, while `transitionEvolution(...)` primarily enforces explicit transition identity and source/target legality. The existing test correctly proves that historical attainment of A does not permit an arbitrary B->A rollback and that replaying A->B while current=B fails.

However the audited runtime does not establish a complete generic interpretation of the `reversible` flag itself. A future fix should make reversal semantics explicit rather than leaving the flag as persisted metadata with no clear enforcement role.

This issue reinforces the requirement/state-machine gap but is not needed independently to reach the FAIL verdict.

---

## 7. No-retrogression recheck

The exact final commit adds an integration test proving active form effects flow through the accepted Phase-5 `DerivedValueResolver`:

```text
Stat base 10 -> effective 15 while active -> effective 10 after deactivate
Resource max 100 -> 125 while active -> 100 after deactivate
Resource current remains 40
Skill base 20 -> effective 26 -> 20
Technique base 30 -> effective 37 -> 30
```

Separate Phase-9 persistence coverage verifies that form activation does not rewrite:

- `PlayerStat.baseValue`,
- `PlayerSkill.baseMastery`,
- `PlayerTechnique.baseMastery`,
- Talent persistent base value,
- Potential persistent base value.

This satisfies the requested Phase 3–8 no-retrogression boundary for the audited transformation path.

---

## 8. Legacy/evidence recheck

The runtime preserves conservative canonicalization:

```text
legacy label / clan_uid
-> evidence only
-> no automatic typed grant
```

The test fixture containing `clan_uid`, `race`, `bloodline`, `evolution_stage` and `form` creates no automatic `PlayerOrigin` or `PlayerInnateFeature`. Explicit `LegacyPhase9Mapping` is required to canonicalize, repeated application is idempotent, and original legacy bytes remain unchanged.

This matches the oracle and coordinator instruction.

---

## 9. Final verdict

The core Phase-9 state separation is strong and the runtime correctly preserves the most important no-retrogression, unlock-vs-active, explicit-transition, legacy-evidence and derived-effect boundaries.

The final semantic gate nevertheless cannot PASS because the oracle explicitly requires `UNLOCK`, `TRANSITION`, and `ACTIVATION` to remain semantically distinct requirement contracts. At `d796d374f92d94477542da5f753ee411b633076b`, transition and activation rule UIDs are stored but not enforced by their mutation operations, while unlock has no corresponding explicit requirement binding/evaluation contract.

This permits persistent transition or current activation without proof that the appropriate requirement phase succeeded, and it leaves no typed Phase-9 mechanism for an unlock requirement to be evaluated separately.

No runtime fix was made by CHAT-2.

# PHASE 9 SEMANTIC RECHECK: FAIL
