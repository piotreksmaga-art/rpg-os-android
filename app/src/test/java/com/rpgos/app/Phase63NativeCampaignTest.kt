package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase63NativeCampaignTest {
    @Test fun nativeStagingIsAtomic() {
        val context:Context=RuntimeEnvironment.getApplication()
        val spec=NativeWorldCreationSpec("Native-${System.nanoTime()}","Górska wioska bez wyjątkowych mocy.","Era własna","Górska wioska")
        val selection=CampaignSelectionManager(context)
        val dir=selection.createNativeCampaign(spec) { db,uid,binding ->
            GameplayRuntimeBootstrap.initialize(db,uid)
            withAdministrativeMutationAuthority(db,uid) {
                SQLiteDatabase.create(null).use { neutral->CharacterCreationDefinitionBootstrap(db,neutral,
                    binding).ensure() }
            }
        }
        val authority=selection.currentWorldPackAuthority()
        assertEquals(CampaignRuleSourceKind.CAMPAIGN_NATIVE,authority.binding.sourceKind)
        assertEquals(selection.activeCampaignId(),authority.binding.worldPackUid)
        assertFalse(File(context.filesDir,"rpgos/worldpacks/Naruto.worldpack").exists())
        SQLiteDatabase.openDatabase(File(dir,"campaign.db").path,null,SQLiteDatabase.OPEN_READONLY).use { db->
            val root=requireNotNull(Phase63WorldStore(db,authority.campaignUid).root())
            assertEquals("Era własna",root.skeleton.era)
            assertEquals(0,db.rawQuery("SELECT COUNT(*) FROM active_player_ref",null).use { it.moveToFirst();it.getInt(0) })
            assertFalse(db.rawQuery("SELECT resource_key FROM resource_definitions",null).use { c->buildList { while(c.moveToNext())add(c.getString(0)) } }.any { it.contains("CHAKRA") })
            NativeWorldReadDatabase.open(db,authority.campaignUid).use { view->
                assertEquals("Górska wioska",WorldReader(view,db).locations().single().name)
                assertTrue(runCatching { view.execSQL("DELETE FROM map_locations_v2") }.isFailure)
            }
            GameplayRuntimeBootstrap.requireReady(db,authority.campaignUid)
        }
    }
    @Test fun failedBootstrapKeepsSelection() {
        val context:Context=RuntimeEnvironment.getApplication()
        val selection=CampaignSelectionManager(context)
        val before=selection.activeCampaignDirName()
        val spec=NativeWorldCreationSpec("Failure-${System.nanoTime()}","Opis","Era","Start")
        assertEquals("injected",runCatching { selection.createNativeCampaign(spec) { _,_,_ ->error("injected") } }.exceptionOrNull()?.message)
        assertEquals(before,selection.activeCampaignDirName())
        assertFalse(File(context.filesDir,"rpgos/saves/${spec.name}.campaign").exists())
    }
    @Test fun failedBaselineNeverActivatesTheNewCampaign() {
        val context:Context=RuntimeEnvironment.getApplication()
        val selection=CampaignSelectionManager(context)
        val previous=selection.activeCampaignDirName()
        val spec=NativeWorldCreationSpec("Baseline-Failure-${System.nanoTime()}","Opis","Era","Start")
        val result=runCatching { selection.createNativeCampaign(spec,beforeActivation={_,_->
            assertEquals(previous,selection.activeCampaignDirName())
            error("baseline-injected")
        }) { db,uid,_->GameplayRuntimeBootstrap.initialize(db,uid) } }
        assertEquals("baseline-injected",result.exceptionOrNull()?.message)
        assertEquals(previous,selection.activeCampaignDirName())
        assertFalse(File(context.filesDir,"rpgos/saves/${spec.name}.campaign").exists())
    }
    @Test fun nativeFlow() {
        val context:Context=RuntimeEnvironment.getApplication()
        val local=LocalGameStore(context)
        local.createNativeCampaign(NativeWorldCreationSpec("Flow-${System.nanoTime()}","Świat bez magii.","Własna era","Wioska"))
        val catalog=local.characterCreationCatalog()
        fun choices(kind:CharacterCreationDefinitionKind)=catalog.options.filter { it.kind==kind }.map {
            CharacterCreationValueChoice(it.definitionUid,50.0.coerceAtLeast(it.minimumValue?:0.0).coerceAtMost(it.maximumValue?:100.0),it.dimensionUid)
        }
        val location=local.worldLocations().single().uid
        val draft=PlayerCharacterCreationDraft("NATIVE-CREATION",catalog.campaignUid,"NATIVE-PLAYER","Smagi","UNSPECIFIED",
            stats=choices(CharacterCreationDefinitionKind.STAT),resources=choices(CharacterCreationDefinitionKind.RESOURCE),
            talents=choices(CharacterCreationDefinitionKind.TALENT),potentials=choices(CharacterCreationDefinitionKind.POTENTIAL),
            skills=choices(CharacterCreationDefinitionKind.SKILL),techniques=choices(CharacterCreationDefinitionKind.TECHNIQUE),startingLocationUid=location)
        val receipt=local.createPlayerCharacter(draft,PlayerCharacterCreationConfirmation(PlayerCharacterBootstrapService.fingerprint(draft),"EXPLICIT-CONFIRM"))
        assertFalse(receipt.idempotentReplay)
        local.openGameplaySaveDb().use { db->
            val body=requireNotNull(MechanicalActorStateStore(db,catalog.campaignUid).actor(DomainRef("PLAYER",draft.playerUid)))
            assertEquals(MechanicalActorKind.ACTIVE_PLAYER,body.kind)
            assertFalse(body.resources.isEmpty())
            val digest=AuthoritativeStateDigest.compute(db)
            db.execSQL("DELETE FROM ${CampaignWorldProjectionSchema.TABLE}")
            assertEquals(digest,AuthoritativeStateDigest.compute(db))
            assertEquals(location,CampaignWorldProjectionStore(db,catalog.campaignUid).searchPlayerVisible("Wioska",
                WorldReferenceShape(WorldReferenceShapeKind.CATEGORY,WorldElementBaseKind.PLACE,"STARTING_PLACE",emptySet(),"LOCAL_SITE")).single().element.uid)
        }
        val reopened=LocalGameStore(context)
        assertEquals(location,reopened.worldLocations().single().uid)
        assertEquals("Wioska",reopened.status().location)
        val bundle=reopened.buildContext("Idę potrenować",1,VisibilityAudienceFactory.player(catalog.campaignUid),
            PurposeContext(catalog.campaignUid,VisibilityPurposeKinds.GAMEPLAY_NARRATION))
        assertTrue(bundle.playerState.isNotEmpty())
        assertTrue(bundle.missions.isEmpty())
        assertFalse(bundle.toString().contains("CHAKRA"))
        // The public confirmation path refreshes every browser, not just the chat/bridge.
        // A native campaign has no Naruto index; the disposable view projects Core definitions.
        val techniques=reopened.techniqueBrowser()
        assertEquals(listOf("Basic action"),techniques.map { it.name })
        assertTrue(techniques.all { it.rank.isEmpty() && it.element.isEmpty() && it.wikiUrl.isEmpty() })
        assertEquals(techniques,reopened.techniqueBrowser("Basic"))
        assertTrue(reopened.techniqueBrowser("chakra").isEmpty())
        assertTrue(reopened.missionBrowser().isEmpty())
        val repository=UnifiedGameRepository(context)
        val frame=repository.infrastructureWorldFrame(VisibilityAudienceFactory.player(catalog.campaignUid),
            PurposeContext(catalog.campaignUid,VisibilityPurposeKinds.GAMEPLAY_NARRATION)) as ProtectedReadResult.Allow
        assertEquals("Świat bez magii.",frame.value["declared_world_premise"])
        assertFalse(frame.value.keys.any { it.contains("seed") || it.contains("latent") })
        assertTrue(repository.infrastructureWorldFrame(VisibilityAudienceFactory.player("OTHER"),
            PurposeContext("OTHER",VisibilityPurposeKinds.GAMEPLAY_NARRATION)) is ProtectedReadResult.Deny)
    }
    @Test fun nativeSourceIsNotUnboundAndCannotBeSubstitutedWithPackAuthority() {
        val native=WorldPackRuleBinding("C","1",CampaignRuleSourceKind.CAMPAIGN_NATIVE)
        val pack=WorldPackRuleBinding("C","1")
        assertNotEquals(native,pack)
        assertNotEquals(native.ruleSource,pack.ruleSource)
        val registry=WorldRuleProviderRegistry.of(listOf(UniversalMechanicsWorldRuleProvider(native),UniversalMechanicsWorldRuleProvider(pack)))
        assertEquals(CampaignRuleSourceKind.CAMPAIGN_NATIVE,registry.providerFor(native)?.sourceKind)
        assertEquals(CampaignRuleSourceKind.WORLD_PACK,registry.providerFor(pack)?.sourceKind)
    }
    @Test fun boundedPresentationKeepsTheExactStartAnchor() {
        val context:Context=RuntimeEnvironment.getApplication()
        val local=LocalGameStore(context)
        local.createNativeCampaign(NativeWorldCreationSpec("Page-${System.nanoTime()}","Neutralny świat.","Era","Start"))
        val campaign=CampaignSelectionManager(context).activeCampaignRef().campaignId
        local.openGameplaySaveDb().use { db->
            val anchor=requireNotNull(Phase63WorldStore(db,campaign).root()).skeleton.initialAnchor
            withAdministrativeMutationAuthority(db,campaign) {
                db.beginTransaction()
                try {
                    repeat(513) { index->
                        val uid="DYN-PLACE-${index.toString().padStart(4,'0')}"
                        val facts=mapOf(CampaignWorldFacts.KIND to "PLACE",CampaignWorldFacts.NAME to "Miejsce $index",
                            CampaignWorldFacts.CATEGORY to "SITE",CampaignWorldFacts.TOPOLOGY to "LOCAL_SITE",
                            CampaignWorldFacts.AUDIENCE_SCOPE to CampaignWorldAudience.PLAYER_VISIBLE,
                            CampaignWorldFacts.SOURCE_CLASSIFICATION to WorldEvidenceClassification.CAMPAIGN_FACT.name)
                        facts.forEach { (predicate,value)->CampaignTruthStore(db,campaign).record(TruthKind.FACT,predicate,
                            Provenance(ProvenanceSourceType.PLAYER_ACTION,sourceId="TEST:PUBLIC_PAGE",verified=true),
                            subjectUid=uid,objectValue=value,truthUid="$uid:$predicate",createdAt=0) }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            assertFalse(CampaignWorldProjectionStore(db,campaign).canonicalPublicElements().any { it.element==anchor })
            val before=AuthoritativeStateDigest.compute(db)
            NativeWorldReadDatabase.open(db,campaign).use { view->
                assertTrue(WorldReader(view,db).locations().any { it.uid==anchor.uid && it.name=="Start" })
            }
            assertEquals(before,AuthoritativeStateDigest.compute(db))
        }
    }
}
