package com.rpgos.app

import java.util.Collections

enum class CharacterPanelSnapshotClassification { DERIVED_PRESENTATION }

data class CharacterPanelIdentityV2(val keyUid: String, val value: String)
data class CharacterPanelExactValueV2(val targetUid: String, val exactValue: Long, val semanticsUid: String)
data class CharacterPanelMasteryV2(val targetUid: String, val exactProgress: Long, val displayName: String?)
data class CharacterPanelProfileValueV2(val domainUid: String, val dimensionUid: String?, val canonicalValue: String, val evidenceUid: String?)
data class CharacterPanelInnateV2(val innateUid: String, val stateUid: String, val canonicalValue: String?)
data class CharacterPanelInventoryV2(val itemInstanceUid: String, val definitionUid: String?, val quantity: Long)
data class CharacterPanelEquipmentV2(val slotUid: String, val itemInstanceUid: String?)
data class CharacterPanelOwnershipV2(val assetKindUid: String, val assetUid: String, val ownerUid: String)
data class CharacterPanelEconomyV2(val currencyUid: String, val exactBalance: Long, val authorityRecordUid: String)
data class CharacterPanelProgressionV2(val targetKindUid: String, val targetUid: String, val exactValue: Long, val provenanceStatusUid: String?)
data class CharacterPanelProjectV2(val projectUid: String, val lifecycleUid: String, val exactProgress: Long)
data class CharacterPanelRelationshipV2(val otherEntityUid: String, val relationshipTypeUid: String, val exactScore: Long)
data class CharacterPanelGoalV2(val goalUid: String, val title: String, val priority: Long)

/**
 * Read-only source contract. Implementations adapt existing authoritative/current stores.
 * The snapshot owns no write path and may be discarded and rebuilt at any time.
 */
interface CharacterPanelV2ReadSource {
    fun identity(campaignUid: String, characterUid: String): List<CharacterPanelIdentityV2>
    fun stats(campaignUid: String, characterUid: String): List<CharacterPanelExactValueV2>
    fun resources(campaignUid: String, characterUid: String): List<CharacterPanelExactValueV2>
    fun skills(campaignUid: String, characterUid: String): List<CharacterPanelMasteryV2>
    fun techniques(campaignUid: String, characterUid: String): List<CharacterPanelMasteryV2>
    fun talent(campaignUid: String, characterUid: String): List<CharacterPanelProfileValueV2>
    fun potential(campaignUid: String, characterUid: String): List<CharacterPanelProfileValueV2>
    fun innateAndEvolution(campaignUid: String, characterUid: String): List<CharacterPanelInnateV2>
    fun inventory(campaignUid: String, characterUid: String): List<CharacterPanelInventoryV2>
    fun equipment(campaignUid: String, characterUid: String): List<CharacterPanelEquipmentV2>
    fun ownershipAndAssets(campaignUid: String, characterUid: String): List<CharacterPanelOwnershipV2>
    fun economy(campaignUid: String, characterUid: String): List<CharacterPanelEconomyV2>
    fun progression(campaignUid: String, characterUid: String): List<CharacterPanelProgressionV2>
    fun projects(campaignUid: String, characterUid: String): List<CharacterPanelProjectV2>
    fun relationships(campaignUid: String, characterUid: String): List<CharacterPanelRelationshipV2>
    fun goals(campaignUid: String, characterUid: String): List<CharacterPanelGoalV2>
}

