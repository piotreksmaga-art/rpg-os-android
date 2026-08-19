# Mapa plików

Indeks techniczny wspierający `docs/Architektura projektu.md` i `docs/Roadmap.md`. Nie jest osobną architekturą ani roadmapą. Aktualny `master` pozostaje technicznym source of truth.

Źródło bazowe: raport odzyskiwalności Git przygotowany 2026-08-18 dla Phase 1-32; indeks jest rozwijany wraz z kolejnymi zaakceptowanymi fazami. Ścieżki służą jako punkt startowy do nawigacji; przy każdej pracy należy sprawdzić ich bieżący stan na aktualnym `master`.

## Phase 1 — Unified Repository + stable UID
- PRIMARY: `app/src/main/java/com/rpgos/app/ActiveCampaignRef.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/CampaignSelectionManager.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/GameRepository.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/UnifiedGameRepository.kt`
- TEST: `app/src/test/java/com/rpgos/app/ActiveCampaignRefTest.kt`
- DOC: `docs/PHASE_1_UNIFIED_REPOSITORY.md`

## Phase 2 — Campaign Source of Truth
- PRIMARY: `app/src/main/java/com/rpgos/app/CampaignTruthModels.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/CampaignTruthStore.kt`
- TEST: `app/src/test/java/com/rpgos/app/CampaignTruthPolicyTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/CampaignTruthChangeIntegrationTest.kt`
- DOC: `docs/PHASE_2_SOURCE_OF_TRUTH.md`

## Phase 3 — Player State Contract
- PRIMARY: `app/src/main/java/com/rpgos/app/ActivePlayerStore.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/PlayerStateContract.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/PlayerStateStore.kt`
- TEST: `app/src/test/java/com/rpgos/app/PlayerStatePolicyTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/PlayerStatePersistenceTest.kt`
- DOC: `docs/PHASE_3_PLAYER_STATE_CONTRACT.md`

## Phase 4 — Dynamic Stats & Resources
- PRIMARY: `app/src/main/java/com/rpgos/app/StatResourceContract.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/StatResourceStore.kt`
- TEST: `app/src/test/java/com/rpgos/app/StatResourceContractTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/StatResourcePersistenceTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/StatResourceReconciliationTest.kt`

## Phase 5 — DerivedValueResolver + modifiers
- PRIMARY: `app/src/main/java/com/rpgos/app/DerivedValueResolver.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/ModifierModel.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/ModifierStore.kt`
- TEST: `app/src/test/java/com/rpgos/app/DerivedValueResolverTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/ModifierPersistenceTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/EquipmentModifierIntegrationTest.kt`

## Phase 6 — TalentProfile + PotentialProfile
- PRIMARY: `app/src/main/java/com/rpgos/app/ProgressionProfileModel.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/ProgressionProfileStore.kt`
- MIGRATION: `app/src/main/java/com/rpgos/app/Phase6Migration.kt`
- TEST: `app/src/test/java/com/rpgos/app/ProgressionProfilePersistenceTest.kt`

## Phase 7 — Skill model
- PRIMARY: `app/src/main/java/com/rpgos/app/SkillModel.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/SkillStore.kt`
- MIGRATION: `app/src/main/java/com/rpgos/app/Phase7Migration.kt`
- TEST: `app/src/test/java/com/rpgos/app/SkillPersistenceTest.kt`

## Phase 8 — Technique model
- PRIMARY: `app/src/main/java/com/rpgos/app/TechniqueModel.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/TechniqueStore.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/RequirementModel.kt`
- MIGRATION: `app/src/main/java/com/rpgos/app/Phase8Migration.kt`
- TEST: `app/src/test/java/com/rpgos/app/TechniquePersistenceTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/TechniqueFingerprintTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/TechniqueContextBuilderTest.kt`

## Phase 9 — Innate / Racial / Bloodline / Evolution
- PRIMARY: `app/src/main/java/com/rpgos/app/Phase9Model.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/Phase9Store.kt`
- MIGRATION: `app/src/main/java/com/rpgos/app/Phase9Migration.kt`
- MIGRATION: `app/src/main/java/com/rpgos/app/Phase9RequirementMigration.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase9PersistenceTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase9ProductionRoutingTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase9RequirementGatesTest.kt`

