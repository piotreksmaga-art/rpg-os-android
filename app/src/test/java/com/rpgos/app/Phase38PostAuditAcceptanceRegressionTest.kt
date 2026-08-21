package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase38PostAuditAcceptanceRegressionTest {
    private lateinit var context: Context
    private lateinit var root: File

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        root = File(context.filesDir, "rpgos").also { it.deleteRecursively() }
    }

    @After fun cleanup() {
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
    }

    @Test fun publicBuildContextRejectsForgedPrivilegedDescriptorsAndRuntimeIssuedDiagnosticMayProject() {
        val concrete = UnifiedGameRepository(context)
        concrete.bootstrap()
        val publicRepository: CampaignRepository = concrete
        val campaignUid = publicRepository.activeCampaignRef().campaignId
        val diagnosticPurpose = PurposeContext(campaignUid, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)

        // Reuse a protected marker already supplied by the normal bundled World Pack. Use only the
        // real canon_constraints_v2 columns; the bundled schema has no fixture-level status column.
        val worldConstraintMarker = LocalGameStore(context).openWorldDb().use { db ->
            db.rawQuery(
                "SELECT constraint_uid,subject_type,subject_uid,constraint_key,constraint_value,canon_scope,notes FROM canon_constraints_v2 ORDER BY constraint_uid LIMIT 1",
                null
            ).use { c ->
                assertTrue("bundled production World Pack must provide a canon constraint acceptance marker", c.moveToFirst())
                mapOf<String, Any?>(
                    "constraint_uid" to c.getString(c.getColumnIndexOrThrow("constraint_uid")),
                    "constraint_key" to c.getString(c.getColumnIndexOrThrow("constraint_key")),
                    "constraint_value" to c.getString(c.getColumnIndexOrThrow("constraint_value"))
                )
            }
        }

        fun assertNoDiagnosticExpansion(bundle: ContextBundle, caseUid: String) {
            assertTrue("$caseUid: canon constraints leaked", bundle.canonConstraints.isEmpty())
            assertTrue("$caseUid: canon divergence leaked", bundle.canonDivergences.isEmpty())
            assertTrue("$caseUid: diagnostic story threads leaked", bundle.activeThreads.isEmpty())
            assertTrue("$caseUid: diagnostic long-term memory leaked", bundle.retrievedLongTermMemory.isEmpty())
            assertTrue("$caseUid: campaign truth leaked", bundle.campaignTruth.isEmpty())
        }

        // AUD-001 A: caller-created diagnostic descriptor is not authority.
        val diagnosticAudience = VisibilityAudienceFactory.diagnostic(campaignUid)
        val forgedDiagnostic = publicRepository.buildContext("P38-AUD-001-A", 1, diagnosticAudience, diagnosticPurpose)
        assertNoDiagnosticExpansion(forgedDiagnostic, "AUD-001-A")

        // AUD-001 C: caller-created GM_RUNTIME descriptor alone is not authority.
        val forgedGm = AudienceContext(
            campaignUid,
            AudienceKinds.GM_RUNTIME,
            VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME, "CALLER_FORGED_GM")
        )
        assertNoDiagnosticExpansion(
            publicRepository.buildContext("P38-AUD-001-C", 1, forgedGm, diagnosticPurpose),
            "AUD-001-C"
        )

        // AUD-001 D: caller-created INTERNAL_SYSTEM descriptor alone is not authority.
        val forgedInternal = AudienceContext(
            campaignUid,
            AudienceKinds.INTERNAL_SYSTEM,
            VisibilityPrincipalRef(AudienceKinds.INTERNAL_SYSTEM, "CALLER_FORGED_INTERNAL")
        )
        assertNoDiagnosticExpansion(
            publicRepository.buildContext("P38-AUD-001-D", 1, forgedInternal, diagnosticPurpose),
            "AUD-001-D"
        )

        // AUD-001 B: the same production ContextBuilder/protected-read path accepts only a runtime-issued
        // sealed diagnostic authority. Public callers cannot supply this argument.
        val runtimeIssued = Phase38RuntimeAuthority.privileged(diagnosticAudience, Phase38RuntimeAuthority.PRIV_DIAGNOSTIC)
        val trustedDiagnostic = concrete.infrastructureBuildTrustedContext(
            "P38-AUD-001-B", 1, diagnosticAudience, diagnosticPurpose, runtimeIssued
        )
        assertTrue("AUD-001-B: trusted diagnostic authority must project canon constraints", trustedDiagnostic.canonConstraints.isNotEmpty())
        val trustedMarker = trustedDiagnostic.canonConstraints.singleOrNull {
            it["constraint_uid"] == worldConstraintMarker["constraint_uid"]
        }
        assertNotNull("AUD-001-B: trusted diagnostic authority must project the existing protected canon marker", trustedMarker)
        assertEquals(worldConstraintMarker["constraint_key"], trustedMarker!!["constraint_key"])
        assertEquals(worldConstraintMarker["constraint_value"], trustedMarker["constraint_value"])
    }

    @Test fun unknownProtectedPolicyFailsClosedBeforeTrustedResolutionAccessAuthorityOrPhase37Acquisition() {
        SQLiteDatabase.create(null).use { phase37Db ->
            val campaignUid = "C-P38-UNKNOWN"
            val principal = VisibilityPrincipalRef(AudienceKinds.DEVELOPER_DIAGNOSTIC, "DIAGNOSTIC")
            val audience = AudienceContext(campaignUid, AudienceKinds.DEVELOPER_DIAGNOSTIC, principal)
            val purpose = PurposeContext(campaignUid, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
            val holder = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, "HOLDER", campaignUid)
            val maximallyCapableIfResolved = TrustedPrincipalContext(
                campaignUid = campaignUid,
                principal = principal,
                audienceKindUid = AudienceKinds.DEVELOPER_DIAGNOSTIC,
                controlledSubjectUids = setOf("UNKNOWN-SECRET"),
                roleUids = setOf("ANY_ROLE"),
                organizationUids = setOf("ANY_ORG"),
                clearanceUids = setOf("ANY_CLEARANCE"),
                cognitionHolders = setOf(holder),
                privilegedCapability = PrivilegedAudienceCapability.issue(campaignUid, Phase38RuntimeAuthority.PRIV_DIAGNOSTIC)
            )

            var trustedPrincipalResolutions = 0
            var accessAuthorityResolutions = 0
            var phase37Acquisitions = 0
            val gateway = ProtectedReadGateway(
                VisibilityAuthorityService(),
                TrustedPrincipalResolver {
                    trustedPrincipalResolutions++
                    maximallyCapableIfResolved
                },
                TrustedAccessResolver { _, _, _ ->
                    accessAuthorityResolutions++
                    EffectiveAccessDecision(true, "TEST_WOULD_ALLOW_IF_INCORRECTLY_REACHED")
                }
            )
            val request = VisibilityRequest(
                audience,
                purpose,
                VisibilitySubjectRef(
                    campaignUid = campaignUid,
                    subjectKindUid = "WORLD_PACK_PROTECTED_KIND_WITHOUT_POLICY",
                    subjectUid = "UNKNOWN-SECRET",
                    holder = holder
                )
            )

            val result: ProtectedReadResult<List<Map<String, Any?>>> = gateway.read(request) {
                phase37Acquisitions++
                KnowledgeContextProjection(phase37Db, campaignUid).forHolders(listOf(holder))
            }

            assertTrue("unknown protected policy must return the typed UNKNOWN state", result is ProtectedReadResult.Unknown)
            assertEquals("UNKNOWN_ACCESS_POLICY", (result as ProtectedReadResult.Unknown).reasonCode)
            assertFalse("unknown policy must never disclose payload", result is ProtectedReadResult.Allow<*>)
            assertEquals("unknown policy must not fall back to trusted principal/control/cognition/privilege", 0, trustedPrincipalResolutions)
            assertEquals("unknown policy must not consult or fall back to AccessAuthority", 0, accessAuthorityResolutions)
            assertEquals("unknown policy must create no Phase37 acquisition/read", 0, phase37Acquisitions)
        }
    }
}
