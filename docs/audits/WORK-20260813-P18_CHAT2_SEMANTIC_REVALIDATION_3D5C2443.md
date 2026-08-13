# WORK-20260813-P18 — CHAT-2 Semantic Revalidation

Status: **FAIL — RELEASE BLOCKER**

Role: CHAT-2 — Independent Semantic Auditor
Repository: `piotreksmaga-art/rpg-os-android`
Validated runtime SHA: `3d5c24438d477bb6670efcb31771058332bd451f`
Fresh master before report write: `b028ee89ef5c086849a0ce2f8a6b6b0b1b5e8ab3`
Runtime changed after target: NO — target..master contained only `docs/audits/WORK-20260813-P18_CHAT1_IMPLEMENTATION_AND_RECOVERY_3D5C2443.md`.

## Verdict

`PHASE 18 SEMANTIC REVALIDATION: FAIL`

The target provides useful typed routing and Phase-17 proposal validation, but the public orchestration dependency boundary is too weak to satisfy the Phase-18 semantic contract that resolution is pure/read-only, deterministic, proposal-only orchestration.

## P18-SEM-RESOLVER-BOUNDARY-01 — unrestricted resolver can mutate authoritative state during `PlayerDomainEngine.resolve`

**Severity:** release blocker.

Production path:

`PlayerDomainEngine.resolve(command)`
→ `resolverRegistry.resolverFor(...)`
→ `resolveTyped(...)`
→ public `PlayerCommandResolver.resolve(command): PlayerChangeSet`
→ only after resolver returns: command fingerprint check + command/proposal linkage + `PlayerChangeSetValidator.validate(...)`.

The resolver port receives a command and returns a final `PlayerChangeSet` directly. Nothing in the production boundary prevents the resolver implementation from performing DB/store/ledger/event mutation before returning the proposal. Such mutation occurs *inside* the semantic `PlayerDomainEngine.resolve` operation and is neither detected nor rolled back by the engine.

Minimal reproducer (local contract test shape):

```kotlin
val db = SQLiteDatabase.create(null)
db.execSQL("CREATE TABLE authority_fixture(uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
db.execSQL("INSERT INTO authority_fixture(uid,value) VALUES('A',7)")

val resolver = object : PlayerCommandResolver<TrainCommandPayload> {
    override val commandKindUid = PlayerCommandKinds.TRAIN
    override val payloadType = TrainCommandPayload::class
    override fun resolve(command: PlayerCommand<TrainCommandPayload>): PlayerChangeSet {
        db.execSQL("UPDATE authority_fixture SET value=99 WHERE uid='A'")
        return validStatProposal(command)
    }
}

PlayerDomainEngine(PlayerCommandResolverRegistry.of(listOf(resolver))).resolve(trainCommand)
// returns a structurally valid PlayerChangeSet, but authority_fixture is now 99
```

Expected: a successful Phase-18 resolution is proposal-only and cannot mutate authoritative state.

Actual: the public production callback can mutate arbitrary authority while `engine.resolve()` is executing; the engine still accepts the returned valid proposal.

Architectural consequence: `PlayerDomainEngine.resolve` is not a reliable proposal-only boundary. A resolver can effectively become a hidden transaction/mechanics/writer authority, bypassing later TurnTransaction/COMMIT boundaries.

The existing tests `p18Engine11` and `p18Engine12` do not prove this invariant. They create an independent SQLite fixture and use resolvers that never receive or capture it; naturally the fixture remains unchanged. The test would remain green even though the public resolver API permits the reproducer above.

Minimal Phase-18-only correction scope: harden the orchestration port so production resolvers are constrained to trusted read-only/deterministic resolution inputs and cannot act as arbitrary externally supplied callbacks that return the final ChangeSet. A suitable direction is to make the engine own proposal assembly from a typed resolution draft/read-only evidence boundary, with resolver implementations internal/trusted and no writable authority dependencies. Do not implement Phase-19+ world rules/mechanics; only establish the read-only Phase-18 orchestration contract.

## P18-SEM-DETERMINISM-01 — resolver nondeterminism is unconstrained

