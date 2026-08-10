# WORK-20260810-037 — Phase 9 Semantic / State-Machine Oracle

Status: FINAL SEMANTIC REVALIDATION / READ-ONLY RUNTIME

Work ID: `WORK-20260810-037`
Owner: `CHAT-2`
Role: READ-ONLY SEMANTIC AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase 8 runtime: `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`
Previous Phase 9 runtime: `d796d374f92d94477542da5f753ee411b633076b`
Previous semantic verdict: `PHASE 9 SEMANTIC RECHECK: FAIL`
Final hotfix audited: `c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`
Original oracle commit: `90e584a73e7ee0066f952508328de42e22f6fdf8`

This report is documentation-only. CHAT-2 did not modify Kotlin runtime, schema, migrations, tests, authoritative state, or any Phase-10 subsystem.

---

## 1. Revalidation scope

The final revalidation compares the exact hotfix runtime `c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87` against the semantic/state-machine contract previously defined by WORK-037.

The prior FAIL at `d796d374f92d94477542da5f753ee411b633076b` was caused by a specific semantic gap:

- transition requirement UID existed but was not enforced before evolution mutation;
- activation requirement UID existed but was not enforced before active state/modifier activation;
- no explicit UNLOCK requirement contract was enforced before durable form unlock.

The hotfix introduces a generic `RequirementEvaluator`, explicit `RequirementGate` values `UNLOCK`, `TRANSITION`, `ACTIVATION`, versioned bindings, deterministic dependency evaluation, and additive migration `RPGOS-9.1-REQUIREMENT-GATES`.

---

## 2. Canonical semantic separation remains intact

The hotfix does not collapse Phase-9 authorities.

The runtime still keeps separate:

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
- unresolved legacy evidence / explicit World Pack mapping.

Therefore the following invariants remain valid:

```text
origin/species identity != clan identity
identity != innate ownership
innate ownership != evolution state
unlocked form != active form
evolution stage != temporary transformation
Talent != Potential != Skill != Technique != Phase-9 identity/state
```

No hotfix path introduces automatic origin/clan/label-to-feature grants.

---

## 3. Generic requirement contract

The hotfix adds:

```text
RequirementGate.UNLOCK
RequirementGate.TRANSITION
RequirementGate.ACTIVATION
```

A `RequirementBinding` is explicitly versioned by:

```text
ruleUid
ruleVersion
```

A provider exposes a descriptor containing:

```text
ruleUid
version
allowedGates
dependencies
```

and evaluates using a `RequirementContext` containing the exact semantic gate and subject UID.

This is a generic Core contract; it contains no Naruto/Bleach mechanics.

### 3.1 Gate non-substitution

`RequirementEvaluator` checks:

```text
context.gate in descriptor.allowedGates
```

Therefore a TRANSITION-only rule cannot satisfy UNLOCK, an UNLOCK-only rule cannot satisfy ACTIVATION, and an ACTIVATION-only rule cannot satisfy TRANSITION.

This closes the prior semantic ambiguity.

---

## 4. UNLOCK gate — PASS

`Phase9Store.unlockForm(...)` now performs:

1. player/campaign/version/provenance validation;
2. canonical form lookup;
3. duplicate-unlock idempotency check;
4. `RequirementEvaluator.requirePass(... RequirementGate.UNLOCK ...)`;
5. only after success: persistent insert into `player_form_unlocks`.

Therefore a failed UNLOCK requirement cannot leave a new durable unlock row.

The runtime also fails before write for:

- missing provider,
- missing rule,
- incompatible rule version,
- rule bound to the wrong gate,
- malformed/indeterminate provider result,
- dependency cycle.

A form with no explicit UNLOCK requirement remains legal to unlock according to its definition contract; absence of a binding is explicit no-requirement semantics, not a silent bypass of a declared rule.

Verdict: **PASS**.

---

## 5. TRANSITION gate — PASS

### 5.1 Normal transition

For a source-stage transition, `transitionEvolution(...)` validates:

