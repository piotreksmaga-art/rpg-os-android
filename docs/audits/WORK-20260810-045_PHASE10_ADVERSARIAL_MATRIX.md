# WORK-20260810-045 — Phase 10 Adversarial Matrix

Status: FINAL

Work ID: `WORK-20260810-045`
Role: READ-ONLY ADVERSARIAL VALIDATION AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-9 runtime: `c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`
Exact Phase-10 candidate: `eb8bb64f8be566982c91f1062f319078899c1e47`
Candidate CI: GitHub Actions run `#241`, run ID `31358857064`
Allowed write scope: this report only.

## Final verdict

**PHASE 10 ADVERSARIAL VALIDATION: PASS**

No release-blocking adversarial failure was found in the exact candidate. This report does not change the global Phase-10 roadmap status and does not implement Phase 11.

## 1. Canonical oracle

MASTER and the Phase-10 architecture/oracle reports require:

```text
ItemDefinition != ItemInstance
Inventory possession != Equipment/loadout
Inventory possession != OwnershipRecord
stable UID > display name
stackable quantity != unique instance identity
legacy evidence != canonical identity without explicit mapping
```

The historical `CharacterPanelSnapshot.equipment` name is presentation debt only. The underlying legacy read is `character_inventory.item_name`; it is not evidence of equipped state.

## 2. Candidate / CI pin

The validated runtime candidate is exactly `eb8bb64f8be566982c91f1062f319078899c1e47`.

Independent GitHub Actions verification found run `31358857064`, run number `241`, `head_sha=eb8bb64f8be566982c91f1062f319078899c1e47`, status `completed`, conclusion `success`.

The earlier #237 fixture failure is not treated as a blocker for this SHA. The final ContextBuilder scale fixture uses the canonical `active_player_ref(campaign_id,player_uid,updated_at)` shape and retains the no-truncation assertion.

## 3. Adversarial matrix

