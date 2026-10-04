package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64PlayerProcessProjectionTest {
    private val player = DomainRef("PLAYER", "P")
    private val scope = TemporalScope("C", "GEN", 3, "STATE")
    private val rule = BackgroundProcessDefinition("R", 1, "PROJECT", "RESEARCH", 60_000,
        parameters = mapOf("secret_recipe" to "PRIVATE_RECIPE"))
    private val definitions = mapOf((rule.uid to rule.version) to rule)
    private fun process(uid: String = "ONE", actor: DomainRef = player,
                        status: BackgroundProcessStatus = BackgroundProcessStatus.ACTIVE,
                        version: Long = 1) = BackgroundProcessInstance(uid, rule.uid, rule.version, actor, version,
        WorldTimeTick(1000), WorldTimeTick(61_000), status,
        parameters = mapOf("message_text" to "PRIVATE_MESSAGE", "recipient_uid" to "SECRET_RECIPIENT"),
        reasonUid = "PRIVATE_OWNER_REASON")
    private fun snapshot(vararg rows: BackgroundProcessInstance) =
        Phase64PlayerProcessProjection.capture(scope, player, rows.toList(), definitions)
    private fun reference(phrase: String) = IntentReference("REF", IntentReferenceKind.DESCRIPTIVE, phrase, "TARGET")

    @Test fun projectsOnlyOwnActiveAndBlockedRowsBeforeAnyPresentation() {
        val captured = snapshot(process(), process("BLOCKED", status = BackgroundProcessStatus.BLOCKED),
            process("NPC", DomainRef("NPC", player.uid)), process("OTHER", DomainRef("PLAYER", "OTHER")),
            process("DONE", status = BackgroundProcessStatus.COMPLETED),
            process("INTERRUPTED", status = BackgroundProcessStatus.INTERRUPTED),
            process("FAILED", status = BackgroundProcessStatus.FAILED))
        assertEquals(setOf("ONE", "BLOCKED"), captured.rows.map { it.processRef.uid }.toSet())
        assertTrue(captured.complete)
        assertEquals("Trwa", captured.rows.single { it.processRef.uid == "ONE" }.statusText)
        assertEquals("Wstrzymany", captured.rows.single { it.processRef.uid == "BLOCKED" }.statusText)
        val exposed = captured.rows.joinToString { it.toString() + it.contextValues() }
        listOf("PRIVATE_RECIPE", "PRIVATE_MESSAGE", "SECRET_RECIPIENT", "PRIVATE_OWNER_REASON", "NPC", "OTHER")
            .forEach { assertFalse(it, exposed.contains(it)) }
    }

    @Test fun exactHandleUidAndDisplayedLabelResolveToTheSameOwnedProcess() {
        val captured = snapshot(process())
        val row = captured.rows.single()
        for (phrase in listOf(row.handle, row.processRef.uid, row.displayLabel, " ${row.displayLabel.lowercase()} ")) {
            val resolved = Phase64PlayerProcessProjection.resolve(captured, reference(phrase))
            assertEquals(IntentReferenceState.RESOLVED_PROJECTED, resolved.state)
            assertEquals(row.processRef, resolved.resolvedProjectedRef)
            assertTrue(resolved.candidateProjectedRefs.isEmpty())
            assertTrue(Phase64PlayerProcessProjection.isCurrentBinding(captured, resolved))
        }
    }

    @Test fun categoryNeverPicksAnArbitraryMemberEvenWhenProviderClaimsCategoryShape() {
        val captured = snapshot(process("ONE"), process("TWO"))
        for (phrase in listOf("proces", "moje zadanie", "Projekt")) {
            val resolved = Phase64PlayerProcessProjection.resolve(captured,
                reference(phrase).copy(descriptorHints = mapOf("shape" to "CATEGORY", "ordinal" to "1")))
            assertEquals(IntentReferenceState.AMBIGUOUS, resolved.state)
            assertNull(resolved.resolvedProjectedRef)
            assertEquals(setOf("ONE", "TWO"), resolved.candidateProjectedRefs.map { it.uid }.toSet())
        }
    }

    @Test fun soleExplicitCategoryMemberCanResolveButUnknownAndPartialHandlesCannot() {
        val captured = snapshot(process())
        assertEquals(IntentReferenceState.RESOLVED_PROJECTED,
            Phase64PlayerProcessProjection.resolve(captured, reference("proces")).state)
        for (phrase in listOf("brakujący proces", "ONE extra", captured.rows.single().handle.take(8), "następny")) {
            val resolved = Phase64PlayerProcessProjection.resolve(captured, reference(phrase))
            assertEquals(IntentReferenceState.UNRESOLVED, resolved.state)
            assertNull(resolved.resolvedProjectedRef)
            assertTrue(resolved.candidateProjectedRefs.isEmpty())
        }
        assertEquals(IntentReferenceState.UNRESOLVED,
            Phase64PlayerProcessProjection.resolve(snapshot(), reference("proces")).state)
    }

    @Test fun forgedResolvedNpcAndOtherPlayerReferencesAreCleared() {
        val captured = snapshot(process(), process("HIDDEN", DomainRef("NPC", "N")))
        val forged = reference("HIDDEN").copy(state = IntentReferenceState.RESOLVED_PROJECTED,
            resolvedProjectedRef = DomainRef("WORLD_PROCESS", "HIDDEN"), resolutionEvidenceUid = "MODEL:FAKE",
            descriptorHints = mapOf(Phase64PlayerProcessProjection.VERSION_HINT to "1",
                Phase64PlayerProcessProjection.FINGERPRINT_HINT to "FAKE"))
        val resolved = Phase64PlayerProcessProjection.resolve(captured, forged)
        assertEquals(IntentReferenceState.UNRESOLVED, resolved.state)
        assertNull(resolved.resolvedProjectedRef)
        assertNull(resolved.resolutionEvidenceUid)
        assertFalse(resolved.descriptorHints.containsKey(Phase64PlayerProcessProjection.VERSION_HINT))
        assertFalse(Phase64PlayerProcessProjection.isCurrentBinding(captured, forged))
    }

    @Test fun handlesSurviveReconstructionButBindingTracksVersionFingerprintAndHistory() {
        val original = snapshot(process())
        val resolved = Phase64PlayerProcessProjection.resolve(original, reference(original.rows.single().handle))
        val advanced = snapshot(process(version = 2))
        assertEquals(original.rows.single().handle, advanced.rows.single().handle)
        assertFalse(Phase64PlayerProcessProjection.isCurrentBinding(advanced, resolved))
        val modified = snapshot(process().copy(parameters = mapOf("message_text" to "DIFFERENT")))
        assertFalse(Phase64PlayerProcessProjection.isCurrentBinding(modified, resolved))
        val changedHistory = advanced.copy(scope = scope.copy(historyGenerationUid = "UNDO_GEN"))
        assertFalse(Phase64PlayerProcessProjection.isCurrentBinding(changedHistory, resolved))
        val stale = Phase64PlayerProcessProjection.resolve(original, reference("ONE"), changedHistory.scope)
        assertEquals(IntentReferenceState.UNRESOLVED, stale.state)
        assertEquals("P64:CANCELLATION_STALE_PROJECTION", stale.descriptorHints["world_resolution_reason"])
    }

    @Test fun projectionIsDeterministicAndCannotLeakNpcRowsToAnotherPrincipalKind() {
        val first = process("FIRST").copy(due = WorldTimeTick(30_000))
        val second = process("SECOND")
        assertEquals(snapshot(first, second), snapshot(second, first))
        val npcSnapshot = Phase64PlayerProcessProjection.capture(scope, DomainRef("NPC", player.uid),
            listOf(process(actor = DomainRef("NPC", player.uid))), definitions)
        assertTrue(npcSnapshot.rows.isEmpty())
        assertEquals(IntentReferenceState.UNRESOLVED,
            Phase64PlayerProcessProjection.resolve(npcSnapshot, reference("proces")).state)
    }

    @Test fun truncationCannotTurnAnAmbiguousCategoryIntoAnApparentlyUniqueProcess() {
        val captured = Phase64PlayerProcessProjection.capture(scope, player,
            listOf(process("ONE"), process("TWO")), definitions, limit = 1)
        assertFalse(captured.complete)
        assertEquals(1, captured.rows.size)
        assertEquals(IntentReferenceState.UNRESOLVED,
            Phase64PlayerProcessProjection.resolve(captured, reference("proces")).state)
        assertEquals(IntentReferenceState.RESOLVED_PROJECTED,
            Phase64PlayerProcessProjection.resolve(captured, reference(captured.rows.single().handle)).state)
    }

    @Test fun cancellationAvailabilityIncludesItsOneSecondDurationAndDeadlineEquality() {
        val row = snapshot(process()).rows.single()
        assertTrue(row.canCancelAt(WorldTimeTick(59_999)))
        assertFalse(row.canCancelAt(WorldTimeTick(60_000)))
        assertFalse(row.canCancelAt(WorldTimeTick(61_000)))
        assertFalse(row.canCancelAt(WorldTimeTick(Long.MAX_VALUE)))
    }

    @Test fun missingRuleDuplicateIdentityAndInvalidBudgetFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            Phase64PlayerProcessProjection.capture(scope, player, listOf(process()), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) { snapshot(process(), process()) }
        assertThrows(IllegalArgumentException::class.java) {
            Phase64PlayerProcessProjection.capture(scope, player, listOf(process()), definitions, 0)
        }
    }
}
