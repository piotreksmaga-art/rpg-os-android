package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase32ProjectionAdversarialTest {
 private class MutableSource:PlayerSnapshotReadSource{
  var identityValue="v1";var statValue=10L
  var truth=listOf(PlayerTruthView("F","FACT".let(PlayerTruthClass::valueOf),"P","fact",null),PlayerTruthView("B",PlayerTruthClass.BELIEF,"P","belief",null),PlayerTruthView("N",PlayerTruthClass.NARRATIVE,"P","narrative",null))
  override fun identity(c:String,p:String)=listOf(CharacterPanelIdentityV2("identity",identityValue))
  override fun stats(c:String,p:String)=listOf(CharacterPanelExactValueV2("STAT",statValue,"EXACT"))
  override fun resources(c:String,p:String)=emptyList<CharacterPanelExactValueV2>()
  override fun skills(c:String,p:String)=emptyList<CharacterPanelMasteryV2>()
  override fun techniques(c:String,p:String)=emptyList<CharacterPanelMasteryV2>()
  override fun talent(c:String,p:String)=emptyList<CharacterPanelProfileValueV2>()
  override fun potential(c:String,p:String)=emptyList<CharacterPanelProfileValueV2>()
  override fun innateAndEvolution(c:String,p:String)=emptyList<CharacterPanelInnateV2>()
  override fun inventory(c:String,p:String)=emptyList<CharacterPanelInventoryV2>()
  override fun equipment(c:String,p:String)=emptyList<CharacterPanelEquipmentV2>()
  override fun ownershipAndAssets(c:String,p:String)=emptyList<CharacterPanelOwnershipV2>()
  override fun economy(c:String,p:String)=emptyList<CharacterPanelEconomyV2>()
  override fun progression(c:String,p:String)=emptyList<CharacterPanelProgressionV2>()
  override fun projects(c:String,p:String)=emptyList<CharacterPanelProjectV2>()
  override fun relationships(c:String,p:String)=emptyList<CharacterPanelRelationshipV2>()
  override fun goals(c:String,p:String)=emptyList<CharacterPanelGoalV2>()
  override fun truthViews(c:String,p:String)=truth
 }

 @Test fun characterPanelStaleObjectCannotOverwriteAndDiscardRebuildReadsCanonicalSource(){
  val source=MutableSource();val stale=CharacterPanelSnapshotV2Builder.build(source,"C","P")
  assertEquals(10L,stale.stats.single().exactValue);assertEquals(CharacterPanelSnapshotClassification.DERIVED_PRESENTATION,stale.classification)
  source.statValue=25L;source.identityValue="v2"
  val rebuilt=CharacterPanelSnapshotV2Builder.build(source,"C","P")
  assertEquals(10L,stale.stats.single().exactValue);assertEquals(25L,rebuilt.stats.single().exactValue);assertNotEquals(stale.fingerprint,rebuilt.fingerprint)
  assertFalse(CharacterPanelSnapshotV2::class.java.declaredFields.any{it.name.contains("timestamp",true)||it.name.contains("updated",true)})
  assertFalse(CharacterPanelSnapshotV2::class.java.methods.any{it.name.startsWith("save",true)||it.name.startsWith("update",true)||it.name.startsWith("write",true)})
  assertEquals(setOf(RuntimeTruthLayer.DERIVED_PRESENTATION),RuntimeTruthLayerRegistry.requireFamily("CHARACTER_PANEL_SNAPSHOT_V2").layers)
 }

 @Test fun allSixPlayerSnapshotProfilesAreReadOnlyDiscardableProjections(){
  val source=MutableSource()
  assertEquals(setOf(PlayerSnapshotProfile.FULL,PlayerSnapshotProfile.COMBAT,PlayerSnapshotProfile.PROGRESSION,PlayerSnapshotProfile.ECONOMY,PlayerSnapshotProfile.SOCIAL,PlayerSnapshotProfile.GM_CONTEXT),PlayerSnapshotProfile.values().toSet())
  PlayerSnapshotProfile.values().forEach{profile->
   source.identityValue="before-$profile";val old=PlayerSnapshotBuilder.build(source,"C","P",profile)
   source.identityValue="after-$profile";val rebuilt=PlayerSnapshotBuilder.build(source,"C","P",profile)
   assertEquals(PlayerSnapshotClassification.DERIVED_PROJECTION,old.classification);assertEquals("before-$profile",old.panel.identity.single().value);assertEquals("after-$profile",rebuilt.panel.identity.single().value);assertNotEquals(old.fingerprint,rebuilt.fingerprint)
  }
  assertFalse(PlayerSnapshot::class.java.declaredFields.any{it.name.contains("timestamp",true)||it.name.contains("updated",true)})
  assertFalse(PlayerSnapshot::class.java.methods.any{it.name.startsWith("save",true)||it.name.startsWith("update",true)||it.name.startsWith("write",true)})
  assertEquals(setOf(RuntimeTruthLayer.DERIVED_PROJECTION),RuntimeTruthLayerRegistry.requireFamily("PLAYER_SNAPSHOT_PROFILES").layers)
 }

 @Test fun factBeliefNarrativeTypesSurviveProjectionWithoutPromotion(){
  val source=MutableSource();val gm=PlayerSnapshotBuilder.build(source,"C","P",PlayerSnapshotProfile.GM_CONTEXT)
  assertEquals(mapOf("F" to PlayerTruthClass.FACT,"B" to PlayerTruthClass.BELIEF,"N" to PlayerTruthClass.NARRATIVE),gm.truthViews.associate{it.truthUid to it.truthClass})
  PlayerSnapshotProfile.values().filter{it!=PlayerSnapshotProfile.GM_CONTEXT}.forEach{assertTrue(PlayerSnapshotBuilder.build(source,"C","P",it).truthViews.isEmpty())}
  source.identityValue="newer-presentation";val newer=PlayerSnapshotBuilder.build(source,"C","P",PlayerSnapshotProfile.GM_CONTEXT)
  assertEquals(gm.truthViews.map{it.truthClass},newer.truthViews.map{it.truthClass})
 }

 @Test fun derivedCachePresentationCapabilitiesCannotEscalateToAuthorityOrAdmin(){
  listOf(RuntimeMutationCapability.DERIVED_REBUILD,RuntimeMutationCapability.CACHE_REBUILD,RuntimeMutationCapability.PRESENTATION_ONLY).forEach{cap->
   listOf("CAMPAIGN_TRUTH","BASE_STATS_RESOURCES","INVENTORY","OWNERSHIP_HISTORY","FINANCE_AUTHORITY","DEVELOPMENT_PROJECTS").forEach{family->assertTrue("$cap unexpectedly wrote $family",runCatching{RuntimeTruthLayerRegistry.requireAuthoritativeMutation(family,cap)}.isFailure)}
   assertTrue(runCatching{RuntimeTruthLayerRegistry.requireGameplayCapability(cap)}.isFailure)
  }
 }
}