## Phase 10 — Inventory
- PRIMARY: `app/src/main/java/com/rpgos/app/InventoryModel.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/InventoryStore.kt`
- TEST: `app/src/test/java/com/rpgos/app/InventoryPersistenceTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/InventoryTransferAndAmbiguityTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/InventoryBackupRestoreTest.kt`

## Phase 11 — Equipment
- PRIMARY: `app/src/main/java/com/rpgos/app/EquipmentModel.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/EquipmentStore.kt`
- TEST: `app/src/test/java/com/rpgos/app/EquipmentPersistenceTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/EquipmentModifierIntegrationTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase11ProductionRoutingTest.kt`

## Phase 12 — Ownership
- PRIMARY: `app/src/main/java/com/rpgos/app/OwnershipModel.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/OwnershipStore.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/OwnershipReferenceRegistry.kt`
- TEST: `app/src/test/java/com/rpgos/app/OwnershipPersistenceTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/OwnershipConcurrencyTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase12ProductionRoutingTest.kt`

## Phase 13 — Financial Ledger / Economy
- PRIMARY: `app/src/main/java/com/rpgos/app/FinancialModel.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/FinancialStore.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/FinancialContextReader.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/Phase13BalanceGuards.kt`
- TEST: `app/src/test/java/com/rpgos/app/FinancialPersistenceTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/FinancialConcurrencyTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase13ProductionRoutingTest.kt`

## Phase 14 — Assets / Liabilities / Net Worth
- PRIMARY: `app/src/main/java/com/rpgos/app/AssetLiabilityModel.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/AssetLiabilityStore.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/Phase14Hardening.kt`
- TEST: `app/src/test/java/com/rpgos/app/AssetLiabilityPersistenceTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/AssetLiabilityConcurrencyTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase14ProductionRoutingTest.kt`

## Phase 15 — DevelopmentProject
- PRIMARY: `app/src/main/java/com/rpgos/app/DevelopmentProjectModel.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/DevelopmentProjectStore.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/ProjectProgressDelta.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/Phase15Hardening.kt`
- TEST: `app/src/test/java/com/rpgos/app/DevelopmentProjectPersistenceTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/DevelopmentProjectConcurrencyTest.kt`

## Phase 16 — PlayerCommand
- PRIMARY: `app/src/main/java/com/rpgos/app/PlayerCommandModel.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/PlayerCommandCoreCodecs.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/PlayerCommandRegistry.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/PlayerCommandStrictJson.kt`
- TEST: `app/src/test/java/com/rpgos/app/PlayerCommandContractTest.kt`

## Phase 17 — PlayerChangeSet
- PRIMARY: `app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt`
- TEST: `app/src/test/java/com/rpgos/app/PlayerChangeSetContractTest.kt`

## Phase 18 — PlayerDomainEngine orchestration
- PRIMARY: `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/PlayerResolutionComponentStateValidator.kt`
- TEST: `app/src/test/java/com/rpgos/app/PlayerDomainEngineTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/PlayerDomainEngineExistingScalarRefTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/PlayerDomainEngineInheritedStateTest.kt`

## Phase 19 — WorldRuleProvider
- PRIMARY: `app/src/main/java/com/rpgos/app/WorldRuleProvider.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/WorldRuleCanonical.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/CanonicalPackageAuthorityGate.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/CanonicalPackageReplacement.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase19CanonicalAuthorityTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase19CanonicalCoherenceTest.kt`
- DOC: `docs/architecture/PHASE19_ACCEPTANCE.md`

## Phase 20 — ProgressionEngine + Progression Ledger
- PRIMARY: `app/src/main/java/com/rpgos/app/ProgressionEngine.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/ProgressionLedgerIntent.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/ProgressionLedgerKindExtension.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase20ProgressionEngineTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase20FactorCanonicalizationRegressionTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Work026ProgressionCommitIntegrationTest.kt`
- DOC: `docs/architecture/PHASE20_ACCEPTANCE.md`