- transition identity exists,
- target stage exists,
- current source path/state exists,
- current stage equals declared source stage,
- cross-path semantics are explicitly allowed when needed,
- `RequirementEvaluator.requirePass(... RequirementGate.TRANSITION ...)` succeeds.

Only after requirement success does the runtime begin the transaction that changes current stage and records attained history.

A failed requirement therefore leaves:

```text
current stage unchanged
attained-stage history unchanged
no target stage materialized
```

### 5.2 ENTRY transition

Direct arbitrary stage entry is explicitly forbidden. `enterEvolutionPath(...)` is deprecated at error level and throws; path entry must use an explicit `EvolutionTransitionDefinition` with `sourceStageUid == null`.

ENTRY transitions execute the same `TRANSITION` gate before insertion of either:

- `player_evolution_states`, or
- `player_evolution_stages`.

Thus an ENTRY requirement failure creates zero evolution state/history.

### 5.3 Rollback semantics

An ENTRY transition cannot be replayed on a path that already has current state and cannot be used as rollback. Normal rollback/reversal still requires an explicit legal transition whose declared source matches current stage.

Historical attainment of an older stage alone never authorizes rollback.

Verdict: **PASS**.

---

## 6. ACTIVATION gate — PASS

`activateForm(...)` now performs before authoritative active-state mutation:

1. campaign/player validation;
2. durable unlock existence check;
3. form definition/status validation;
4. mutual-exclusion validation;
5. `RequirementEvaluator.requirePass(... RequirementGate.ACTIVATION ...)`.

Only after the ACTIVATION gate passes does one SQLite transaction:

- create `player_active_forms` if needed;
- ensure Phase-5 modifier rows exist;
- activate the Phase-9 form modifier source.

Therefore activation requirement failure cannot leave:

- active form state,
- active Phase-5 form modifiers,
- partial activation state.

Verdict: **PASS**.

---

## 7. Failure semantics and determinism

### 7.1 Missing provider

A non-null binding with no `RequirementRuleProvider` fails explicitly before mutation.

### 7.2 Missing rule

A provider returning no descriptor for the bound UID fails explicitly.

### 7.3 Version mismatch

The top-level bound rule descriptor version must equal the persisted `RequirementBinding.ruleVersion`. Mismatch fails deterministically.

### 7.4 Malformed result

Provider result `null` is treated as malformed/indeterminate and fails explicitly; it cannot be interpreted as success.

### 7.5 Dependency cycle

Dependencies are evaluated recursively with a stable stack guard. Encountering a UID already in the active stack raises a deterministic cycle error containing the dependency path rather than recursing indefinitely.

Dependencies are sorted before evaluation, so result order does not depend on provider insertion order.

### 7.6 Failed dependency

If any dependency resolves false, the parent requirement resolves false without authorizing the gate.

Verdict: **PASS**.

---

## 8. No partial state

The three gates are evaluated before their respective authoritative writes.

- UNLOCK: evaluation precedes the single durable unlock insert.
- TRANSITION: evaluation precedes transaction start and all current-stage/history writes.
- ACTIVATION: evaluation precedes the transaction containing active state and modifier activation.

Additionally, activation and transition multi-write operations are transactional.

The final tests verify zero/unchanged state after failed gates, including ENTRY transition failure and failed activation modifier atomicity.

Verdict: **PASS**.

---

## 9. Unlock vs active / temporary-condition semantics

`deactivateForm(...)` removes only:

- `player_active_forms` current state;
- active status of Phase-5 modifiers sourced by the form.

It does not delete `player_form_unlocks`.

A test changes a previously passing activation condition to false and then deactivates the form; the durable unlock remains. Therefore loss of a current activation condition after legal unlock does not retroactively erase the unlock.

The runtime does not reinterpret failure of ACTIVATION as failure of prior UNLOCK or TRANSITION.

Verdict: **PASS**.

---

## 10. Temporary transformation and no-retrogression

Phase-9 form effects continue to flow through the accepted generic Phase-5 modifier/resolver layer.

