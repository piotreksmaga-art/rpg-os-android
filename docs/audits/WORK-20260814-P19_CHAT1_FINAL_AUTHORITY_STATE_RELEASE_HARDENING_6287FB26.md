# WORK-20260814-P19 — CHAT-1 Final Authority / State / Release-Provenance Hardening

## Scope

Role: CHAT-1 — Phase-19 implementation owner.

This report closes the implementation/recovery pass for the three remaining Phase-19 blocker classes reported against runtime `11e0dcac8e128404524350bc53b9963124e9bbd7`:

- `P19-C3-001` / `P19-C5-008` — active/bound World Pack authority substitution;
- `P19-C5-007` — constant-specific enum retained-state bypass;
- `P19-C5-009` — mutable rolling release / post-target release overwrite.

This report does not globally accept Phase 19. Fresh independent revalidation remains required. Phase 20 remains planning-only and has no runtime implementation in this change set.

Final Phase-19 runtime established by this pass:

`6287fb2612afc9b60c7a9d47508cb0fcb79dbb67`

Rejected predecessor runtime:

`11e0dcac8e128404524350bc53b9963124e9bbd7`

History policy remained forward-only. No reset, rebase, force push, or published-history rewrite was performed.

## P19-C3-001 / P19-C5-008 — authoritative active World Pack

Status: FIXED.

### Root cause

`CampaignSelectionManager` already held canonical application selection for the active campaign and active World Pack. However, `PlayerResolutionContext` could carry a caller-supplied `WorldRuleMode.Bound(B)` and `PlayerDomainEngine` previously validated only whether provider B existed and matched B's version. A different fully valid and registered World Pack therefore could be substituted for the campaign's authoritative active World Pack.

### Final authority boundary

The hotfix adds internal immutable `WorldPackAuthoritySnapshot` as a read-only transient view of the existing canonical campaign -> active World Pack selection. It is not a second persisted source of truth.

`CampaignSelectionManager.activeWorldPackAuthoritySnapshot()` derives the snapshot from:

- `activeCampaignId()`;
- existing `activeWorldRuleMode()`;
- the already selected active World Pack directory;
- `PackageValidator`-validated World Pack manifest UID/version.

`PlayerDomainEngine` receives the immutable authority snapshot and performs `validateWorldRuleAuthority(context)` after Phase-18 command reference validation and before provider selection / `COMMAND_PRECHECK`.

For bound mode:

- no authoritative campaign binding -> `WORLD_RULE_AUTHORITY_MISSING`;
- supplied binding differs in UID or version -> `WORLD_RULE_BINDING_AUTHORITY_MISMATCH`;
- only an exact authoritative match proceeds to `WorldRuleProviderRegistry`.

For internal generic mode:

- any authoritative campaign binding -> `WORLD_RULE_GENERIC_MODE_AUTHORITY_MISMATCH`.

Thus arbitrary caller-created `Bound(B)` no longer acts as authority.

### Authority regression matrix

`WorldRuleProviderPhase19FinalHotfixTest` adds P19-AUTH-01..10:

1. active A + canonical Bound(A) -> rule path accepted;
2. active A + supplied Bound(B), with B fully valid/registered -> rejected before B evaluation;
3. active A + same World Pack UID but wrong version -> rejected;
4. active A + missing provider A -> `WORLD_RULE_PROVIDER_MISSING`;
5. permissive provider B cannot bypass A;
6. provider B invocation count remains zero during substitution attack;
7. C1 authority cannot be reused for C2 without C2 authority;
8. internal generic mode remains available only with no bound authority;
9. Phase-18 UNKNOWN_REFERENCE still rejects before provider evaluation;
10. Phase-18 WRONG_CAMPAIGN_REFERENCE still rejects before provider evaluation.

The tests execute the production `PlayerDomainEngine` path.

## P19-C5-007 — constant-specific enum retained state

Status: FIXED.

### Exact reproduction before production change

The reported JVM attack was reproduced before modifying production using an enum with a constant-specific class body:

- retained field declared type: base enum, `isEnum=true`;
- base enum class declared no semantic instance field;
- actual retained EVIL constant runtime class was an enum constant-specific subclass;
- that runtime subclass declared mutable `counter` state.

This proved that inspecting only `field.type` / base enum class was insufficient.

### Final provider-state validation

The retained-state validator now reads the actual retained enum value and validates the value's runtime class rather than assuming the declared enum type describes all state.

Validation:

- walks the actual constant runtime-class/superclass chain up to `Enum`;
- ignores static/synthetic JVM infrastructure;
- rejects non-final semantic instance fields with `MUTABLE_WORLD_RULE_PROVIDER_STATE`;
- permits primitive/scalar/String fields;
- recursively validates nested enum values using an identity-based visited set;
- rejects retained non-scalar mutable/writer-like objects with `UNSAFE_WORLD_RULE_PROVIDER_STATE`;
- maps reflection/security access failures to structural unsafe-state failure.

The existing provider trust model remains unchanged: this is supported retained-capability/state hardening, not a JVM sandbox.

### Enum regression matrix

P19-ENUM-01..08 cover:

- ordinary stateless enum -> accepted;
- base enum mutable field -> rejected;
- constant-specific `var counter` -> rejected;
- constant-specific nested mutable object -> rejected;
- constant-specific writer-like retained capability -> rejected;
- repeated-request state variation prevented because unsafe provider cannot register;
- previous mutable collection/inherited unsafe provider guards remain active;
- safe scalar/String/stateless-enum configuration remains usable.

## P19-C5-009 — release provenance

Status: FIXED.

### Confirmed old failure mode

The previous `build-alpha.yml` ran on ordinary pushes with `contents: write` and automatically updated the same rolling GitHub Release/tag/version using `--clobber`.

Consequently later report/housekeeping/phase pushes could replace the published APK under the same persistent user-facing release identity after an exact Phase CI run had been audited.

Immediately before the safe workflow separation, the rolling published release state was recorded as:

- release ID: `367217333`;
- tag: `v1.2.0-alpha5-hybrid140`;
- release `updated_at`: `2026-08-14T15:56:03Z`;
- APK asset ID: `514606226`;
- APK digest: `sha256:ab94ca98dab19c0da47e8fa8cfad61d53d06f3226c30b7c6e9b288173fe2db4a`.

This already differed from the earlier audited exact-target APK and therefore confirmed the provenance blocker.

### Development validation CI

`.github/workflows/build-alpha.yml` is now the development validation workflow `Validate RPG OS ALPHA`.

It:

- runs on normal pushes and can also be dispatched manually;
- has `permissions: contents: read`;
- runs permanent release-workflow separation validation;
- runs project validation;
- runs full `:app:testDebugUnitTest`;
- restores the permanent signing key and builds a signed validation APK;
- writes APK checksum plus `build-provenance.json` containing exact `github.sha`, workflow run ID, version identity and APK SHA-256;
- uploads an Actions artifact whose name contains the exact full head SHA;
- contains no GitHub Release create/update command;
- contains no `--clobber`;
- has no authority to mutate repository release assets.

Phase auditing therefore uses:

exact runtime SHA -> exact development CI run -> immutable Actions artifact.

### Explicit CHAT-6 publication workflow

`.github/workflows/publish-alpha.yml` is the separate user-application publication path.

It:

- uses `workflow_dispatch` only;
- does not run on ordinary pushes;
- requires an explicit `accepted_sha`;
- requires `confirm_release_owner = CHAT-6`;
- checks out exactly `accepted_sha` and verifies `git rev-parse HEAD` equals that input;
- retains project/JVM/signing validation for the publication owner;
- fails if the release tag for the current version already exists;
- requires a new user-facing version identity before publication rather than overwriting an existing tag/version;
- creates a new release explicitly with target equal to accepted SHA;
- never uses `--clobber`.

No Phase-19 application release was invoked by CHAT-1.

### Permanent workflow validator

`tools/validate_release_workflows.py` statically enforces the separation in development CI:

- dev workflow remains push-enabled but read-only;
- dev path has exact-SHA Actions artifact/provenance;
- dev path has no `gh release` or `--clobber`;
- publication is explicit `workflow_dispatch` only;
- publication requires exact accepted SHA and CHAT-6 confirmation;
- publication checks out/verifies exact accepted SHA;
- tag reuse fails closed;
- publication creates instead of silently replacing a user release.

### Published release non-mutation proof

After all Phase-19 hotfix pushes and after exact final development run #505 completed, the previously recorded rolling release was fetched again.

It remained exactly:

- `updated_at`: `2026-08-14T15:56:03Z`;
- APK asset ID: `514606226`;
- APK digest: `sha256:ab94ca98dab19c0da47e8fa8cfad61d53d06f3226c30b7c6e9b288173fe2db4a`.

Therefore exact development CI #505 did not modify the published user application release.

## Exact final development CI

Final runtime SHA:

`6287fb2612afc9b60c7a9d47508cb0fcb79dbb67`

Canonical development validation:

