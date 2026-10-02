package com.rpgos.app

/** Only a literal, unambiguous duration declared by the player. This is not a verb clock or
 * a replacement for domain timing. Multiple actions still need individual/dependency timing. */
internal object Phase60PlayerDeclaredDuration {
    private val duration=Regex("(?iu)\\b(?:przez|for)\\s+(\\d+|jedną|jedna|a|an|one)\\s*(sekund\\p{L}*|minut\\p{L}*|godzin\\p{L}*|second\\p{L}*|minute\\p{L}*|hour\\p{L}*)\\b")
    fun read(input:String):ActionDuration? {
        if(input.length>16384)return null
        // Question marks in delivered speech do not make the preceding action a meta question.
        // Conversely, time mentioned inside that speech is not the player's action duration.
        val surface=input.take(input.indexOfFirst{it==':' || it in "„“\"«"}.takeIf{it>=0}?:input.length)
        if('?' in surface)return null
        val matches=duration.findAll(surface).toList()
        if(matches.size!=1)return null
        val match=matches.single()
        // Do not choose one bound from a range or a negated/time-planning question.
        val prefix=surface.substring(0,match.range.first).trimEnd()
        if(Regex("(?iu)\\b(nie|not|czy|maybe|może|moze|około|okolo)\\s*$").containsMatchIn(prefix))return null
        if(Regex("(?iu)^\\s*(?:lub|albo|or|to|do)\\b").containsMatchIn(surface.substring(match.range.last+1)))return null
        val number=match.groupValues[1].trim()
        val units=if(number.first().isDigit())number.toLongOrNull()?:return null else 1L
        val unit=match.groupValues[2].lowercase()
        val scale=when{unit.startsWith("sek")||unit.startsWith("second")->1000L;unit.startsWith("min")->60000L;else->3600000L}
        return runCatching{ActionDuration(Math.multiplyExact(units,scale)).takeIf{it.milliseconds>0}}.getOrNull()
    }
}
