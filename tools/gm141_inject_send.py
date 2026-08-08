from pathlib import Path

p = Path("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt")
text = p.read_text()
needle = '''            val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1

            try {
'''
if text.count(needle) != 1:
    raise SystemExit(f"expected one send() insertion point, found {text.count(needle)}")

injected = '''            val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1

            if (_settings.value.gm141Enabled) {
                try {
                    DiagnosticLogger.log(app, "GM141_SEND_START", message = "chapter=$chapter")
                    _messages.value = _messages.value + ChatMessage(
                        "system",
                        "GM141: budowanie kontrolowanego kontekstu i rozstrzyganie tury..."
                    )

                    _visualSuggestions.value = runCatching {
                        val preview = store.buildContext(text, chapter)
                        VisualSuggestionEngine().suggest(text, preview)
                    }.getOrElse {
                        DiagnosticLogger.log(app, "GM141_VISUAL_SUGGESTIONS_GUARDED", it)
                        emptyList()
                    }

                    val outcome = GameMasterChatBridge141(app, store).play(
                        playerAction = text,
                        chapter = chapter,
                        backendUrl = _settings.value.backendUrl
                    )

                    _lastContextSummary.value = outcome.contextSummary
                    _messages.value = _messages.value + ChatMessage("gm", outcome.narrative)

                    if (_settings.value.showGmDiagnostics && outcome.warnings.isNotEmpty()) {
                        _messages.value = _messages.value + ChatMessage(
                            "system",
                            "GM141 ostrzeżenia: ${outcome.warnings.joinToString("; ")}"
                        )
                    }

                    runCatching { refresh() }
                        .onFailure { DiagnosticLogger.log(app, "GM141_REFRESH_GUARDED", it) }

                    if (_settings.value.autoBackup) {
                        try {
                            val saveInfo = store.finalizeChapter(chapter, "Rozdział $chapter")
                            _messages.value = _messages.value + ChatMessage(
                                "system",
                                "Tura GM141 jest zapisana. Manifest=${saveInfo.first.take(12)}… Backup utworzony."
                            )
                        } catch (t: Throwable) {
                            DiagnosticLogger.log(app, "GM141_AUTOSAVE_GUARDED", t)
                            _messages.value = _messages.value + ChatMessage(
                                "system",
                                "Tura GM141 jest zapisana, ale dodatkowy backup nie powiódł się."
                            )
                        }
                    }

                    DiagnosticLogger.log(app, "GM141_SEND_COMPLETE")
                } catch (t: Throwable) {
                    DiagnosticLogger.log(app, "GM141_SEND_GUARDED", t)
                    _messages.value = _messages.value + ChatMessage(
                        "system",
                        "GM141 odrzucił lub nie zakończył tury: ${t::class.simpleName}: ${t.message ?: "brak szczegółów"}. Stary StatePatch nie został uruchomiony."
                    )
                    _lastContextSummary.value =
                        "GM141: tura niezatwierdzona — ${t::class.simpleName}: ${t.message ?: "brak szczegółów"}"
                }
                return@launch
            }

            try {
'''

p.write_text(text.replace(needle, injected, 1))
