from pathlib import Path
p=Path('app/src/main/java/com/rpgos/app/GameMasterContextRepository141.kt')
s=p.read_text()
old='''            val repo = session.repository
            val turn = repo.currentTurnId(session.campaignUid)
            val events = repo.recentEvents(session.campaignUid, beforeOrAtTurn = turn, limit = 60)
            val memories = repo.memories(session.campaignUid, limit = 60)
            val divergences = repo.getActiveDivergences(session.campaignUid)

            val playerUid = resolvePlayerUid(legacy)
'''
new='''            val repo = session.repository
            val turn = repo.currentTurnId(session.campaignUid)
            val divergences = repo.getActiveDivergences(session.campaignUid)

            val playerUid = resolvePlayerUid(legacy)
'''
if s.count(old)!=1: raise SystemExit(f'initial retrieval block mismatch: {s.count(old)}')
s=s.replace(old,new,1)
old='''            val beliefs = mutableListOf<CampaignTruth>()
            relevantNpcUids.take(16).forEach { npcUid ->
                beliefs += repo.getBeliefs(
                    campaignUid = session.campaignUid,
                    holderUid = npcUid,
                    atTurnId = turn,
                    limit = 24
                )
            }

            val budget = request.contextBudget
'''
new='''            val retrieved = GameMasterRetriever141(repo, session.campaignUid).retrieve(
                playerAction = request.playerAction,
                atTurnId = turn,
                relevantNpcUids = relevantNpcUids,
                eventLimit = 36,
                memoryLimit = 36,
                beliefLimitPerNpc = 16
            )
            val events = retrieved.events
            val memories = retrieved.memories
            val beliefs = retrieved.beliefsByHolder.values.flatten()

            val budget = request.contextBudget
'''
if s.count(old)!=1: raise SystemExit(f'belief retrieval block mismatch: {s.count(old)}')
s=s.replace(old,new,1)
old='''                    put("npc_beliefs_gm141", truthsJson(beliefs))
'''
new='''                    put("npc_beliefs_gm141", truthsJson(beliefs))
                    put("npc_belief_holders_gm141", JSONArray(retrieved.beliefsByHolder.keys.map { it.value }))
'''
if s.count(old)!=1: raise SystemExit(f'belief json insertion mismatch: {s.count(old)}')
s=s.replace(old,new,1)
old='''                    ContextSource("GM141_MEMORY", session.campaignUid.value, "episodic and semantic campaign memory")
'''
new='''                    ContextSource("GM141_MEMORY", session.campaignUid.value, "bounded temporal episodic and semantic memory"),
                    ContextSource("GM141_RETRIEVER", session.campaignUid.value, "query-ranked temporal retrieval with holder-scoped beliefs")
'''
if s.count(old)!=1: raise SystemExit(f'provenance insertion mismatch: {s.count(old)}')
s=s.replace(old,new,1)
p.write_text(s)