**Severity:** release blocker; same root boundary.

Because `PlayerCommandResolver.resolve(command)` is an unrestricted callback and the engine supplies no immutable resolution context / deterministic evidence / entropy contract, the same command and same registry can legally produce different proposals on consecutive calls:

```kotlin
var n = 0L
val resolver = resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) {
    n += 1
    statProposal(it, "CH-$n", DomainRef("PLAYER","P1"), "STAT:STR", n)
}
val engine = PlayerDomainEngine(PlayerCommandResolverRegistry.of(listOf(resolver)))
val a = engine.resolve(trainCommand)
val b = engine.resolve(trainCommand)
// a != b; both are valid proposals
```

The same holds for `UUID.randomUUID()`, clock time, or random state inside a resolver.

Expected: equivalent command + equivalent explicit dependency inputs produce equivalent proposal semantics, or nondeterministic evidence/seed is explicit and replayable.

Actual: determinism depends entirely on undocumented resolver implementation discipline. The engine cannot distinguish deterministic resolution from hidden time/random/mutable singleton state.

The existing `p18Engine07_sameInputsAndDependencyResultAreDeterministic` test uses a deliberately deterministic test resolver and therefore verifies that stub, not a production engine guarantee.

Minimal correction scope: same as P18-SEM-RESOLVER-BOUNDARY-01 — make deterministic dependency/context/entropy inputs explicit at the Phase-18 boundary or constrain production resolver implementations to deterministic pure resolution.

## Gates

- PlayerDomainEngine semantics: FAIL
- Command coverage: PASS at routing-contract level — all canonical Phase-16 kinds can be keyed by stable `commandKindUid`; absent resolver fails `UNKNOWN_COMMAND_RESOLVER`; there is no silent no-op.
- Unique typed routing: PASS — duplicate resolver kind rejected; payload type mismatch rejected before resolver execution.
- Fail-closed structural routing: PASS
- Phase boundary: FAIL because the unrestricted resolver can act as hidden writer/mechanics/transaction authority even though `PlayerDomainEngine` itself has no writer fields.
- Proposal-only: FAIL
- Zero authoritative mutation: FAIL
- Determinism: FAIL
- World-agnostic: PASS — no Naruto/Bleach-specific branch found in `PlayerDomainEngine.kt`.
- Phase-17 semantic preservation: PASS for proposals that reach canonical validation.
- Project zero-progress: PASS — `ProjectProgressDelta.of(0)` survives representative engine proposal path.
- ExactLongDelta: PASS — zero remains forbidden.
- Composite target identity: PASS — historical delimiter collision remains distinct under Phase-17 validation.
- Asset identity: PASS — `OwnedAssetRef(assetKindUid, assetUid)` is preserved by representative path.
- Financial/ledger: PASS — representative proposal preserves exact terms and Phase-17 causal validation.
- Serialization: PASS for engine-produced valid proposals.
- Fingerprint: PASS for deterministic resolver outputs; not sufficient to repair resolver nondeterminism.
- Test quality: FAIL for zero-mutation/determinism guarantees because current tests only use benign deterministic test-local resolvers and can remain green while the production callback boundary permits both counterexamples.
- Phase 3–17 regression: PASS from inspected representative gates and exact full CI.
- Full JVM: PASS via exact CI run #385.

## Exact CI

GitHub Actions #385
Run ID: `31720139533`
Head SHA: `3d5c24438d477bb6670efcb31771058332bd451f`
Status: completed
Conclusion: success

Verified build job steps:

- Validate project — SUCCESS
- Run JVM unit tests — SUCCESS
- Build signed ALPHA APK — SUCCESS
- Prepare release files — SUCCESS
- Upload Actions artifact — SUCCESS
- Check if release already exists — SUCCESS
- Create GitHub Release — SKIPPED because release already existed
- Update existing GitHub Release assets — SUCCESS
- Show release information — SUCCESS
- Overall job — SUCCESS

Green CI does not cover the two semantic counterexamples above.

## Final

`FINAL CHAT-2 VERDICT: FAIL`

Phase 18 is not globally accepted. Phase 19 remains blocked.