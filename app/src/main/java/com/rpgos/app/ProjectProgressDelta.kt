package com.rpgos.app

/** Exact non-negative progress amount for a DevelopmentProject work proposal. */
data class ProjectProgressDelta private constructor(val units: Long) {
    init {
        if (units < 0L) throw PlayerChangeSetStructuralException("NEGATIVE_PROJECT_PROGRESS_DELTA")
    }

    companion object {
        fun of(units: Long): ProjectProgressDelta = ProjectProgressDelta(units)
    }
}
