package com.vitkkk.vocalstretcher;

/**
 * Compatibility entry point used by the editor UI.
 *
 * v0.5.8 test build routes the selected green region through the clean-room
 * waveform stretcher inspired by AckieSound's public WaveTone/VocalShifter
 * time-stretch description. WORLD remains in the project so we can switch or
 * compare engines again without throwing away the proven harmonic version.
 */
final class StretchEngineV2 {
    private StretchEngineV2() {}

    static AudioData stretchRegion(AudioData in, int startFrame, int endFrame, int targetFrames) {
        return WaveStretchEngine.stretchRegion(in, startFrame, endFrame, targetFrames);
    }

    static AudioData stretchRegionWorld(AudioData in, int startFrame, int endFrame, int targetFrames) {
        return WorldVocoderEngine.stretchRegion(in, startFrame, endFrame, targetFrames);
    }
}