| Gate | Attack | Expected | Candidate evidence / result |
|---|---|---|---|
| Identity | duplicate definition UID | fail-loud | `registerDefinitions` rejects an existing UID; DB PK also protects it. PASS |
| Identity | duplicate stable key in same World Pack | fail-loud | explicit `(world_pack_uid,item_key)` check plus UNIQUE constraint. PASS |
| Identity | same display name / different UID | remain distinct | test registers same display name in two packs with distinct UIDs. PASS |
| Identity | World-Pack mapping hijack | reject | mapping target definition owner must equal supplied World Pack. PASS |
| Identity | missing definition | fail-loud | `definition(uid)` errors when target is absent. PASS |
| Identity | deprecated definition | existing identity remains readable | status is stored on definition and reads do not erase holdings. PASS |
| Instance | duplicate ItemInstance UID | reject | campaign-scoped PK plus `instanceExists`. PASS |
| Instance | instance mapped to incompatible definition | reject | mapping checks instance definition UID equals mapped definition UID. PASS |
| Stack/unique | stack API on UNIQUE definition | reject | storage policy check. PASS |
| Stack/unique | unique without stable instance | reject | unique mapping requires explicit `canonicalItemInstanceUid`; addUnique requires existing instance. PASS |
| Stack/unique | one unique instance at two holders | reject | `uniqueHolder` plus UNIQUE `(campaign_id,item_instance_uid)`. PASS |
| Stack/unique | unique transfer loses identity | same UID at destination | transfer updates holder on same row; test asserts UID `X` survives. PASS |
| Quantity | zero / negative add/remove | reject | `requireQuantity(q>0)`. PASS |
| Quantity | Long overflow | fail without mutation | `Math.addExact`; test checks MAX_VALUE + 1 fails and MAX_VALUE remains. PASS |
| Quantity | remove > possessed | fail without mutation | checked before update/delete. PASS |
| Quantity | remove all | delete active holding | tested quantity 5 -> no stack row. PASS |
| Quantity | repeated add | exact sum | 2 + 3 -> 5 test. PASS |
| Transfer | partial stack transfer | atomic decrement/increment | transaction; 10 -> 7/3 test. PASS |
| Transfer | whole stack | source deletion + target add under same transaction | same `removeStackInside/addStackInside` transaction semantics. PASS |
| Transfer | source == destination | reject before transaction | explicit require. PASS |
| Transfer | target overflow/failure | source unchanged | target checked with `checkedAdd` before source mutation; transaction protects later failures. PASS |
| Legacy | same name => same item | forbidden | no name-based definition lookup/reconciliation. PASS |
| Legacy | unmapped row | preserved unresolved | `reconciled` returns unresolved evidence. PASS |
| Legacy | duplicate identical rows | must not infer quantity=2 | evidence `rowCount=2`; mapping rejected; test asserts no typed stack. PASS |
| Legacy | mixed typed + same-name legacy | no silent merge | typed row remains typed and legacy remains unresolved without mapping. PASS |
| Legacy | wrong World Pack mapping | reject | tested. PASS |
| Legacy | missing/deleted target | fail-loud | reconciliation resolves definition/instance by stable UID and errors if absent. PASS |
| Legacy | mapping rewrite/hijack | fail-loud | existing mapping may only be re-registered if all canonical fields/version/provenance exactly match. PASS |
| Legacy | repeated identical mapping | idempotent | exact existing mapping is accepted without second insert. PASS |
| Legacy | bytes deletion | forbidden | migration is additive; backup/restore test verifies original name/quantity bytes survive. PASS |
| Equipment | possession -> equipped | forbidden | Phase-10 schema creates no equipment table; ContextBuilder explicitly emits `equipped=null`. PASS |
| Equipment | possession -> loadout/modifier | forbidden | no Phase-10 loadout/equipment mutation path; transfer test asserts no `player_equipment` table. PASS |
| Ownership | possession -> OwnershipRecord | forbidden | no OwnershipRecord creation; tests assert no ownership table side effect. PASS |
| Ownership | inventory transfer -> legal transfer | forbidden | transfer mutates inventory tables only. PASS |
| Isolation | player leakage | none | all holding reads/writes include character UID; player isolation test passes in CI. PASS |
| Isolation | campaign leakage | none | stacks/instances/unique/mappings are campaign-scoped; test checks campaign B cannot see A holding. PASS |
| Isolation | World-Pack identity | definition/mapping owner preserved | explicit owner checks and `(world_pack_uid,item_key)` scope. PASS |
| Scale | >1000 definitions/typed entries | complete authoritative read | 1005 definitions/stacks fixture. PASS |
| Scale | >1000 unresolved legacy | complete read | 1005 legacy evidence rows remain unresolved/player-scoped. PASS |
| Scale | ContextBuilder no truncation | 1001 typed + 1 unresolved = 1002 | exact fixture asserts `playerInventory.size == 1002`. PASS |
| Migration | CurrentSchema -> V10 | reachable | `CurrentSchema.ensure` calls `ensureV10`; V10 chains through V9.1. PASS |
| Migration | bootstrap | V10 marker/table | production-routing test. PASS |
| Migration | campaign switch | V9 -> V10 | production-routing test. PASS |
| Migration | restore | V9 -> V10 | production-routing test. PASS |
| Migration | reopen/idempotency | no duplicates | repeated ensure marker count=1 and reopen preserves 1005 entries. PASS |
| DB safety | integrity_check | `ok` | persistence and bundled preflight tests. PASS |
| DB safety | foreign_key_check | empty | persistence test. PASS |
| Legacy asset | real bundled PRAGMA | inspect real `character_inventory` when present | asset preflight executes table/index/FK PRAGMAs and compatibility read. PASS |
| Regression | Phase 3–9.1 state | no inventory inference/mutation | Phase-10 migration is additive and chains prior migrations; inventory code does not write stat/resource/modifier/talent/potential/skill/technique/origin/innate/evolution/form/requirement state. CI full JVM suite succeeds. PASS |

## 4. Failure semantics review

### Stack transfer

`transferStack` validates nonblank/different participants, positive quantity, STACKABLE policy and target overflow. Source decrement and target increment execute inside one SQLite transaction. An over-remove or later exception leaves no committed partial transfer.

### Unique transfer

