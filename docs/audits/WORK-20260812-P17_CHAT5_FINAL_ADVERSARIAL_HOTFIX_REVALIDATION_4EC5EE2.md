# PHASE 17 — FINAL ADVERSARIAL / ROBUSTNESS HOTFIX REVALIDATION

Role: CHAT-5 / independent adversarial robustness auditor

Repository: `piotreksmaga-art/rpg-os-android`

Validated runtime SHA: `4ec5ee2bbdcd445beb067097c37bc095e3007540`

Fresh master at final pre-report recheck: `466785802df57edbbae9e18d6f25582e48e75ea6`

Runtime changed after target: **NO**. The commits after the target observed at final recheck are report-only CHAT-3 / CHAT-2 audit commits. No later Phase-17 production/test runtime was found.

Exact CI: GitHub Actions `#357`, run ID `31637247305`, head SHA `4ec5ee2bbdcd445beb067097c37bc095e3007540`, conclusion `SUCCESS`.

This is a report-only audit. No production code, tests, schema, migration, runtime or Phase-18 implementation was modified.

## Final verdict

`PHASE 17 ADVERSARIAL / ROBUSTNESS REVALIDATION: FAIL`

The two original release blockers are fixed correctly, but an independent follow-on adversarial case exposes a new duplicate financial-ledger effect blocker.

---

## 1. Exact runtime / scope evidence

The Phase-17 hotfix production delta preserves the transient PlayerChangeSet contract and changes only the Phase-17 model/validator/codec surfaces needed for the two previous blockers, plus regression tests and report documentation.

The exact CI run checks out the validated SHA and executes the full `:app:testDebugUnitTest` task successfully. The same workflow also succeeds for Validate project, signed ALPHA APK construction, release file preparation, Actions artifact upload and existing release-asset update.

Green CI is execution evidence only; the FAIL below comes from an independently inspected path that is not covered by the hotfix suite.

---

# 2. HOTFIX A — ASSET IDENTITY — PASS

Previous blocker: bare `assetUid` erased the Phase-14 generic asset namespace.

The new runtime uses:

`AssetChange(asset: OwnedAssetRef, proposedLifecycleStateUid)`

The codec preserves both:

- `assetKindUid`
- `assetUid`

Validation requires both components to be nonblank. Canonical JSON uses the nested owned-asset representation. Decode restores both components. The conflict key is:

`ASSET:<assetKindUid>:<assetUid>`

Adversarial results:

- PROPERTY/A-1 vs BUSINESS/A-1: distinct typed targets — PASS
- same kind + same UID: same canonical target / conflicting duplicate target fails closed — PASS
- different kind + same UID: no false conflict — PASS
- round-trip preserves kind + UID — PASS
- changing assetKindUid changes canonical JSON/fingerprint — PASS
- blank kind/UID rejected — PASS

Conclusion: `P17-ADV-ASSET-IDENTITY-01` is closed.

---

# 3. HOTFIX B — FINANCIAL CHANGE / LEDGER TERM CONSISTENCY — PASS FOR ORIGINAL BLOCKER

The validator now builds a stable `changesByUid` map and resolves each ledger `causalChangeUid` against the ChangeSet.

For each causal `FinancialChange`, the `FinancialTransferLedgerIntentPayload` must match all five immutable terms:

- fromAccountUid
- toAccountUid
- amountMinor
- currencyUid
- transactionTypeUid

Adversarial results:

- exact matching financial + ledger terms — ACCEPT / PASS
- destination mismatch — REJECT / PASS
- source mismatch — REJECT / PASS
- amount mismatch — REJECT / PASS
- currency mismatch — REJECT / PASS
- transaction-type mismatch — REJECT / PASS
- dangling causal UID — REJECT / PASS
- causal list containing no FinancialChange — REJECT / PASS
- multiple causal refs including FinancialChanges: every FinancialChange encountered must match or the set rejects — PASS
- standalone ledger with zero causal refs remains explicitly legal and round-trips — PASS

Conclusion: the original `P17-ADV-FIN-LEDGER-01` mismatch blocker is closed.

---

# 4. NEW RELEASE BLOCKER

## P17-ADV-FIN-LEDGER-DUP-02 — one FinancialChange can cause multiple duplicate ledger append intents

### Violated invariant