- workflow: `Validate RPG OS ALPHA`;
- run number: `505`;
- run ID: `31818203973`;
- head SHA: `6287fb2612afc9b60c7a9d47508cb0fcb79dbb67`;
- status: `completed`;
- conclusion: `success`.

Successful gates:

- release workflow separation validation — SUCCESS;
- Validate project — SUCCESS;
- Run JVM unit tests — SUCCESS;
- Build signed validation APK — SUCCESS;
- Prepare immutable validation artifact — SUCCESS;
- Upload immutable Actions artifact — SUCCESS;
- overall workflow — SUCCESS.

A prior candidate run #501 usefully exposed eight test failures, all caused by the newly added authority test fixture retaining a full `WorldPackRuleBinding` object. The production provider-state guard correctly rejected that fixture. The fixture was corrected forward-only to retain only allowed scalar state; no production guard was weakened.

## Exact Actions artifact

Run #505 produced:

- artifact ID: `9226017383`;
- name: `RPG-OS-VALIDATION-1.2.0-alpha5-hybrid140-6287fb2612afc9b60c7a9d47508cb0fcb79dbb67`;
- artifact digest: `sha256:ed55bfb2d787d4cadd4d6963979d5047bb8e08706b7479dd4f17b7c7928757d2`;
- workflow head SHA: `6287fb2612afc9b60c7a9d47508cb0fcb79dbb67`.

The artifact is an exact-SHA validation artifact; it is deliberately not a published application release.

## Runtime tree cleanliness

The final runtime contains only the two permanent workflows:

- `.github/workflows/build-alpha.yml` — development validation;
- `.github/workflows/publish-alpha.yml` — explicit CHAT-6 publication.

All temporary Phase-19 hotfix/fixture applicator workflows were removed forward-only.

All temporary Phase-19 patch scripts were removed forward-only. The final runtime has no `scripts/` directory.

The permanent workflow validation tool under `tools/validate_release_workflows.py` remains intentionally.

## Preserved canonical hardening

The hotfix did not redesign or weaken the already-hardened Phase-19 canonical identity path:

- `RPGOS-WORLD-RULE-CANONICAL` format/version remains;
- structural NULL/VALUE encoding remains;
- explicit ALLOWED/REJECTED discriminator remains;
- effect snapshot framing remains;
- context section framing remains;
- proposal UID structural preimage remains;
- request/decision replayability remains;
- deterministic ordering remains.

Full JVM regression success on exact final SHA includes the original P19-01..30 and P19-H1..H7 suites together with the new AUTH/ENUM suites.

## Phase-18 / Phase-17 locks

Phase-18 ordering remains:

canonical command validation
-> Phase-18 command reference validation
-> authoritative World Pack binding validation
-> Phase-19 COMMAND_PRECHECK
-> resolution
-> Phase-18 draft reference validation
-> Phase-19 DRAFT_EFFECT_CHECK
-> engine-owned PlayerChangeSet.

The authority validation occurs only after Phase-18 command references, preserving UNKNOWN_REFERENCE / WRONG_CAMPAIGN_REFERENCE precedence over provider evaluation.

Phase-18 classification semantics were not changed:

- equipment slot remains B;
- ownership record remains D;
- asset/fromOwner/toOwner remain A;
- finance authoritative references remain A;
- command/draft reference closure remains.

Phase-17 PlayerChangeSet/value-object/financial/serialization/fingerprint contracts were not modified. The runtime diff contains no PlayerChangeSet schema/codec modification.

## Mutation / separation boundaries

WorldRuleProvider and authority validation remain read-only proposal-orchestration mechanisms.

No authoritative database mutation, StatePatch execution, TurnTransaction or COMMIT capability was added.

No database migration or schema change was introduced.

No progression, diminishing returns, passive progression, Progression Ledger or other Phase-20 runtime implementation was introduced.

## App version

No user-facing version bump was made during this Phase-19 hotfix:

- versionName: `1.2.0-alpha5-hybrid140`;
- versionCode: `140`.

The existing rolling release is not republished by this phase. CHAT-6 must intentionally publish a future cumulative accepted runtime with a new trustworthy version/tag identity.

## Final CHAT-1 result

All three confirmed blocker classes are closed in final runtime `6287fb2612afc9b60c7a9d47508cb0fcb79dbb67` with exact development CI SUCCESS and immutable exact-SHA artifact evidence.

CHAT-1 verdict: PASS.

Phase 19 status: IMPLEMENTED — awaiting fresh independent revalidation. CHAT-1 does not globally accept Phase 19.

Phase 20 status: PLANNING ONLY; implementation remains blocked until Phase 19 global acceptance.
