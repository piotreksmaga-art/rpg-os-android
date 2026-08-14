from pathlib import Path

root = Path('.')

def replace(path, old, new):
    p = root / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f'expected block not found in {path}')
    p.write_text(text.replace(old, new, 1))

engine = 'app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt'
replace(engine,
'''    val entropy: ResolutionEntropyEvidence,
    val worldPackBinding: WorldPackRuleBinding?
) {''',
'''    val entropy: ResolutionEntropyEvidence,
    val worldRuleMode: WorldRuleMode
) {''')

replace(engine,
'''    internal fun deterministicFingerprint(): String = sha256(
        buildString {
            appendToken(campaignUid)
            appendToken(actor.actorKindUid)
            appendToken(actor.actorUid)
            knownReferences
                .sortedWith(compareBy({ it.campaignUid }, { it.ref.kindUid }, { it.ref.uid }))
                .forEach {
                    appendToken(it.campaignUid)
                    appendToken(it.ref.kindUid)
                    appendToken(it.ref.uid)
                }
            dependencyVersions.forEach { (key, value) ->
                appendToken(key)
                appendToken(value)
            }
            appendToken(entropy.evidenceUid)
            appendToken(entropy.exactValue.toString())
            appendToken(worldPackBinding?.worldPackUid ?: "RPGOS-WORLD-PACK:NONE")
            appendToken(worldPackBinding?.worldPackVersion ?: "RPGOS-WORLD-PACK-VERSION:NONE")
        }
    )

    companion object {
        fun create(
            campaignUid: String,
            actor: CommandActorRef,
            knownReferences: Set<CampaignScopedDomainRef>,
            dependencyVersions: Map<String, String> = emptyMap(),
            entropy: ResolutionEntropyEvidence = ResolutionEntropyEvidence.none(),
            worldPackBinding: WorldPackRuleBinding? = null
        ): PlayerResolutionContext = PlayerResolutionContext(
            campaignUid,
            actor,
            LinkedHashSet(knownReferences),
            TreeMap(dependencyVersions),
            entropy,
            worldPackBinding
        )
    }''',
'''    internal fun deterministicFingerprint(): String = WorldRuleCanonicalWriter.fingerprint("PLAYER_RESOLUTION_CONTEXT") {
        field("CONTEXT_VERSION", "1")
        field("CAMPAIGN_UID", campaignUid)
        section("ACTOR") {
            field("KIND_UID", actor.actorKindUid)
            field("UID", actor.actorUid)
        }
        val refs = knownReferences.sortedWith(compareBy({ it.campaignUid }, { it.ref.kindUid }, { it.ref.uid }))
        list("KNOWN_REFERENCES", refs) { scoped ->
            record("CAMPAIGN_SCOPED_DOMAIN_REF") {
                field("CAMPAIGN_UID", scoped.campaignUid)
                field("KIND_UID", scoped.ref.kindUid)
                field("UID", scoped.ref.uid)
            }
        }
        val dependencies = dependencyVersions.entries.toList()
        list("DEPENDENCY_VERSIONS", dependencies) { entry ->
            record("DEPENDENCY_VERSION") {
                field("KEY", entry.key)
                field("VALUE", entry.value)
            }
        }
        section("ENTROPY") {
            field("EVIDENCE_UID", entropy.evidenceUid)
            longField("EXACT_VALUE", entropy.exactValue)
        }
        section("WORLD_RULE_MODE") {
            when (val mode = worldRuleMode) {
                is WorldRuleMode.Bound -> {
                    field("MODE", "BOUND")
                    field("WORLD_PACK_UID", mode.binding.worldPackUid)
                    field("WORLD_PACK_VERSION", mode.binding.worldPackVersion)
                }
                WorldRuleMode.UnboundGeneric -> field("MODE", "UNBOUND_GENERIC")
            }
        }
    }

    companion object {
        fun create(
            campaignUid: String,
            actor: CommandActorRef,
            knownReferences: Set<CampaignScopedDomainRef>,
            dependencyVersions: Map<String, String> = emptyMap(),
            entropy: ResolutionEntropyEvidence = ResolutionEntropyEvidence.none(),
            worldRuleMode: WorldRuleMode
        ): PlayerResolutionContext = PlayerResolutionContext(
            campaignUid,
            actor,
            LinkedHashSet(knownReferences),
            TreeMap(dependencyVersions),
            entropy,
            worldRuleMode
        )

        fun createUnboundGeneric(
            campaignUid: String,
            actor: CommandActorRef,
            knownReferences: Set<CampaignScopedDomainRef>,
            dependencyVersions: Map<String, String> = emptyMap(),
            entropy: ResolutionEntropyEvidence = ResolutionEntropyEvidence.none()
        ): PlayerResolutionContext = create(
            campaignUid,
            actor,
            knownReferences,
            dependencyVersions,
            entropy,
            WorldRuleMode.UnboundGeneric
        )
    }''')