`transferUnique` requires the exact source holder and updates the holder of the existing stable instance UID inside a transaction. It does not synthesize a new instance.

### Legacy ambiguity

Legacy evidence identity is derived from the full non-owner raw field signature. Identical rows collapse only into evidence with `rowCount > 1`; this is deliberately *not* interpreted as quantity. Explicit mapping requires `rowCount == 1`, so ambiguous duplicates remain unresolved.

### Mixed legacy + typed

Without explicit mapping, a typed item and a same-looking legacy item coexist as typed canonical state plus unresolved evidence. With explicit mapping, reconciliation suppresses duplicate mapped legacy representation when the corresponding typed UID/instance is already authoritative; original legacy bytes remain untouched.

## 5. Equipment blocker attack

The legacy CharacterPanel still has a field called `equipment` populated from `character_inventory.item_name`. That remains presentation debt and is not used by Phase-10 Core as authority.

The Phase-10 ContextBuilder inventory projection emits:

```text
equipped = null
ownership_record = null
```

for typed and unresolved inventory representations. Phase-10 tests also verify that inventory operations do not create equipment or ownership tables. No Inventory -> Equipment inference was found.

**BLOCKER NOT TRIGGERED.**

## 6. Ownership blocker attack

No InventoryStore add/remove/transfer path creates or mutates an OwnershipRecord authority. Possession transfer remains possession-only.

**BLOCKER NOT TRIGGERED.**

## 7. Scale / truncation

Authoritative inventory reads contain no `LIMIT 50/60` budget. The final ContextBuilder fixture constructs 1001 typed stack entries plus one unresolved legacy row and asserts exactly 1002 inventory entries, including the last typed UID and unresolved row.

The corrected fixture writes only canonical `active_player_ref` columns and therefore retains the substantive no-truncation assertion while removing the invalid `source` column assumption that caused the earlier CI failure.

**BLOCKER NOT TRIGGERED.**

## 8. Migration / persistence / restore

`CurrentSchema.ensure()` reaches V10. Production tests cover bundled bootstrap, switching a V9 campaign to active, and restoring a V9 backup. Repeated ensure is idempotent. Close/reopen preserves typed holdings. Backup/restore preserves typed inventory, explicit mapping version/provenance and untouched legacy bytes.

**BLOCKER NOT TRIGGERED.**

## 9. Database integrity

The candidate test suite asserts `PRAGMA integrity_check = ok` and `PRAGMA foreign_key_check` returns no rows after Phase-10 operations. The bundled legacy asset preflight also executes real `character_inventory` PRAGMA inspection against the packaged campaign database when the table exists.

**BLOCKER NOT TRIGGERED.**

## 10. Phase 3–9.1 no-regression

The Phase-10 schema is additive. Its runtime mutation paths are confined to item definitions, item instances, inventory stacks, unique inventory holders and explicit legacy inventory mappings. No automatic writes or semantic inference into PlayerStat, PlayerResource, modifiers, Talent, Potential, Skill, Technique, Origin, InnateFeature, Evolution, Form or requirement gates were found. Full candidate CI succeeded.

**BLOCKER NOT TRIGGERED.**

## 11. CI result

Independent verification:

```text
GitHub Actions run: #241
run ID: 31358857064
head SHA: eb8bb64f8be566982c91f1062f319078899c1e47
status: completed
conclusion: success
```

The exact candidate therefore has successful final CI evidence.

## 12. Final decision

All automatic blocker classes requested by WORK-045 were attacked against the exact candidate and none was established:

- no name-based canonical item identity;
- no duplicate-legacy-to-quantity inference;
- no unique item without explicit stable instance identity;
- no multi-holder unique instance;
- overflow is fail-loud;
- transfers are transactional;
- campaign/player scope is explicit for holdings and mappings;
- no Inventory -> Equipment inference;
- no Inventory -> Ownership inference;
- no silent legacy deletion;
- no authoritative inventory truncation;
- V10 is reachable through production schema paths;
- no Phase 3–9.1 authoritative mutation/regression was found.

# PHASE 10 ADVERSARIAL VALIDATION: PASS
