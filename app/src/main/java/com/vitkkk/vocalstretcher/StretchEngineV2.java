package com.vitkkk.vocalstretcher;

/**
 * Compatibility entry point used by the current editor UI.
 *
 * v0.3 deliberately routes every stretch through WORLD vocoder resynthesis.
 * There is no PSOLA/WSOLA/granular fallback here: if synthesis cannot analyze
 * the selected vowel, the operation fails instead of silently reverting to a
 * repeated-grain sound.
 */
final class StretchEngineV2 {
    private StretchEngineV2() {}

    static AudioData stretchRegion(AudioData in, int startFrame, int endFrame, int targetFrames) {
        return WorldVocoderEngine.stretchRegion(in, startFrame, endFrame, targetFrames);
    }
}