The hotfix does not introduce writes from temporary requirement/form state into persistent Phase 3–8 bases.

Existing verified behavior remains:

```text
PlayerStat.baseValue unchanged
PlayerResource.currentValue not silently rewritten by derived max effect
PlayerSkill.baseMastery unchanged
PlayerTechnique.baseMastery unchanged
Talent persistent value unchanged
Potential persistent value unchanged
```

Phase-9 durable unlock and evolution history are also not deleted by deactivation or failed reactivation.

Verdict: **PASS**.

---

## 11. Legacy evidence semantics

The conservative Phase-9 legacy policy remains unchanged by the requirement hotfix:

```text
legacy label / clan_uid / status evidence
!= automatic canonical grant
```

Canonicalization still requires explicit World Pack mapping.

For legacy evolution-stage mapping, the hotfix is stricter: materialization requires exactly one explicit ENTRY transition to the target stage, and that transition is processed through the normal TRANSITION gate. Legacy mapping therefore cannot bypass transition requirement semantics.

Legacy bytes remain preserved.

Verdict: **PASS**.

---

## 12. Migration / current-schema semantics

The hotfix adds additive migration:

`RPGOS-9.1-REQUIREMENT-GATES`

It adds nullable requirement-version / unlock-binding columns and preserves pre-hotfix transition/activation UID bindings deterministically as version 1 rather than discarding them.

It does not rewrite player Phase-9 state or legacy evidence.

`CurrentSchema.ensure(...)` now routes through `ensureV9RequirementHotfix(...)`, so normal production current-schema bootstrap reaches the hotfix.

Migration is idempotent via the migration ledger and additive column checks.

Verdict: **PASS**.

---

## 13. CI evidence for exact final SHA

Exact SHA audited:

`c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`

GitHub Actions evidence:

- workflow: `Build & Release RPG OS ALPHA`
- run number: `#213`
- run id: `31350492914`
- event: `push`
- head branch: `master`
- head SHA: `c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`
- status: `completed`
- conclusion: `success`
- attempt: `1`

CI gate: **PASS**.

---

## 14. Final semantic revalidation matrix

| Required semantic gate | Result |
|---|---|
| UNLOCK requirement before persistent write | PASS |
| TRANSITION requirement before stage/history mutation | PASS |
| ACTIVATION requirement before active state/modifiers | PASS |
| failure leaves no partial state | PASS |
| missing provider fails | PASS |
| missing rule fails | PASS |
| version mismatch fails | PASS |
| malformed evaluation fails | PASS |
| dependency cycle fails deterministically | PASS |
| gate semantics cannot substitute for each other | PASS |
| deactivation preserves durable unlock | PASS |
| later activation-condition loss does not delete unlock | PASS |
| ENTRY transition uses explicit TRANSITION gate | PASS |
| arbitrary direct stage entry forbidden | PASS |
| rollback requires explicit legal transition semantics | PASS |
| temporary conditions do not rewrite Phase 3–9 persistent authority | PASS |
| legacy labels / clan_uid remain evidence only | PASS |
| legacy evolution mapping cannot bypass explicit ENTRY transition | PASS |
| current-schema path reaches requirement hotfix | PASS |
| exact final SHA CI | PASS (#213) |

---

## 15. Final conclusion

The blocker that caused the prior `PHASE 9 SEMANTIC RECHECK: FAIL` at `d796d374f92d94477542da5f753ee411b633076b` is resolved in `c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`.

UNLOCK, TRANSITION and ACTIVATION are now separate, typed, versioned semantic gates enforced in the real mutation paths before their respective authoritative writes. Failure behavior is explicit and deterministic, ENTRY evolution follows the same transition contract, active-form lifecycle preserves durable unlock, Phase-5 remains the sole derived-effect mechanism, and legacy evidence cannot bypass canonical state-machine gates.

No runtime changes were made by CHAT-2.

PHASE 9 SEMANTIC REVALIDATION: PASS
