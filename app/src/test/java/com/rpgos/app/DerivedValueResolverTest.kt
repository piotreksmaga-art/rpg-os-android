package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DerivedValueResolverTest {
    @Test fun baseOnlyAndLifecycleAddsProduce105WithoutBaseMutation() {
        val base = PlayerStat("C", "P", "S", 100.0)
        assertEquals(100.0, resolve(stats=listOf(base)).resolvedStats.single().effectiveValue, 0.0)
        val result = resolve(stats=listOf(base), modifiers=listOf(
            mod("T", ModifierLifecycle.TEMPORARY, ModifierOperation.ADD_FLAT, 5.0),
            mod("E", ModifierLifecycle.EQUIPMENT, ModifierOperation.ADD_FLAT, 20.0),
            mod("P", ModifierLifecycle.PERMANENT, ModifierOperation.ADD_FLAT, 10.0),
            mod("I", ModifierLifecycle.INJURY, ModifierOperation.ADD_FLAT, -30.0)
        )).resolvedStats.single()
        assertEquals(105.0, result.effectiveValue, 0.0)
        assertEquals(100.0, base.baseValue, 0.0)
        assertEquals(listOf("P","E","I","T"), result.contributions.map { it.modifierUid })
    }

    @Test fun operationAndLifecycleOrderingIsDeterministic() {
        val mixed = listOf(
            mod("CAP", operation=ModifierOperation.MAX_CAP, value=150.0),
            mod("MULT", operation=ModifierOperation.MULTIPLY, value=1.5),
            mod("P2", operation=ModifierOperation.ADD_PERCENT, value=.20),
            mod("ADD", operation=ModifierOperation.ADD_FLAT, value=20.0),
            mod("P1", operation=ModifierOperation.ADD_PERCENT, value=.10)
        )
        assertEquals(150.0, resolve(modifiers=mixed).resolvedStats.single().effectiveValue, 0.0)
        assertEquals(132.0, resolve(modifiers=listOf(mod("B",operation=ModifierOperation.MULTIPLY,value=1.2), mod("A",operation=ModifierOperation.MULTIPLY,value=1.1))).resolvedStats.single().effectiveValue, 1e-9)
        assertEquals(210.0, resolve(modifiers=listOf(mod("P",ModifierLifecycle.PERMANENT,ModifierOperation.MULTIPLY,2.0), mod("E",ModifierLifecycle.EQUIPMENT,ModifierOperation.ADD_FLAT,10.0))).resolvedStats.single().effectiveValue, 0.0)
    }

    @Test fun overridePriorityUidFloorCapAndDefinitionBoundsAreFrozen() {
        val override = resolve(modifiers=listOf(
            mod("A",operation=ModifierOperation.OVERRIDE,value=80.0,priority=10),
            mod("B",operation=ModifierOperation.OVERRIDE,value=120.0,priority=20)
        )).resolvedStats.single()
        assertEquals(120.0, override.effectiveValue, 0.0)
        val tie = resolve(modifiers=listOf(mod("A",operation=ModifierOperation.OVERRIDE,value=80.0,priority=10), mod("B",operation=ModifierOperation.OVERRIDE,value=90.0,priority=10))).resolvedStats.single()
        assertEquals(90.0, tie.effectiveValue, 0.0)
        val bounded = resolve(definition=statDef(min=0.0,max=200.0), modifiers=listOf(mod("ADD",value=150.0))).resolvedStats.single()
        assertEquals(250.0, bounded.preCapValue, 0.0)
        assertEquals(200.0, bounded.effectiveValue, 0.0)
        assertTrue(bounded.diagnostics.any { it.code=="STAT_DEFINITION_BOUND_APPLIED" })
        assertEquals(0.0, resolve(stats=listOf(PlayerStat("C","P","S",-20.0)), modifiers=listOf(mod("F",operation=ModifierOperation.MIN_FLOOR,value=0.0))).resolvedStats.single().effectiveValue, 0.0)
    }

    @Test fun inactiveSourceInactiveExpiredFutureIgnoredAndTimeBoundaryInclusive() {
        val result = resolve(epoch=100, modifiers=listOf(
            mod("INACTIVE",value=100.0,active=false),
            mod("SOURCE",value=100.0,sourceActive=false),
            mod("EXPIRED",value=100.0,validUntil=99),
            mod("FUTURE",value=100.0,validFrom=101),
            mod("NOW",value=5.0,validFrom=100,validUntil=100)
        ))
        assertEquals(105.0, result.resolvedStats.single().effectiveValue, 0.0)
        assertEquals(4, result.diagnostics.count { it.code.startsWith("MODIFIER_") })
        val sourceOn = resolve(epoch=100, modifiers=listOf(mod("SOURCE",value=100.0,sourceActive=true)))
        assertNotEquals(result.inputFingerprint, sourceOn.inputFingerprint)
    }

    @Test fun removalRepeatedResolveAndTemporaryExpiryNeverRegressBase() {
        val base = PlayerStat("C","P","S",100.0)
        val resolver = DerivedValueResolver()
        val injury = request(stats=listOf(base), modifiers=listOf(mod("I",ModifierLifecycle.INJURY,value=-40.0)))
        repeat(100) { assertEquals(60.0, resolver.resolve(injury).resolvedStats.single().effectiveValue, 0.0) }
        assertEquals(100.0, base.baseValue, 0.0)
        assertEquals(100.0, resolver.resolve(request(stats=listOf(base))).resolvedStats.single().effectiveValue, 0.0)
        val equipment = resolve(stats=listOf(base),modifiers=listOf(mod("E",ModifierLifecycle.EQUIPMENT,value=25.0)))
        assertEquals(125.0,equipment.resolvedStats.single().effectiveValue,0.0)
        val expired = resolve(stats=listOf(base),epoch=200,modifiers=listOf(mod("T",ModifierLifecycle.TEMPORARY,value=10.0,validUntil=150)))
        assertEquals(100.0,expired.resolvedStats.single().effectiveValue,0.0)
    }

    @Test fun duplicateUnknownTargetCrossCampaignAndCrossPlayerFailLoud() {
        expectFailure { resolve(modifiers=listOf(mod("D"),mod("D"))) }
        expectFailure { resolve(modifiers=listOf(mod("MISSING",targetUid="NOPE"))) }
        expectFailure { resolve(modifiers=listOf(mod("X").copy(campaignId="OTHER"))) }
        expectFailure { resolve(modifiers=listOf(mod("X").copy(characterUid="OTHER"))) }
        expectFailure { Modifier("BAD","C","P","S",ModifierTargetKind.STAT_EFFECTIVE,ModifierLifecycle.PERMANENT,ModifierOperation.ADD_FLAT,1.0,sourceType="X",sourceUid="Y",validFrom=20,validUntil=10,provenance="x") }
    }

    @Test fun replayPermutationAndThousandModifiersAreStable() {
        val mods=listOf(mod("Z",operation=ModifierOperation.MULTIPLY,value=1.2),mod("A",value=20.0),mod("M",operation=ModifierOperation.ADD_PERCENT,value=.1))
        val canonical=resolve(modifiers=mods)
        assertEquals(canonical,resolve(modifiers=mods.reversed()))
        repeat(100) { assertEquals(canonical,resolve(modifiers=mods.shuffled(java.util.Random(it.toLong())))) }
        val thousand=(0 until 1005).map { mod("M%04d".format(it),value=1.0) }
        val large=resolve(stats=listOf(PlayerStat("C","P","S",0.0)),modifiers=thousand).resolvedStats.single()
        assertEquals(1005.0,large.effectiveValue,0.0)
        assertEquals(1005,large.contributions.size)
    }

    @Test fun finiteGuardsAndNegativeZeroCanonicalizationWork() {
        expectFailure { mod("N",value=Double.NaN) }
        expectFailure { resolve(stats=listOf(PlayerStat("C","P","S",Double.MAX_VALUE)),modifiers=listOf(mod("X",operation=ModifierOperation.MULTIPLY,value=2.0))) }
        val zero=resolve(stats=listOf(PlayerStat("C","P","S",0.0)),modifiers=listOf(mod("Z",value=-0.0))).resolvedStats.single().effectiveValue
        assertEquals(java.lang.Double.doubleToRawLongBits(0.0),java.lang.Double.doubleToRawLongBits(zero))
    }

    @Test fun resourceMaximumRegenerationAndOvercapArePureDerivedOutputs() {
        val dep=DerivedDependency(ModifierTargetKind.STAT_EFFECTIVE,"S")
        val provider=TestProvider(mapOf(
            "MAX" to DerivedRuleDescriptor("MAX",1,listOf(dep)),
            "REGEN" to DerivedRuleDescriptor("REGEN",2)
        ), mapOf("MAX" to { c -> c.dependencyValues.getValue(dep)*10.0 }, "REGEN" to { _ -> 3.5 }))
        val rdef=ResourceDefinition("R","flux","resource",maxRuleUid="MAX",regenerationRuleUid="REGEN",worldPackUid="W")
        val current=PlayerResource("C","P","R",150.0)
        val result=DerivedValueResolver(provider).resolve(request(resourceDefinitions=listOf(rdef),resources=listOf(current),ruleVersions=mapOf("MAX" to 1L,"REGEN" to 2L))).resolvedResources.single()
        assertEquals(150.0,result.currentValueObserved,0.0)
        assertEquals(1000.0,result.maximumValue!!,0.0)
        assertEquals(3.5,result.regenerationRate!!,0.0)
        assertEquals(150.0,current.currentValue,0.0)

        val overProvider=TestProvider(mapOf("MAX" to DerivedRuleDescriptor("MAX",1)),mapOf("MAX" to { _ -> 100.0 }))
        val over=DerivedValueResolver(overProvider).resolve(request(resourceDefinitions=listOf(rdef.copy(regenerationRuleUid=null)),resources=listOf(current),ruleVersions=mapOf("MAX" to 1L))).resolvedResources.single()
        assertEquals(150.0,over.currentValueObserved,0.0)
        assertTrue(over.diagnostics.any { it.code=="RESOURCE_CURRENT_ABOVE_DERIVED_MAX" })
    }

    @Test fun resourceModifiersOnlyChangeMaximumAndRegeneration() {
        val provider=TestProvider(mapOf("MAX" to DerivedRuleDescriptor("MAX",1),"REGEN" to DerivedRuleDescriptor("REGEN",1)),mapOf("MAX" to { _ -> 100.0 },"REGEN" to { _ -> 2.0 }))
        val def=ResourceDefinition("R","flux","resource",maxRuleUid="MAX",regenerationRuleUid="REGEN",worldPackUid="W")
        val req=request(resourceDefinitions=listOf(def),resources=listOf(PlayerResource("C","P","R",40.0)),modifiers=listOf(
            mod("RM",targetKind=ModifierTargetKind.RESOURCE_MAXIMUM,targetUid="R",value=20.0),
            mod("RR",targetKind=ModifierTargetKind.RESOURCE_REGENERATION,targetUid="R",operation=ModifierOperation.MULTIPLY,value=1.5)
        ),ruleVersions=mapOf("MAX" to 1L,"REGEN" to 1L))
        val result=DerivedValueResolver(provider).resolve(req).resolvedResources.single()
        assertEquals(120.0,result.maximumValue!!,0.0)
        assertEquals(3.0,result.regenerationRate!!,0.0)
        assertEquals(40.0,result.currentValueObserved,0.0)
    }

    @Test fun missingRulesVersionMismatchAndCyclesFailDeterministically() {
        expectFailure { DerivedValueResolver(TestProvider(emptyMap(),emptyMap())).resolve(request(definitions=listOf(statDef(derivation="MISS")),stats=emptyList(),ruleVersions=mapOf("MISS" to 1L))) }
        val mismatch=TestProvider(mapOf("R" to DerivedRuleDescriptor("R",2)),mapOf("R" to { _ -> 1.0 }))
        expectFailure { DerivedValueResolver(mismatch).resolve(request(definitions=listOf(statDef(derivation="R")),stats=emptyList(),ruleVersions=mapOf("R" to 1L))) }
        val a=DerivedDependency(ModifierTargetKind.STAT_EFFECTIVE,"B")
        val b=DerivedDependency(ModifierTargetKind.STAT_EFFECTIVE,"A")
        val cycle=TestProvider(mapOf("RA" to DerivedRuleDescriptor("RA",1,listOf(a)),"RB" to DerivedRuleDescriptor("RB",1,listOf(b))),mapOf("RA" to { c -> c.dependencyValues.getValue(a) },"RB" to { c -> c.dependencyValues.getValue(b) }))
        expectFailure { DerivedValueResolver(cycle).resolve(request(definitions=listOf(statDef("A","a","RA"),statDef("B","b","RB")),stats=emptyList(),ruleVersions=mapOf("RA" to 1L,"RB" to 1L))) }
    }

    @Test fun typedSameKeyWorldPacksStayDistinctAndAliasMetadataAffectsFingerprint() {
        val defs=listOf(statDef("A","same",worldPack="W1"),statDef("B","same",worldPack="W2"))
        val result=DerivedValueResolver().resolve(request(definitions=defs,stats=listOf(PlayerStat("C","P","A",10.0),PlayerStat("C","P","B",20.0))))
        assertEquals(listOf("A","B"),result.resolvedStats.map { it.statUid })
        val alias=LegacyStatAlias("C",LegacyCompatibilityIdentity.statUidForKey("strength"),"S","W",1,"migration")
        val one=resolve(aliases=listOf(alias))
        val two=resolve(aliases=listOf(alias.copy(mappingVersion=2)))
        assertEquals("S",one.resolvedStats.single().statUid)
        assertNotEquals(one.inputFingerprint,two.inputFingerprint)
    }

    private fun resolve(definition:StatDefinition=statDef(),definitions:List<StatDefinition> = listOf(definition),stats:List<PlayerStat> = listOf(PlayerStat("C","P","S",100.0)),modifiers:List<Modifier> = emptyList(),epoch:Long=100,aliases:List<LegacyStatAlias> = emptyList())=
        DerivedValueResolver().resolve(request(definitions=definitions,stats=stats,modifiers=modifiers,epoch=epoch,aliases=aliases))

    private fun request(definitions:List<StatDefinition> = listOf(statDef()),stats:List<PlayerStat> = listOf(PlayerStat("C","P","S",100.0)),resourceDefinitions:List<ResourceDefinition> = emptyList(),resources:List<PlayerResource> = emptyList(),modifiers:List<Modifier> = emptyList(),epoch:Long=100,ruleVersions:Map<String,Long> = emptyMap(),aliases:List<LegacyStatAlias> = emptyList())=
        DerivedResolutionRequest("C","P",epoch,definitions,resourceDefinitions,stats,resources,modifiers,ruleVersions,aliases)

    private fun statDef(uid:String="S",key:String="stat",derivation:String?=null,min:Double?=null,max:Double?=null,worldPack:String="W")=StatDefinition(uid,key,"generic",minValue=min,maxValue=max,derivationRuleUid=derivation,worldPackUid=worldPack)

    private fun mod(uid:String,lifecycle:ModifierLifecycle=ModifierLifecycle.PERMANENT,operation:ModifierOperation=ModifierOperation.ADD_FLAT,value:Double=1.0,priority:Int=0,active:Boolean=true,sourceActive:Boolean=true,validFrom:Long?=null,validUntil:Long?=null,targetKind:ModifierTargetKind=ModifierTargetKind.STAT_EFFECTIVE,targetUid:String="S")=Modifier(
        modifierUid=uid,campaignId="C",characterUid="P",targetDefinitionUid=targetUid,targetKind=targetKind,lifecycle=lifecycle,operation=operation,value=value,priority=priority,sourceType="TEST",sourceUid="SRC-$uid",sourceActive=sourceActive,validFrom=validFrom,validUntil=validUntil,active=active,provenance="test"
    )

    private class TestProvider(private val descriptors:Map<String,DerivedRuleDescriptor>,private val evaluators:Map<String,(DerivedRuleContext)->Double>):DerivedRuleProvider {
        override val providerUid="TEST-PROVIDER"
        override fun descriptor(ruleUid:String)=descriptors[ruleUid]
        override fun evaluate(descriptor:DerivedRuleDescriptor,context:DerivedRuleContext)=evaluators[descriptor.ruleUid]?.invoke(context) ?: error("Missing evaluator ${descriptor.ruleUid}")
    }
    private fun expectFailure(block:()->Unit){try{block();fail("Expected failure")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
}