class CharacterPanelSnapshotV2 private constructor(
    val campaignUid: String,
    val characterUid: String,
    val classification: CharacterPanelSnapshotClassification,
    identity: List<CharacterPanelIdentityV2>,
    stats: List<CharacterPanelExactValueV2>,
    resources: List<CharacterPanelExactValueV2>,
    skills: List<CharacterPanelMasteryV2>,
    techniques: List<CharacterPanelMasteryV2>,
    talent: List<CharacterPanelProfileValueV2>,
    potential: List<CharacterPanelProfileValueV2>,
    innateAndEvolution: List<CharacterPanelInnateV2>,
    inventory: List<CharacterPanelInventoryV2>,
    equipment: List<CharacterPanelEquipmentV2>,
    ownershipAndAssets: List<CharacterPanelOwnershipV2>,
    economy: List<CharacterPanelEconomyV2>,
    progression: List<CharacterPanelProgressionV2>,
    projects: List<CharacterPanelProjectV2>,
    relationships: List<CharacterPanelRelationshipV2>,
    goals: List<CharacterPanelGoalV2>,
    val fingerprint: String
) {
    val identity = frozen(identity)
    val stats = frozen(stats)
    val resources = frozen(resources)
    val skills = frozen(skills)
    val techniques = frozen(techniques)
    val talent = frozen(talent)
    val potential = frozen(potential)
    val innateAndEvolution = frozen(innateAndEvolution)
    val inventory = frozen(inventory)
    val equipment = frozen(equipment)
    val ownershipAndAssets = frozen(ownershipAndAssets)
    val economy = frozen(economy)
    val progression = frozen(progression)
    val projects = frozen(projects)
    val relationships = frozen(relationships)
    val goals = frozen(goals)

    override fun equals(other: Any?): Boolean = other is CharacterPanelSnapshotV2 &&
        campaignUid == other.campaignUid && characterUid == other.characterUid && classification == other.classification &&
        identity == other.identity && stats == other.stats && resources == other.resources && skills == other.skills &&
        techniques == other.techniques && talent == other.talent && potential == other.potential &&
        innateAndEvolution == other.innateAndEvolution && inventory == other.inventory && equipment == other.equipment &&
        ownershipAndAssets == other.ownershipAndAssets && economy == other.economy && progression == other.progression &&
        projects == other.projects && relationships == other.relationships && goals == other.goals && fingerprint == other.fingerprint

    override fun hashCode(): Int = arrayOf(
        campaignUid, characterUid, classification, identity, stats, resources, skills, techniques, talent, potential,
        innateAndEvolution, inventory, equipment, ownershipAndAssets, economy, progression, projects, relationships, goals, fingerprint
    ).contentHashCode()

    companion object {
        internal fun create(
            campaignUid: String, characterUid: String,
            identity: List<CharacterPanelIdentityV2>, stats: List<CharacterPanelExactValueV2>,
            resources: List<CharacterPanelExactValueV2>, skills: List<CharacterPanelMasteryV2>,
            techniques: List<CharacterPanelMasteryV2>, talent: List<CharacterPanelProfileValueV2>,
            potential: List<CharacterPanelProfileValueV2>, innateAndEvolution: List<CharacterPanelInnateV2>,
            inventory: List<CharacterPanelInventoryV2>, equipment: List<CharacterPanelEquipmentV2>,
            ownershipAndAssets: List<CharacterPanelOwnershipV2>, economy: List<CharacterPanelEconomyV2>,
            progression: List<CharacterPanelProgressionV2>, projects: List<CharacterPanelProjectV2>,
            relationships: List<CharacterPanelRelationshipV2>, goals: List<CharacterPanelGoalV2>, fingerprint: String
        ) = CharacterPanelSnapshotV2(
            campaignUid, characterUid, CharacterPanelSnapshotClassification.DERIVED_PRESENTATION,
            identity, stats, resources, skills, techniques, talent, potential, innateAndEvolution,
            inventory, equipment, ownershipAndAssets, economy, progression, projects, relationships, goals, fingerprint
        )
    }
}

object CharacterPanelSnapshotV2Builder {
    const val BUILDER_UID = "RPGOS-CORE:CHARACTER_PANEL_SNAPSHOT_V2_BUILDER"
    const val BUILDER_VERSION = "1"

