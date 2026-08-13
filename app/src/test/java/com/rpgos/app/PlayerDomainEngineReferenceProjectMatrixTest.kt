package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerDomainEngineReferenceProjectMatrixTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private fun ref(kind: String, uid: String) = DomainRef(kind, uid)
    private fun references(kind: String, payload: PlayerCommandPayload): List<DomainRef> {
        val input = PlayerCommand(
            commandUid = "MATRIX-INPUT",
            campaignUid = "C1",
            actor = actor,
            commandKindUid = kind,
            payload = payload,
            provenance = CommandProvenance("TEST")
        )
        return commandReferences(input)
    }

    @Test fun existingProjectReferencesAreComplete() {
        val evidence = ref("EVIDENCE", "E1")
        assertEquals(
            listOf(ref("PROJECT", "P1"), evidence),
            references(PlayerCommandKinds.COMPLETE_PROJECT, CompleteProjectCommandPayload("P1", listOf(evidence)))
        )
        assertEquals(
            listOf(ref("PROJECT", "P1")),
            references(PlayerCommandKinds.CANCEL_PROJECT, CancelProjectCommandPayload("P1"))
        )
        assertEquals(
            listOf(ref("PROJECT", "P1"), ref("PROJECT", "P2")),
            references(PlayerCommandKinds.CHANGE_PROJECT_LIFECYCLE, ChangeProjectLifecycleCommandPayload("P1", "ACTIVE", "P2"))
        )
    }
}