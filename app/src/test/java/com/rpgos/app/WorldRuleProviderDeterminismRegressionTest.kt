package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WorldRuleProviderDeterminismRegressionTest {
    @Test fun p19_29_projectEffectFingerprintDoesNotDependOnObjectIdentity() {
        val first = WorldRuleEffectSnapshot.create(projectDraft("EVIDENCE:E1"))
        val second = WorldRuleEffectSnapshot.create(projectDraft("EVIDENCE:E1"))
        assertEquals(first.deterministicFingerprint(), second.deterministicFingerprint())
    }

    @Test fun p19_30_semanticallyDifferentProjectEffectChangesFingerprint() {
        val first = WorldRuleEffectSnapshot.create(projectDraft("EVIDENCE:E1"))
        val second = WorldRuleEffectSnapshot.create(projectDraft("EVIDENCE:E2"))
        assertNotEquals(first.deterministicFingerprint(), second.deterministicFingerprint())
    }

    private fun projectDraft(evidenceUid: String): PlayerResolutionDraft {
        val change = PlayerDomainChange.create(
            "CH-P19-PROJECT",
            PlayerChangeKinds.DEVELOPMENT_PROJECT,
            DevelopmentProjectChange.create(
                "PROJECT:P1",
                "SUCCESS",
                ProjectProgressDelta.of(0L),
                listOf(DomainRef("EVIDENCE", evidenceUid))
            )
        )
        return PlayerResolutionDraft.create(changes = listOf(change))
    }
}