## Phase 21 — Diminishing Returns + passive progression hooks
- PRIMARY: `app/src/main/java/com/rpgos/app/Phase21ProgressionPolicy.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/ProgressionEngine.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase21ProgressionPolicyTest.kt`
- DOC: `docs/architecture/PHASE21_25_ACCEPTANCE.md`

## Phase 22 — Player Invariant Validator / No-Retrogression
- PRIMARY: `app/src/main/java/com/rpgos/app/PlayerInvariantValidator.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase22PlayerInvariantValidatorTest.kt`
- DOC: `docs/architecture/PHASE21_25_ACCEPTANCE.md`

## Phase 23 — Unified Player ledgers + provenance
- PRIMARY: `app/src/main/java/com/rpgos/app/PlayerLedgerProvenance.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/ProgressionEngine.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase23PlayerLedgerProvenanceTest.kt`
- DOC: `docs/architecture/PHASE21_25_ACCEPTANCE.md`

## Phase 24 — CharacterPanelSnapshot v2
- PRIMARY: `app/src/main/java/com/rpgos/app/CharacterPanelSnapshotV2.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/CharacterPanel.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase24CharacterPanelSnapshotV2Test.kt`
- DOC: `docs/architecture/PHASE21_25_ACCEPTANCE.md`

## Phase 25 — PlayerSnapshotBuilder profiles
- PRIMARY: `app/src/main/java/com/rpgos/app/PlayerSnapshotBuilder.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/CharacterPanelSnapshotV2.kt`
- TEST: `app/src/test/java/com/rpgos/app/PlayerSnapshotBuilderTest.kt`
- DOC: `docs/architecture/PHASE21_25_ACCEPTANCE.md`

## Phase 26 — Single Truth Mutation Path
- PRIMARY: `app/src/main/java/com/rpgos/app/CampaignMutationBoundary.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/GameRepository.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/UnifiedGameRepository.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/LocalGameStore.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase26MutationBoundaryTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase26To29PostAuditBlockerRepairTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Work026ProductionInitializationEnforcementTest.kt`

## Phase 27 — Turn Transaction
- PRIMARY: `app/src/main/java/com/rpgos/app/TurnTransaction.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/CampaignMutationBoundary.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase27TurnTransactionTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase26To29PostAuditBlockerRepairTest.kt`

## Phase 28 — Idempotency / double-commit protection
- PRIMARY: `app/src/main/java/com/rpgos/app/TurnTransactionReceiptStore.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/TurnTransaction.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase28TurnIdempotencyTest.kt`

## Phase 29 — Crash recovery / LAST VALID COMMIT
- PRIMARY: `app/src/main/java/com/rpgos/app/TurnTransactionReceiptStore.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/TurnTransaction.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/LocalGameStore.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase29CrashRecoveryTest.kt`

## Phase 30 — Canonical Event Store
- PRIMARY: `app/src/main/java/com/rpgos/app/CampaignEventStore.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/TurnTransaction.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/TurnTransactionReceiptStore.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase30EventStoreTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase30CrossCampaignEventReferenceTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Work036Phase30To32PostAuditRepairTest.kt`

## Phase 31 — Causal Graph
- PRIMARY: `app/src/main/java/com/rpgos/app/CampaignCausalGraph.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/TurnTransaction.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase31CausalGraphTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Work036Phase30To32PostAuditRepairTest.kt`

## Phase 32 — Truth-layer / writer enforcement
- PRIMARY: `app/src/main/java/com/rpgos/app/RuntimeTruthLayerRegistry.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/RuntimePersistentInventory.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/BundledCampaignPersistentFamilies.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/GameplayMutationGate.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/GameplayRuntimeBootstrap.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase32TruthLayerEnforcementTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase32RegistryCompletenessDatabaseTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase32RepositoryWideWriterSourceInventoryTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase32WriterBypassInventoryTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Work036Phase30To32PostAuditRepairTest.kt`