A PlayerChangeSet is one immutable future atomic proposal unit. Obvious duplicate semantic effects must fail closed. A `FinancialTransferLedgerIntent` that identifies a causal `FinancialChange` is not merely unrelated metadata: it proposes the ledger append corresponding to that financial effect.

Distinct `ledgerIntentUid` values must not be sufficient to turn one financial effect into multiple indistinguishable ledger appends unless the contract explicitly models a one-to-many split/allocation relationship. Phase 17 has no such split/allocation contract.

### Minimal reproducer

Construct one valid FinancialChange:

```text
changeUid = CH-FIN
A -> B
amountMinor = 100
currencyUid = CUR
transactionTypeUid = TRANSFER
```

Then construct two different ledger-intent identities:

```text
LED-1
causalChangeUids = [CH-FIN]
A -> B / 100 CUR / TRANSFER
```

and:

```text
LED-2
causalChangeUids = [CH-FIN]
A -> B / 100 CUR / TRANSFER
```

Place the one FinancialChange and both ledger intents into the same PlayerChangeSet.

### Expected

Deterministic structural rejection as a duplicate semantic ledger effect for the same causal FinancialChange, unless a typed contract explicitly proves a lawful one-to-many split. No such contract exists in this runtime.

### Actual

The ChangeSet is accepted:

1. `LED-1 != LED-2`, so ledger UID uniqueness passes.
2. Both causal UIDs resolve to `CH-FIN`.
3. Both payloads individually pass `validateFinancialTerms()`.
4. Both independently pass `financialTermsMatch()` against `CH-FIN`.
5. The validator does not track that `CH-FIN` has already been represented by a financial-transfer ledger intent.
6. The resulting ChangeSet is canonicalizable/fingerprintable while proposing two ledger append effects from one financial proposal.

### Exact runtime path

`app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt`

`PlayerChangeSetValidator.validate()` -> `ledgerIntents.forEach` -> ledger UID set -> causal resolution -> `financialTermsMatch()`.

There is no semantic uniqueness set keyed by causal FinancialChange UID (or an equivalent typed financial effect identity).

### Why this is Phase-17 scope

This does not require account balance lookup, authorization, stale-state validation, PlayerDomainEngine, transaction execution or persistence. The duplication is fully visible inside one immutable PlayerChangeSet before any Phase-18+ component runs.

It is therefore an internal duplicate/conflict property of the proposal contract itself.

### Minimal correction scope

Phase 17 only:

- enforce at most one `FINANCIAL_TRANSFER` ledger intent for a given causal FinancialChange, **or**
- introduce an explicit typed one-to-many split/allocation contract whose semantics prevent duplicate financial effect application.

Add regression coverage for:

1. one FinancialChange + two identical ledger intents with different ledger UIDs -> reject;
2. one FinancialChange + two differently-termed ledger intents -> reject (already partially covered through mismatch);
3. two independent FinancialChanges + one matching ledger intent per financial change -> accept where the existing financial conflict contract permits the pair;
4. standalone ledger without causal refs -> preserve the explicit compatibility contract.

No Phase-18 engine, DB writer, persistence schema or accepted Phase 3-16 authority needs redesign.

---

# 5. Remaining adversarial matrix

## Immutability — PASS

- root collection defensive copies: PASS
- nested event target/causal lists: PASS
- ledger causal lists: PASS
- DevelopmentProject evidence refs: PASS
- post-construction caller list mutation cannot alter the ChangeSet: PASS

## World-agnostic boundary — PASS

No Naruto/Bleach/chakra/reiatsu/Sharingan/Hollow/Kido-specific Core change contract was found.

## Proposal-only boundary — PASS

No Phase-17 `apply`, `commit`, `execute`, `save`, persistence table, queue/inbox/outbox, DAO/SQLite writer or StatePatch bridge is introduced. Event and ledger objects remain intents, not committed records.

## Typed semantics — FAIL overall

Typed family coverage remains present for stat, resource, skill, technique, innate, inventory, equipment, finance, asset, ownership, condition, runtime, DevelopmentProject, event intents, ledger intents, provenance, warnings and preconditions.

However, financial-ledger cross-surface duplicate semantics fail because of `P17-ADV-FIN-LEDGER-DUP-02`.

## Serialization — PASS

Inspection confirms:

- allowed-key enforcement at root and nested typed surfaces;
- pre-parse duplicate object-key rejection;
- escaped-equivalent duplicate-key detection via decoded key identity;
- strict JSON strings;
- strict JSON numerics;
- quoted numerics rejected;
- unsupported ChangeSet schema version rejected;
- unknown change/event/ledger/precondition kinds rejected;
- canonical encode -> decode -> encode preserves legal semantics;
- internal typed codec decode is itself allowed-key guarded.

Malformed nested values reject rather than becoming canonical legal proposals.

## Identity / fingerprint — FAIL overall

For legal non-duplicated ChangeSets, canonical encoding and SHA-256 fingerprinting remain deterministic, list order remains semantic, and asset kind changes alter fingerprint.

Overall gate is FAIL because the duplicate financial-ledger semantic effect is currently admitted as a legal canonical/fingerprintable ChangeSet.

## Conflict handling — FAIL

PASS:

- duplicate changeUid;
- duplicate eventIntentUid;
- duplicate ledgerIntentUid;
- duplicate semantic target conflict keys;
- conflicting equipment target operations;
- full asset identity conflict keys;
- ownership conflict keys;
- dangling causal refs;
- dangling warning refs;
- financial ledger term mismatch.

FAIL:

- two distinct ledger UIDs representing the same causal FinancialChange are accepted.

## Numeric safety — PASS

- proposal arithmetic uses exact Long;
- ExactLongDelta rejects zero and uses exact overflow-checking arithmetic;
- financial amount is positive exact minor-unit Long;
- > IEEE-754 exact-integer values remain exact because no Float/Double authority is used;
- OwnershipShare reuses accepted fixed-scale authority;
- strict numeric JSON typing remains in place.

## Zero authoritative mutation — PASS

No authoritative writer is reached by construction, validation, encode, decode, fingerprint or identity comparison. The existing SQLite fixture remains unchanged through those operations.

## Phase 3-16 regression — PASS

The exact full JVM suite is green at the target SHA, and the hotfix suite directly exercises Phase-16 PlayerCommand deterministic round-trip plus accepted Ownership/Finance/Asset identity dependencies without changing their authority.

---

# 6. P17-HOTFIX-01..16 quality review

The P17 hotfix tests are active in the exact full JVM run and meaningfully assert the two original blockers:

- full asset identity and conflict key;
- all five finance/ledger mismatch fields;
- non-financial and dangling causal refs;
- canonical round-trip/fingerprint;
- zero mutation;
- Phase 3-16 regression.

The suite does **not** test two distinct ledger intent UIDs pointing to the same FinancialChange with identical terms. That omission explains why CI is green despite the new blocker.

---

# 7. Exact CI evidence

GitHub Actions:

- run number: `357`
- run ID: `31637247305`
- head SHA: `4ec5ee2bbdcd445beb067097c37bc095e3007540`
- conclusion: `SUCCESS`

Successful build-job steps include:

- Validate project
- Run JVM unit tests (`:app:testDebugUnitTest`)
- Build signed ALPHA APK
- Prepare release files
- Upload Actions artifact
- Update existing GitHub Release assets
- overall job completion

The workflow checked out the exact target SHA.

---

# 8. Final gate summary

```text
ASSET IDENTITY HOTFIX:       PASS
FINANCIAL / LEDGER HOTFIX:   PASS for original mismatch blocker
IMMUTABILITY:                PASS
WORLD-AGNOSTIC:              PASS
PROPOSAL-ONLY:               PASS
TYPED SEMANTICS:             FAIL
SERIALIZATION:               PASS
IDENTITY / FINGERPRINT:      FAIL overall
CONFLICT HANDLING:           FAIL
NUMERIC SAFETY:              PASS
ZERO AUTHORITATIVE MUTATION: PASS
PHASE 3-16 REGRESSION:       PASS
FULL JVM:                    PASS (exact CI #357)
```

NEW BLOCKERS:

- `P17-ADV-FIN-LEDGER-DUP-02` — multiple distinct ledger intents can duplicate one causal FinancialChange.

# FINAL VERDICT

`PHASE 17 ADVERSARIAL / ROBUSTNESS REVALIDATION: FAIL`

Phase 17 is not marked globally ACCEPTED by this report. Phase 18 remains BLOCKED.
