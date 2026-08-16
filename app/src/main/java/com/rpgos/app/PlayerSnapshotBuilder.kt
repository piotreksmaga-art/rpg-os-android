package com.rpgos.app

import java.util.Collections

enum class PlayerSnapshotProfile { FULL, COMBAT, PROGRESSION, ECONOMY, SOCIAL, GM_CONTEXT }
enum class PlayerSnapshotClassification { DERIVED_PROJECTION }
enum class PlayerTruthClass { FACT, BELIEF, NARRATIVE }

data class PlayerTruthView(
    val truthUid: String,
    val truthClass: PlayerTruthClass,
    val subjectUid: String,
    val canonicalValue: String,
    val evidenceUid: String?
)

/**
 * Canonical Phase-25 input. All values are read-only current/authoritative views.
 * Truth views remain typed; this contract is not an NPC knowledge store.
 */
interface PlayerSnapshotReadSource : CharacterPanelV2ReadSource {
    fun truthViews(campaignUid: String, characterUid: String): List<PlayerTruthView>
}

class PlayerSnapshot private constructor(
    val campaignUid: String,
    val characterUid: String,
    val profile: PlayerSnapshotProfile,
    val classification: PlayerSnapshotClassification,
    val panel: CharacterPanelSnapshotV2,
    truthViews: List<PlayerTruthView>,
    val fingerprint: String
) {
    val truthViews: List<PlayerTruthView> = Collections.unmodifiableList(ArrayList(truthViews))

    companion object {
        internal fun create(
            campaignUid: String,
            characterUid: String,
            profile: PlayerSnapshotProfile,
            panel: CharacterPanelSnapshotV2,
            truthViews: List<PlayerTruthView>,
            fingerprint: String
        ) = PlayerSnapshot(
            campaignUid, characterUid, profile, PlayerSnapshotClassification.DERIVED_PROJECTION,
            panel, truthViews, fingerprint
        )
    }
}

/**
 * Pure deterministic projection builder. Profiles never write, commit, advance time,
 * run progression, or become a replacement source of player truth.
 * Absence from a profile means omitted from this projection, never absent from reality.
 */
object PlayerSnapshotBuilder {
    const val BUILDER_UID = "RPGOS-CORE:PLAYER_SNAPSHOT_BUILDER"
    const val BUILDER_VERSION = "1"

    fun build(source: PlayerSnapshotReadSource, campaignUid: String, characterUid: String, profile: PlayerSnapshotProfile): PlayerSnapshot {
        require(campaignUid.isNotBlank() && characterUid.isNotBlank())
        val full = CharacterPanelSnapshotV2Builder.build(source, campaignUid, characterUid)
        val truth = source.truthViews(campaignUid, characterUid).sortedWith(
            compareBy({ it.truthClass.name }, { it.truthUid }, { it.subjectUid }, { it.canonicalValue }, { it.evidenceUid ?: "" })
        )
        val panel = project(full, profile)
        val visibleTruth = when (profile) {
            PlayerSnapshotProfile.GM_CONTEXT -> truth
            else -> emptyList()
        }
        val fingerprint = progressionFingerprint(
            "PLAYER_SNAPSHOT", BUILDER_UID, BUILDER_VERSION, campaignUid, characterUid, profile.name,
            panel.fingerprint,
            visibleTruth.joinToString("|") {
                "${it.truthClass.name}:${it.truthUid}:${it.subjectUid}:${it.canonicalValue}:${it.evidenceUid ?: "<NULL>"}"
            }
        )
        return PlayerSnapshot.create(campaignUid, characterUid, profile, panel, visibleTruth, fingerprint)
    }

    private fun project(full: CharacterPanelSnapshotV2, profile: PlayerSnapshotProfile): CharacterPanelSnapshotV2 {
        if (profile == PlayerSnapshotProfile.FULL || profile == PlayerSnapshotProfile.GM_CONTEXT) return full
        val source = ProjectionSource(full, profile)
        return CharacterPanelSnapshotV2Builder.build(source, full.campaignUid, full.characterUid)
    }

    private class ProjectionSource(
        private val full: CharacterPanelSnapshotV2,
        private val profile: PlayerSnapshotProfile
    ) : CharacterPanelV2ReadSource {
        private fun combat() = profile == PlayerSnapshotProfile.COMBAT
        private fun progression() = profile == PlayerSnapshotProfile.PROGRESSION
        private fun economy() = profile == PlayerSnapshotProfile.ECONOMY
        private fun social() = profile == PlayerSnapshotProfile.SOCIAL

        override fun identity(campaignUid: String, characterUid: String) = full.identity
        override fun stats(campaignUid: String, characterUid: String) = if (combat() || progression()) full.stats else emptyList()
        override fun resources(campaignUid: String, characterUid: String) = if (combat()) full.resources else emptyList()
        override fun skills(campaignUid: String, characterUid: String) = if (combat() || progression()) full.skills else emptyList()
        override fun techniques(campaignUid: String, characterUid: String) = if (combat() || progression()) full.techniques else emptyList()
        override fun talent(campaignUid: String, characterUid: String) = if (progression()) full.talent else emptyList()
        override fun potential(campaignUid: String, characterUid: String) = if (progression()) full.potential else emptyList()
        override fun innateAndEvolution(campaignUid: String, characterUid: String) = if (combat() || progression()) full.innateAndEvolution else emptyList()
        override fun inventory(campaignUid: String, characterUid: String) = if (combat() || economy()) full.inventory else emptyList()
        override fun equipment(campaignUid: String, characterUid: String) = if (combat()) full.equipment else emptyList()
        override fun ownershipAndAssets(campaignUid: String, characterUid: String) = if (economy()) full.ownershipAndAssets else emptyList()
        override fun economy(campaignUid: String, characterUid: String) = if (economy()) full.economy else emptyList()
        override fun progression(campaignUid: String, characterUid: String) = if (progression()) full.progression else emptyList()
        override fun projects(campaignUid: String, characterUid: String) = if (progression()) full.projects else emptyList()
        override fun relationships(campaignUid: String, characterUid: String) = if (social()) full.relationships else emptyList()
        override fun goals(campaignUid: String, characterUid: String) = if (social()) full.goals else emptyList()
    }
}
