# WORK-20260812-071 — Phase 17 release-blocker hotfix implementation note

Scope: Phase 17 only.

This hotfix addresses exactly two audit blockers against runtime `1df30948eb846e7530fcbbb52d56b1b09053d9b4`:

1. Preserve canonical asset identity `(assetKindUid, assetUid)` in `AssetChange` using the existing `OwnedAssetRef`.
2. Enforce immutable FinancialChange ↔ FINANCIAL_TRANSFER ledger term consistency for causal financial links.

The hotfix introduces no PlayerDomainEngine, WorldRuleProvider, ProgressionEngine, TurnTransaction execution, PlayerChangeSet persistence, DB schema, migration, StatePatch bridge, or authoritative mutation path.

The pre-hotfix CHAT-2/CHAT-3/CHAT-5 report-only commits remain preserved in history. Phase 17 remains NOT ACCEPTED until the new exact runtime SHA passes independent CHAT-2, CHAT-3, and CHAT-5 revalidation.
