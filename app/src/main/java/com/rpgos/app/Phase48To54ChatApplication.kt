package com.rpgos.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ChatApplicationOutcome {
    data class Narrated(val result: ChatTurnResult.Narrated) : ChatApplicationOutcome
    data class CommittedNarrationPending(
        val result: ChatTurnResult.CommittedWithoutNarrative,
        val recovery: ChatNarrationRecoveryToken
    ) : ChatApplicationOutcome
    data class Clarification(val reasonUids: List<String>) : ChatApplicationOutcome
    data class Rejected(val stage: AiTurnStage, val reasonUids: List<String>) : ChatApplicationOutcome
    data class Failed(val stage: AiTurnStage, val reasonUid: String, val mutationState: TurnMutationState) : ChatApplicationOutcome
    data class Cancelled(val stage: AiTurnStage, val mutationState: TurnMutationState) : ChatApplicationOutcome

    /**
     * Temporary read-only compatibility outcome. It keeps the old alpha chat usable without
     * accepting its StatePatch as authority. It is deliberately not Phase48-54 completion evidence.
     */
    data class NonAuthoritativeNarration(val text: String, val reasonUid: String) : ChatApplicationOutcome
}

data class ChatNarrationRecoveryToken(val request: ChatTurnRequest) {
    init { require(request.requestUid.isNotBlank()) }
}

interface ChatApplicationPort {
    suspend fun play(input: String, cancellation: AiCancellationSignal = AiCancellationSignal.NONE): ChatApplicationOutcome
    suspend fun recover(token: ChatNarrationRecoveryToken, cancellation: AiCancellationSignal = AiCancellationSignal.NONE): NarrativeRecoveryResult
}

fun interface ChatTurnRequestFactory {
    fun create(input: String): ChatTurnRequest
}

/** The only application adapter allowed to invoke the canonical Phase43-54 facade. */
class CanonicalChatApplication(
    private val engine: AiChatEngineFacade,
    private val requests: ChatTurnRequestFactory
) : ChatApplicationPort {
    override suspend fun play(input: String, cancellation: AiCancellationSignal): ChatApplicationOutcome = withContext(Dispatchers.IO) {
        val request = requests.create(input)
        when (val result = engine.play(request, cancellation)) {
            is ChatTurnResult.Narrated -> ChatApplicationOutcome.Narrated(result)
            is ChatTurnResult.CommittedWithoutNarrative -> ChatApplicationOutcome.CommittedNarrationPending(
                result,
                ChatNarrationRecoveryToken(request)
            )
            is ChatTurnResult.Rejected -> {
                val clarification = result.reasonUids.any { reason ->
                    reason.contains("CLARIFICATION", ignoreCase = true) ||
                        reason.contains("AMBIGU", ignoreCase = true) ||
                        reason.contains("UNRESOLVED", ignoreCase = true)
                }
                if (clarification) ChatApplicationOutcome.Clarification(result.reasonUids)
                else ChatApplicationOutcome.Rejected(result.stage, result.reasonUids)
            }
            is ChatTurnResult.Failed -> ChatApplicationOutcome.Failed(result.stage, result.reasonUid, result.mutationState)
            is ChatTurnResult.Cancelled -> ChatApplicationOutcome.Cancelled(result.stage, result.mutationState)
        }
    }

    override suspend fun recover(token: ChatNarrationRecoveryToken, cancellation: AiCancellationSignal): NarrativeRecoveryResult =
        withContext(Dispatchers.IO) { engine.recoverNarration(token.request, cancellation) }
}

/**
 * Quarantines the pre-Phase48 backend behind a narration-only application boundary. Any returned
 * StatePatch is discarded before it can reach LocalGameStore. Canonical state therefore remains
 * unchanged until a real domain owner is available through CanonicalChatApplication.
 */
class NonAuthoritativeLegacyNarrationApplication(
    private val repository: CampaignRepository,
    private val backendUrl: () -> String,
    private val audience: () -> AudienceContext,
    private val purpose: () -> PurposeContext,
    private val chapter: () -> Int
) : ChatApplicationPort {
    override suspend fun play(input: String, cancellation: AiCancellationSignal): ChatApplicationOutcome {
        if (cancellation.isCancelled()) return ChatApplicationOutcome.Cancelled(AiTurnStage.INTERPRETATION, TurnMutationState.NOT_STARTED)
        val nextChapter = chapter()
        val context = withContext(Dispatchers.IO) { repository.buildContext(input, nextChapter, audience(), purpose()) }
        if (cancellation.isCancelled()) return ChatApplicationOutcome.Cancelled(AiTurnStage.CONTEXT, TurnMutationState.NOT_STARTED)
        val result = try {
            BackendClient(backendUrl()).sendTurn(input, nextChapter, context)
        } catch (_: Throwable) {
            SafeDemoGameMaster().respond(input, context, nextChapter)
        }
        if (cancellation.isCancelled()) return ChatApplicationOutcome.Cancelled(AiTurnStage.PROPOSAL, TurnMutationState.NOT_STARTED)
        return ChatApplicationOutcome.NonAuthoritativeNarration(
            result.narration,
            if (result.patch == null) "LEGACY_NARRATION_ONLY" else "LEGACY_STATE_PATCH_DISCARDED"
        )
    }

    override suspend fun recover(token: ChatNarrationRecoveryToken, cancellation: AiCancellationSignal): NarrativeRecoveryResult =
        NarrativeRecoveryResult.Unavailable("NO_CANONICAL_COMMIT_RECEIPT")
}
