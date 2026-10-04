package com.rpgos.app

/** Rebuildable calculation cursor over the active due index, never a population/global scan. */
internal data class BackgroundDueCursor(val due:WorldTimeTick,val uid:String)
internal data class BackgroundDuePage(val processes:List<BackgroundProcessInstance>,val next:BackgroundDueCursor?) {
    init { require(processes.size<=32) }
}
internal interface BackgroundProcessFrontierPort {
    fun page(through:WorldTimeTick,after:BackgroundDueCursor?):BackgroundDuePage
    fun definition(uid:String,version:Int):BackgroundProcessDefinition?
}
