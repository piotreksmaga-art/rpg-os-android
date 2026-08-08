from pathlib import Path

vm = Path('app/src/main/java/com/rpgos/app/RpgOsViewModel.kt')
s = vm.read_text()

anchor = '''    fun clearDeveloperDiagnostics() {
        clearDiagnosticReport()
        _developerDiagnostic.value = ""
        _developerStatus.value = "Raport diagnostyczny wyczyszczony."
    }

'''
insert = '''    fun clearDeveloperDiagnostics() {
        clearDiagnosticReport()
        _developerDiagnostic.value = ""
        _developerStatus.value = "Raport diagnostyczny wyczyszczony."
    }

    fun loadGm141OfflineDiagnostics() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                _developerStatus.value = "GM141: audyt offline..."
                _developerDiagnostic.value = GameMasterDiagnosticsService141(app, store).report()
                _developerStatus.value = "✅ GM141: raport offline gotowy. Bez AI i bez zapisu tury."
            } catch (t: Throwable) {
                DiagnosticLogger.log(app, "GM141_OFFLINE_DIAGNOSTICS_FAILED", t)
                _developerStatus.value = "❌ GM141 diagnostyka: ${t::class.simpleName}: ${t.message}"
            }
        }
    }

    fun testGm141ProposalEndpoint() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                _developerStatus.value = "GM141: test /v1/gm/proposal bez zapisu..."
                GameMasterRepositoryFactory(app, store).openActiveSession().use { session ->
                    val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1L
                    val request = GameMasterTurnRequest(
                        campaignId = session.campaignUid.value,
                        worldPackId = session.worldPackUid.value,
                        playerAction = "DIAGNOSTIC_PROPOSAL_ONLY: zwróć minimalną bezpieczną propozycję testową bez zmian stanu.",
                        currentChapter = chapter,
                        locale = "pl-PL"
                    )
                    val context = GameMasterContextRepository141(app, store).buildContext(request)
                    val proposal = GameMasterBackendGateway141(_settings.value.backendUrl)
                        .generateProposal(request, context)
                    require(proposal.narrativeDraft.isNotBlank()) { "Backend zwrócił pustą narrację." }
                    _developerStatus.value =
                        "✅ GM141 proposal OK | akcje=${proposal.proposedActions.size}, pamięci=${proposal.proposedMemories.size}, kronika=${proposal.proposedChronicleEntries.size}. Nic nie zapisano."
                }
            } catch (t: Throwable) {
                DiagnosticLogger.log(app, "GM141_PROPOSAL_TEST_FAILED", t)
                _developerStatus.value = "❌ GM141 proposal: ${t::class.simpleName}: ${t.message}"
            }
        }
    }

'''
if s.count(anchor) != 1:
    raise SystemExit(f'VM diagnostics anchor mismatch: {s.count(anchor)}')
s = s.replace(anchor, insert, 1)
vm.write_text(s)

ui = Path('app/src/main/java/com/rpgos/app/MainActivity.kt')
s = ui.read_text()
anchor = '''            Button(onClick={vm::testBackendConnection},modifier=Modifier.fillMaxWidth()){
                Text("Test backendu")
            }
            Button(onClick={vm::createDeveloperBackup},modifier=Modifier.fillMaxWidth()){
                Text("Utwórz backup diagnostyczny")
            }
'''
insert = '''            Button(onClick={vm::testBackendConnection},modifier=Modifier.fillMaxWidth()){
                Text("Test backendu legacy")
            }
            Button(onClick={vm::loadGm141OfflineDiagnostics},modifier=Modifier.fillMaxWidth()){
                Text("Raport GM141 offline")
            }
            Button(onClick={vm::testGm141ProposalEndpoint},modifier=Modifier.fillMaxWidth()){
                Text("Test endpointu GM141 (bez zapisu)")
            }
            Button(onClick={vm::createDeveloperBackup},modifier=Modifier.fillMaxWidth()){
                Text("Utwórz backup diagnostyczny")
            }
'''
if s.count(anchor) != 1:
    raise SystemExit(f'UI diagnostics anchor mismatch: {s.count(anchor)}')
s = s.replace(anchor, insert, 1)
ui.write_text(s)