replace(engine,
'''        val binding = context.worldPackBinding ?: return null
        val provider = worldRuleRegistry.providerFor(binding)''',
'''        val binding = when (val mode = context.worldRuleMode) {
            is WorldRuleMode.Bound -> mode.binding
            WorldRuleMode.UnboundGeneric -> return null
        }
        val provider = worldRuleRegistry.providerFor(binding)''')

selection = 'app/src/main/java/com/rpgos/app/CampaignSelectionManager.kt'
replace(selection,
'''    fun activeWorldPackDirName(): String =
        prefs.getString("active_worldpack", "Naruto.worldpack") ?: "Naruto.worldpack"
''',
'''    fun activeWorldPackDirName(): String =
        prefs.getString("active_worldpack", "Naruto.worldpack") ?: "Naruto.worldpack"

    /** Canonical app-level authority for the active World Pack rule mode. */
    fun activeWorldRuleMode(): WorldRuleMode.Bound {
        val dir = File(worldpacks, activeWorldPackDirName())
        val validation = PackageValidator().validateWorldPack(dir)
        require(validation.ok) { "Active World Pack is invalid: ${validation.message}" }
        val uid = validation.packageId?.takeIf { it.isNotBlank() }
            ?: error("Active World Pack manifest has no id")
        val version = validation.version?.takeIf { it.isNotBlank() }
            ?: error("Active World Pack manifest has no version")
        return WorldRuleMode.Bound(WorldPackRuleBinding(uid, version))
    }
''')

phase18 = 'app/src/test/java/com/rpgos/app/PlayerDomainEngineTest.kt'
p = root / phase18
text = p.read_text()
text = text.replace('PlayerResolutionContext.create("C1",CommandActorRef("PLAYER","P2"),baseRefs())',
                    'PlayerResolutionContext.createUnboundGeneric("C1",CommandActorRef("PLAYER","P2"),baseRefs())')
text = text.replace('PlayerResolutionContext.create(campaignUid,actor,baseRefs()+extraRefs,mapOf("RPGOS-DEPENDENCY:REFERENCE-SNAPSHOT" to "1"),entropy)',
                    'PlayerResolutionContext.createUnboundGeneric(campaignUid,actor,baseRefs()+extraRefs,mapOf("RPGOS-DEPENDENCY:REFERENCE-SNAPSHOT" to "1"),entropy)')
p.write_text(text)

phase19 = 'app/src/test/java/com/rpgos/app/WorldRuleProviderPhase19Test.kt'
p = root / phase19
text = p.read_text()
text = text.replace('''        ResolutionEntropyEvidence.none(),
        if (worldRules) binding else null
    )''', '''        ResolutionEntropyEvidence.none(),
        if (worldRules) WorldRuleMode.Bound(binding) else WorldRuleMode.UnboundGeneric
    )''')
text = text.replace('''        ),
        worldPackBinding = binding
    )''', '''        ),
        worldRuleMode = WorldRuleMode.Bound(binding)
    )''')
p.write_text(text)

repro = 'app/src/test/java/com/rpgos/app/WorldRuleProviderPhase19BlockerReproductionTest.kt'
p = root / repro
text = p.read_text()
text = text.replace('context(worldPackBinding = null)', 'context(WorldRuleMode.Bound(binding))')
text = text.replace('private fun context(worldPackBinding: WorldPackRuleBinding?) = PlayerResolutionContext.create(',
                    'private fun context(worldRuleMode: WorldRuleMode) = PlayerResolutionContext.create(')
text = text.replace('''        ),
        worldPackBinding = worldPackBinding
    )''', '''        ),
        worldRuleMode = worldRuleMode
    )''')
text = text.replace('val ctx = context(binding)', 'val ctx = context(WorldRuleMode.Bound(binding))')
text = text.replace('''            emptyMap(), ResolutionEntropyEvidence.none(), binding
        )''', '''            emptyMap(), ResolutionEntropyEvidence.none(), WorldRuleMode.Bound(binding)
        )''')
text = text.replace('''            ResolutionEntropyEvidence.none(), binding
        )''', '''            ResolutionEntropyEvidence.none(), WorldRuleMode.Bound(binding)
        )''')
p.write_text(text)

print('Phase 19 contract patch applied')
