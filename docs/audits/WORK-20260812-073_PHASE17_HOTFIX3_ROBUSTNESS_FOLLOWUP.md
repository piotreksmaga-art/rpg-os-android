# WORK-20260812-073 — Phase 17 Hotfix3 robustness follow-up

Scope: report-only note; no additional runtime change.

CHAT-5 observed that some malformed nested JSON array element shapes can escape the PlayerChangeSet codec as a library `IllegalArgumentException` instead of the contract-specific `PlayerChangeSetStructuralException` because `.jsonObject` conversion occurs outside localized normalization blocks.

Repository inspection confirms this remains fail-closed: malformed shapes do not decode into a legal `PlayerChangeSet`, do not bypass `PlayerChangeSetValidator`, do not produce lossy canonical state, and do not create authoritative mutation. This is therefore an error-family consistency hardening item, not part of release blocker `P17-ROBUST-ASSET-CONFLICT-KEY-ALIAS-01`.

Per Hotfix3 scope, it is intentionally not changed here. A later dedicated robustness hardening task may normalize all public decode shape failures into the PlayerChangeSet structural error family without changing legal serialization semantics.
