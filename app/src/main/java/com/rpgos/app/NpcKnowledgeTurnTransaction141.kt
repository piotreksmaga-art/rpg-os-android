package com.rpgos.app

/**
 * Atomic boundary for durable NPC-knowledge changes that belong to one accepted turn.
 *
 * All stores are backed by the same campaign.db handle exposed by GameMasterRepositoryFactory.
 * Wrapping their writes in repository.inTransaction therefore guarantees that a failed
 * knowledge update cannot outlive a rolled-back campaign turn.
 */
class NpcKnowledgeTurnTransaction141(
    private val repository: UnifiedCampaignRepository,
    private val stores: SQLiteNpcKnowledgeStores141
) {
    class Scope internal constructor(
        val repository: UnifiedCampaignRepository,
        val retractions: NpcBeliefRetractionStore141,
        val inferences: NpcInferenceStore141,
        val organization: OrganizationKnowledgeStore141,
        val resolutions: NpcKnowledgeResolutionStore141
    )

    suspend fun <T> commit(block: suspend Scope.() -> T): T =
        repository.inTransaction {
            Scope(
                repository = this,
                retractions = stores.retractions,
                inferences = stores.inferences,
                organization = stores.organizations,
                resolutions = stores.resolutions
            ).block()
        }
}
