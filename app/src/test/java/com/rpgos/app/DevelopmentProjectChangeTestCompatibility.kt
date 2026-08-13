package com.rpgos.app

internal fun DevelopmentProjectChange.Companion.create(
    projectUid: String,
    workResultKindUid: String,
    progressDelta: ExactLongDelta,
    evidenceRefs: List<DomainRef> = emptyList()
): DevelopmentProjectChange = create(
    projectUid,
    workResultKindUid,
    ProjectProgressDelta.of(progressDelta.units),
    evidenceRefs
)
