package com.rpgos.app

/** Presentation only. A status message never establishes a result, elapsed time or knowledge.
 * Only an admitted owner result may say that work has completed. No raw rule/parameter text
 * is exposed here, including errors originating from institutions or private reports. */
internal object Phase64WorldMessages {
    fun explanation(reasons: List<String>): String? {
        val codes = reasons.flatMap { it.split('|') }.filter { it.startsWith("P64:") }
        if (codes.isEmpty()) return null
        return when {
            codes.any { "STALE" in it || "HISTORY" in it } ->
                "Stan kampanii zmienił się podczas przygotowania czynności. Spróbuj ponownie."
            codes.any { "CANCELLATION_DEADLINE_REACHED" in it } ->
                "Ten proces osiągnął już termin zakończenia. Nie można go anulować wstecz."
            codes.any { "CANCELLATION" in it } ->
                "Możesz przerwać tylko własny, nadal trwający proces. Wskaż, który chcesz anulować."
            codes.any { "CAMPAIGN_NOT_ENABLED" in it } ->
                "Ten zapis korzysta z wcześniejszych zasad świata. Nowe procesy są dostępne w nowych kampaniach."
            codes.any { "ROUTE" in it || "ORIGIN" in it } ->
                "Dostawa lub podróż wymaga dostępnej, znanej trasy. Sprawdź drogę i położenie uczestników."
            codes.any { "RESOURCE" in it || "LABOUR" in it || "SETTLEMENT" in it || "ACCOUNT" in it } ->
                "Brakuje dostępnych zasobów albo warunków rozliczenia. Sprawdź materiały, środki i uczestników."
            codes.any { "ACCESS" in it || "AUTHORITY" in it || "DISCLOSURE" in it || "SOURCE" in it } ->
                "Ta czynność wymaga odpowiedniego dostępu lub dostępnego źródła informacji."
            codes.any { "PROJECT" in it } ->
                "Projekt nie spełnia jeszcze warunków tego etapu. Sprawdź wymagania i dotychczasowy postęp."
            else -> "Nie są spełnione zarejestrowane warunki tej czynności. Doprecyzuj zamiar lub wybierz inną możliwość."
        }
    }

    fun notice(definition: BackgroundProcessDefinition, process: BackgroundProcessInstance): String {
        require(definition.uid == process.definitionUid && definition.version == process.definitionVersion)
        val label = when (definition.domain) {
            "PROJECT" -> "Projekt"
            "INFORMATION" -> "Przekazanie informacji"
            "ORGANIZATION" -> "Zadanie organizacji"
            "POPULATION" -> "Proces populacji"
            "CONFLICT" -> "Działanie formacji"
            "EPIDEMIC" -> "Działanie zdrowotne"
            else -> if (definition.operation == Phase64EconomyOperations.DELIVER) "Dostawa" else "Zadanie"
        }
        return when (process.status) {
            BackgroundProcessStatus.ACTIVE -> "$label trwa; jego skutki zostaną sprawdzone w terminie zakończenia."
            BackgroundProcessStatus.BLOCKED -> "$label jest wstrzymany. " +
                (explanation(listOfNotNull(process.reasonUid)) ?: "Wymagania nie są obecnie spełnione.")
            BackgroundProcessStatus.COMPLETED -> "$label: rozliczono zakończenie według reguł kampanii."
            BackgroundProcessStatus.INTERRUPTED -> "$label przerwano; przyszłe skutki nie zostały przyznane."
            BackgroundProcessStatus.FAILED -> "$label nie powiódł się."
        }
    }
}