    fun build(source: CharacterPanelV2ReadSource, campaignUid: String, characterUid: String): CharacterPanelSnapshotV2 {
        require(campaignUid.isNotBlank() && characterUid.isNotBlank())
        val identity = source.identity(campaignUid, characterUid).sortedWith(compareBy({ it.keyUid }, { it.value }))
        val stats = source.stats(campaignUid, characterUid).sortedWith(compareBy({ it.targetUid }, { it.semanticsUid }, { it.exactValue }))
        val resources = source.resources(campaignUid, characterUid).sortedWith(compareBy({ it.targetUid }, { it.semanticsUid }, { it.exactValue }))
        val skills = source.skills(campaignUid, characterUid).sortedWith(compareBy({ it.targetUid }, { it.exactProgress }, { it.displayName ?: "" }))
        val techniques = source.techniques(campaignUid, characterUid).sortedWith(compareBy({ it.targetUid }, { it.exactProgress }, { it.displayName ?: "" }))
        val talent = source.talent(campaignUid, characterUid).sortedWith(compareBy({ it.domainUid }, { it.dimensionUid ?: "" }, { it.canonicalValue }, { it.evidenceUid ?: "" }))
        val potential = source.potential(campaignUid, characterUid).sortedWith(compareBy({ it.domainUid }, { it.dimensionUid ?: "" }, { it.canonicalValue }, { it.evidenceUid ?: "" }))
        val innate = source.innateAndEvolution(campaignUid, characterUid).sortedWith(compareBy({ it.innateUid }, { it.stateUid }, { it.canonicalValue ?: "" }))
        val inventory = source.inventory(campaignUid, characterUid).sortedWith(compareBy({ it.itemInstanceUid }, { it.definitionUid ?: "" }, { it.quantity }))
        val equipment = source.equipment(campaignUid, characterUid).sortedWith(compareBy({ it.slotUid }, { it.itemInstanceUid ?: "" }))
        val ownership = source.ownershipAndAssets(campaignUid, characterUid).sortedWith(compareBy({ it.assetKindUid }, { it.assetUid }, { it.ownerUid }))
        val economy = source.economy(campaignUid, characterUid).sortedWith(compareBy({ it.currencyUid }, { it.authorityRecordUid }, { it.exactBalance }))
        val progression = source.progression(campaignUid, characterUid).sortedWith(compareBy({ it.targetKindUid }, { it.targetUid }, { it.exactValue }, { it.provenanceStatusUid ?: "" }))
        val projects = source.projects(campaignUid, characterUid).sortedWith(compareBy({ it.projectUid }, { it.lifecycleUid }, { it.exactProgress }))
        val relationships = source.relationships(campaignUid, characterUid).sortedWith(compareBy({ it.otherEntityUid }, { it.relationshipTypeUid }, { it.exactScore }))
        val goals = source.goals(campaignUid, characterUid).sortedWith(compareBy({ it.goalUid }, { it.priority }, { it.title }))

        val fingerprint = progressionFingerprint(
            "CHARACTER_PANEL_SNAPSHOT_V2", BUILDER_UID, BUILDER_VERSION, campaignUid, characterUid,
            identity.joinToString("|") { "${it.keyUid}=${it.value}" },
            stats.joinToString("|") { "${it.targetUid}:${it.exactValue}:${it.semanticsUid}" },
            resources.joinToString("|") { "${it.targetUid}:${it.exactValue}:${it.semanticsUid}" },
            skills.joinToString("|") { "${it.targetUid}:${it.exactProgress}:${it.displayName ?: "<NULL>"}" },
            techniques.joinToString("|") { "${it.targetUid}:${it.exactProgress}:${it.displayName ?: "<NULL>"}" },
            talent.joinToString("|") { "${it.domainUid}:${it.dimensionUid ?: "<NULL>"}:${it.canonicalValue}:${it.evidenceUid ?: "<NULL>"}" },
            potential.joinToString("|") { "${it.domainUid}:${it.dimensionUid ?: "<NULL>"}:${it.canonicalValue}:${it.evidenceUid ?: "<NULL>"}" },
            innate.joinToString("|") { "${it.innateUid}:${it.stateUid}:${it.canonicalValue ?: "<NULL>"}" },
            inventory.joinToString("|") { "${it.itemInstanceUid}:${it.definitionUid ?: "<NULL>"}:${it.quantity}" },
            equipment.joinToString("|") { "${it.slotUid}:${it.itemInstanceUid ?: "<NULL>"}" },
            ownership.joinToString("|") { "${it.assetKindUid}:${it.assetUid}:${it.ownerUid}" },
            economy.joinToString("|") { "${it.currencyUid}:${it.exactBalance}:${it.authorityRecordUid}" },
            progression.joinToString("|") { "${it.targetKindUid}:${it.targetUid}:${it.exactValue}:${it.provenanceStatusUid ?: "<NULL>"}" },
            projects.joinToString("|") { "${it.projectUid}:${it.lifecycleUid}:${it.exactProgress}" },
            relationships.joinToString("|") { "${it.otherEntityUid}:${it.relationshipTypeUid}:${it.exactScore}" },
            goals.joinToString("|") { "${it.goalUid}:${it.priority}:${it.title}" }
        )
        return CharacterPanelSnapshotV2.create(
            campaignUid, characterUid, identity, stats, resources, skills, techniques, talent, potential,
            innate, inventory, equipment, ownership, economy, progression, projects, relationships, goals, fingerprint
        )
    }
}

private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
