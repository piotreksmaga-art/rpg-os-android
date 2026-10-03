package com.rpgos.app

/** Diagnostic codes stay available to LAB; ordinary players get an actionable explanation. */
internal object Phase63WorldMessages {
    fun explanation(reasons:List<String>):String? {
        val reason=reasons.joinToString("|")
        return when {
            "WORLD_RULE_SOURCE_UNAVAILABLE" in reason ->
                "Nie można odczytać reguł tej kampanii. Sprawdź jej pakiet lub przywróć poprawny zapis. Nie uruchomiono modelu ani nie zmieniono świata."
            "WORLD_DRAFT_REQUIRES_CLARIFICATION" in reason ->
                "Doprecyzuj, jakiego miejsca, osoby lub przedmiotu szukasz. Bez tej informacji nie utworzono zastępczego elementu ani nie wykonano czynności."
            "SET_SELECTION_REQUIRES_CLARIFICATION" in reason ->
                "Doprecyzuj, które osoby lub obiekty wybierasz i czy czynność dotyczy ich razem. Nie wykonano jej na pojedynczym celu zamiast wskazanej grupy."
            "STALE_RESOLUTION_SCOPE" in reason ->
                "Stan kampanii zmienił się podczas przygotowania tej czynności. Spróbuj ponownie; nie użyto nieaktualnych danych."
            "REFERENCE_AMBIGUOUS" in reason || "EXISTING_DISCOURSE_REFERENT_REQUIRED" in reason ->
                "O którego miejsca, przedmiotu lub rozmówcę chodzi? Podaj nazwę albo opisz go dokładniej. Ta próba nie zmieniła świata."
            "KNOWN_ROUTE_REQUIRED" in reason || "ROUTE_UNKNOWN" in reason || "NATURAL_OR_REMOTE_TOPOLOGY_UNRESOLVED" in reason ->
                "Nie znasz jeszcze drogi do tego celu. Możesz zapytać o drogę lub wskazać znaną trasę. Nie przeniesiono Cię i nie upłynął czas tej próby."
            "CONTRADICT" in reason ->
                "Ta czynność jest sprzeczna z ustalonym stanem świata. Wybierz inny cel lub doprecyzuj zamiar. Niczego nie zmieniono."
            "TRAVEL_REQUIREMENT" in reason || "TRAVEL_COST" in reason || "TRAVEL_CAPABILIT" in reason ->
                "Nie są spełnione wymagania tej podróży. Sprawdź trasę, sposób przemieszczania i dostępne zasoby. Nie pobrano kosztów."
            "LATENT_RULE_OR_SLOT_UNAVAILABLE" in reason || "NAMED_SLOT_EVIDENCE_REQUIRED" in reason ->
                "Nie mogę jeszcze potwierdzić tego elementu świata. Doprecyzuj opis lub poszukaj informacji; nie utworzono fikcyjnego celu."
            else -> null
        }
    }
}
