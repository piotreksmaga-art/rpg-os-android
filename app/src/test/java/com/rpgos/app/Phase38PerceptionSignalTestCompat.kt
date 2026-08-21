package com.rpgos.app

/** Test-only compatibility for legacy Slice-D fixtures after AUD-005 sealed PerceptionSignal. */
internal fun PerceptionSignal.copy(ref:PerceptionSignalRef=this.ref):PerceptionSignal =
    Phase38PerceptionRuntimeAuthority.issueSignal(
        campaignUid=campaignUid,
        ref=ref,
        signalKindUid=signalKindUid,
        quality=quality,
        evidence=evidence,
        uncertainty=uncertainty,
        presentedSubject=presentedSubject,
        observationMetadata=observationMetadata
    )
