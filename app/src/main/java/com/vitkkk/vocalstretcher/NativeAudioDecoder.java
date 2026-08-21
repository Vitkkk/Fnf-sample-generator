package com.vitkkk.vocalstretcher;

import java.util.Arrays;

/**
 * Native fallback decoder powered by miniaudio.
 * Used only when Android MediaExtractor and the direct Java parsers fail.
 */
final class NativeAudioDecoder {
    static {
        System.loadLibrary("vocal_world");
    }

    private NativeAudioDecoder() {}

    static AudioData decodeFile(String path) {
        float[] packed = nativeDecodeFile(path);
        if (packed == null || packed.length < 3) return null;

        int sampleRate = Math.round(packed[0]);
        int channels = Math.round(packed[1]);
        if (sampleRate <= 0 || channels <= 0 || channels > 32) return null;

        float[] samples = Arrays.copyOfRange(packed, 2, packed.length);
        if (samples.length == 0) return null;
        return AudioData.reduceToAtMostStereo(sampleRate, channels, samples);
    }

    private static native float[] nativeDecodeFile(String path);
}
