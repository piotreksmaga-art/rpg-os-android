package com.rpgos.app;

import android.os.Bundle;

interface IExecuTorchInferenceService {
    Bundle generate(
        String modelPath,
        String tokenizerPath,
        int contextUnits,
        String prompt,
        int maximumOutputUnits
    );
    void cancelGeneration();
}
