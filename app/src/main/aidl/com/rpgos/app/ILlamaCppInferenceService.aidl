package com.rpgos.app;

import android.os.Bundle;

interface ILlamaCppInferenceService {
    long open(
        String modelPath,
        int contextUnits,
        String backend,
        int threads,
        int prefillBatch,
        int microBatch,
        int gpuLayers,
        String kvKeyType,
        String kvValueType,
        float temperature,
        int topK,
        float topP,
        float repeatPenalty,
        boolean flashAttention,
        boolean memoryMap
    );
    Bundle generate(long handle, String requestUid, String prompt, int maximumOutputUnits);
    long openEmbedding(
        String modelPath,
        int contextUnits,
        String backend,
        int threads,
        int batch,
        int gpuLayers,
        boolean memoryMap
    );
    Bundle embed(long handle, String requestUid, in List<String> texts, int maximumInputUnits);
    void cancel(String requestUid);
    void close(long handle);
}
