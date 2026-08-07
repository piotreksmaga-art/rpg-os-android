package com.rpgos.app

class DemoGameMaster {
    fun respond(playerInput: String, context: ContextBundle, chapter: Int): BackendTurnResult {
        val era = context.time["era_name"]?.toString() ?: "nieznana era"
        val date = context.time["year_label"]?.toString() ?: "nieznana data"
        val location = context.scene["location_uid"]?.toString() ?: "nieustalona lokacja"
        val missions = context.missions.size
        val threads = context.activeThreads.size

        val narration = buildString {
            append("[TRYB DEMO OFFLINE]\\n\\n")
            append("Rozdział $chapter. $date, $era. ")
            append("Znajdujesz się w: $location. ")
            append("Silnik widzi $threads aktywnych wątków i $missions dostępnych/aktywnych misji.\\n\\n")
            append("Twoja akcja: „$playerInput”.\\n\\n")
            append("Świat reaguje, ale w trybie demo odpowiedź jest generowana lokalnie i nie korzysta z modelu AI. ")
            append("Możesz testować interfejs, Status, Czas, Świat, NPC, misje, backupy i kronikę.\\n\\n")
            append("Opcje testowe: 1) kontynuuj obserwację, 2) sprawdź status, 3) przejdź do dostępnej misji.")
        }
        return BackendTurnResult(narration = narration, patch = null)
    }
}