## Phase 33 — Snapshot System
- PRIMARY: `app/src/main/java/com/rpgos/app/CampaignSnapshotSystem.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/TurnTransaction.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/TurnTransactionReceiptStore.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/CampaignEventStore.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/CampaignCausalGraph.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/GameplayRuntimeBootstrap.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/LocalGameStore.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/GameRepository.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/UnifiedGameRepository.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/RuntimeTruthLayerRegistry.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/RuntimePersistentInventory.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase33SnapshotSystemTest.kt`
- ACCEPTED RUNTIME: `b141a590c64b21930abcae6c63353ea93aaf50f4`
- CI: `Validate RPG OS ALPHA` run ID `32217138911`, job ID `95960661888` — SUCCESS
- ARTIFACT: ID `9352815554`, SHA-256 `01fdb47e78a2abdd1c56fa20591431f1f3bb33f1b6f2fcdf40812c517de18e1f`

## Phase 34 — Automatic snapshot retention max 6
- PRIMARY: `app/src/main/java/com/rpgos/app/CampaignSnapshotSystem.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/LocalGameStore.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/BackupManager.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase34SnapshotRetentionTest.kt`
- ACCEPTED RUNTIME: `b141a590c64b21930abcae6c63353ea93aaf50f4`
- CI: `Validate RPG OS ALPHA` run ID `32217138911`, job ID `95960661888` — SUCCESS
- ARTIFACT: ID `9352815554`, SHA-256 `01fdb47e78a2abdd1c56fa20591431f1f3bb33f1b6f2fcdf40812c517de18e1f`

## Phase 35 — Canon Divergence
- PRIMARY: `app/src/main/java/com/rpgos/app/Phase35CanonDivergence.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/CampaignTruthStore.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/TurnTransaction.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/WorldRuleProvider.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/ContextBuilder.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/ContextModels.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/RuntimeTruthLayerRegistry.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/RuntimePersistentInventory.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase35CanonDivergenceTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase30To36PostAuditHardeningTest.kt`
- DOC: `docs/architecture/PHASE35_36_ACCEPTANCE.md`
- ACCEPTED RUNTIME: `7cb61d3cdc42e0c20f2688181d054e55eacbfd8f`
- CI: `Validate RPG OS ALPHA` run ID `32241299329`, job ID `96032227097` — SUCCESS
- ARTIFACT: ID `9361064715`, SHA-256 `73da8802468e0302c5f4548bda9a2240d5c44a17ceed209b27e10c6e11f84b90`

## Phase 36 — Schema Versioning + migration safety + legacy provenance
- PRIMARY: `app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt`
- PRIMARY: `app/src/main/java/com/rpgos/app/Phase36SchemaCompatibilityFingerprint.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/GameplayRuntimeBootstrap.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/GameplayMutationGate.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/CampaignSnapshotSystem.kt`
- SUPPORTING: `app/src/main/java/com/rpgos/app/CampaignRuntimeLifecycleLock.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase36SchemaVersioningTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase30To36PostAuditHardeningTest.kt`
- TEST: `app/src/test/java/com/rpgos/app/Phase33SnapshotSystemTest.kt`
- DOC: `docs/architecture/PHASE35_36_ACCEPTANCE.md`
- ACCEPTED RUNTIME: `7cb61d3cdc42e0c20f2688181d054e55eacbfd8f`
- CI: `Validate RPG OS ALPHA` run ID `32241299329`, job ID `96032227097` — SUCCESS
- ARTIFACT: ID `9361064715`, SHA-256 `73da8802468e0302c5f4548bda9a2240d5c44a17ceed209b27e10c6e11f84b90`

## Kluczowe wspólne dokumenty i runtime assets
- `docs/Architektura projektu.md`
- `docs/Roadmap.md`
- `docs/Mapa plików.md`
- `docs/PROJECT_WORK_PROTOCOL.md`
- `docs/PARALLEL_WORK_COORDINATION.md`
- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- `docs/architecture/PHASE35_36_ACCEPTANCE.md`
- `.github/workflows/build-alpha.yml`
- `.github/workflows/publish-alpha.yml`
- `app/src/main/assets/rpg_core.db`
- `app/src/main/assets/Naruto.worldpack.zip`
- `app/src/main/assets/Naruto_Default.campaign.zip`
