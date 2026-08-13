package com.rpgos.app

import java.util.Collections
import kotlin.reflect.KClass

class PlayerDomainEngineStructuralException(val code: String) : IllegalArgumentException(code)

interface PlayerCommandResolver<P : PlayerCommandPayload> {
    val commandKindUid: String
    val payloadType: KClass<P>

    fun resolve(command: PlayerCommand<P>): PlayerChangeSet
}

class PlayerCommandResolverRegistry private constructor(
    resolvers: List<PlayerCommandResolver<out PlayerCommandPayload>>
) {
    private val byKind: Map<String, PlayerCommandResolver<out PlayerCommandPayload>>
    val commandKindUids: Set<String>

    init {
        val mutable = LinkedHashMap<String, PlayerCommandResolver<out PlayerCommandPayload>>()
        resolvers.forEach { resolver ->
            if (resolver.commandKindUid.isBlank()) fail("EMPTY_COMMAND_RESOLVER_KIND")
            if (mutable.put(resolver.commandKindUid, resolver) != null) fail("DUPLICATE_COMMAND_RESOLVER")
        }
        byKind = Collections.unmodifiableMap(LinkedHashMap(mutable))
        commandKindUids = Collections.unmodifiableSet(LinkedHashSet(mutable.keys))
    }

    fun resolverFor(commandKindUid: String): PlayerCommandResolver<out PlayerCommandPayload>? = byKind[commandKindUid]

    companion object {
        fun of(resolvers: List<PlayerCommandResolver<out PlayerCommandPayload>>): PlayerCommandResolverRegistry =
            PlayerCommandResolverRegistry(ArrayList(resolvers))

        fun empty(): PlayerCommandResolverRegistry = PlayerCommandResolverRegistry(emptyList())
    }

    private fun fail(code: String): Nothing = throw PlayerDomainEngineStructuralException(code)
}

class PlayerDomainEngine(
    private val resolverRegistry: PlayerCommandResolverRegistry,
    private val commandRegistry: PlayerCommandKindRegistry = PlayerCommandKindRegistry.core(),
    private val changeRegistry: TypedPlayerChangeRegistry = TypedPlayerChangeRegistry.core()
) {
    fun resolve(command: PlayerCommand<out PlayerCommandPayload>): PlayerChangeSet {
        commandRegistry.validate(command)

        // Resolver code receives a detached canonical command, never the caller-owned instance.
        val canonicalCommand = commandRegistry.decode(commandRegistry.encode(command))
        val commandFingerprint = commandRegistry.fingerprint(canonicalCommand)
        val resolver = resolverRegistry.resolverFor(canonicalCommand.commandKindUid)
            ?: fail("UNKNOWN_COMMAND_RESOLVER")

        val proposal = resolveTyped(resolver, canonicalCommand)

        // A resolver is not allowed to mutate even its detached command while resolving.
        if (commandRegistry.fingerprint(canonicalCommand) != commandFingerprint) fail("COMMAND_MUTATED_DURING_RESOLUTION")

        validateCommandProposalLink(canonicalCommand, proposal)
        PlayerChangeSetValidator.validate(proposal, changeRegistry)
        return proposal
    }

    private fun validateCommandProposalLink(
        command: PlayerCommand<out PlayerCommandPayload>,
        proposal: PlayerChangeSet
    ) {
        if (proposal.campaignUid != command.campaignUid) fail("CHANGESET_CAMPAIGN_MISMATCH")
        if (proposal.sourceCommandUid != command.commandUid) fail("CHANGESET_SOURCE_COMMAND_MISMATCH")
        if (proposal.actor != command.actor) fail("CHANGESET_ACTOR_MISMATCH")
        if (proposal.causationUid != command.causationUid) fail("CHANGESET_CAUSATION_MISMATCH")
        if (proposal.correlationUid != command.correlationUid) fail("CHANGESET_CORRELATION_MISMATCH")
        if (proposal.requestedEffectiveOrder != command.requestedEffectiveOrder) fail("CHANGESET_REQUESTED_ORDER_MISMATCH")

        val requiredPreconditions = command.preconditions.map(::toChangeSetPrecondition)
        if (!proposal.preconditions.containsAll(requiredPreconditions)) fail("CHANGESET_PRECONDITION_MISMATCH")
    }

    private fun fail(code: String): Nothing = throw PlayerDomainEngineStructuralException(code)
}

private fun toChangeSetPrecondition(precondition: CommandPrecondition): ChangeSetPrecondition = when (precondition) {
    is ExpectedRecordVersion -> ChangeSetExpectedRecordVersion(precondition.target, precondition.expectedVersion)
    is ExpectedLifecycleState -> ChangeSetExpectedLifecycleState(precondition.target, precondition.expectedStateUid)
}

private fun resolveTyped(
    resolver: PlayerCommandResolver<out PlayerCommandPayload>,
    command: PlayerCommand<out PlayerCommandPayload>
): PlayerChangeSet {
    if (!resolver.payloadType.isInstance(command.payload)) {
        throw PlayerDomainEngineStructuralException("COMMAND_RESOLVER_PAYLOAD_TYPE_MISMATCH")
    }
    @Suppress("UNCHECKED_CAST")
    val typedResolver = resolver as PlayerCommandResolver<PlayerCommandPayload>
    @Suppress("UNCHECKED_CAST")
    val typedCommand = command as PlayerCommand<PlayerCommandPayload>
    return typedResolver.resolve(typedCommand)
}
