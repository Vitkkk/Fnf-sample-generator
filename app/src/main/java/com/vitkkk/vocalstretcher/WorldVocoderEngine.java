package com.vitkkk.vocalstretcher;

/**
 * Vowel synthesis based on WORLD's source/filter representation.
 *
 * This is deliberately different from PSOLA/WSOLA/granular stretching: the
 * selected waveform is first analyzed, then a NEW waveform is synthesized from
 * F0 + spectral envelope + aperiodicity. The original PCM is only used for the
 * short crossfades at the boundaries of the edited region.
 */
final class WorldVocoderEngine {
    static {
        System.loadLibrary("vocal_world");
    }

    private WorldVocoderEngine() {}

    static AudioData stretchRegion(AudioData in, int startFrame, int endFrame, int targetFrames) {
        startFrame = clamp(startFrame, 0, in.frameCount());
        endFrame = clamp(endFrame, startFrame, in.frameCount());
        int sourceFrames = endFrame - startFrame;

        int minimumFrames = Math.max(256, (int) Math.round(in.sampleRate * 0.060));
        if (sourceFrames < minimumFrames) {
            throw new IllegalArgumentException("Selecione pelo menos ~60 ms de uma vogal limpa para a síntese WORLD.");
        }
        if (targetFrames < sourceFrames) {
            throw new IllegalArgumentException("A nova duração precisa ser maior ou igual à região selecionada.");
        }
        if (targetFrames == sourceFrames) {
            return new AudioData(in.sampleRate, in.channels, in.samples.clone());
        }

        float[] mono = new float[sourceFrames];
        for (int f = 0; f < sourceFrames; f++) {
            mono[f] = in.monoAt(startFrame + f);
        }

        float[] synthesized = nativeSynthesizeVowel(mono, in.sampleRate, targetFrames);
        if (synthesized == null || synthesized.length != targetFrames) {
            throw new IllegalStateException("O sintetizador WORLD não retornou o tamanho solicitado.");
        }

        removeDc(synthesized);
        matchRms(mono, synthesized);
        softLimit(synthesized);

        int channels = in.channels;
        float[] channelGain = estimateChannelGains(in, startFrame, endFrame);
        float[] stretched = new float[targetFrames * channels];
        for (int f = 0; f < targetFrames; f++) {
            float s = synthesized[f];
            int base = f * channels;
            for (int c = 0; c < channels; c++) {
                stretched[base + c] = AudioData.clamp(s * channelGain[c]);
            }
        }

        // The body is 100% synthesized. Only a very short boundary crossfade
        // uses original PCM so the edit joins the untouched file without clicks.
        int fade = Math.min(sourceFrames / 4, targetFrames / 4);
        fade = Math.min(fade, Math.max(16, (int) Math.round(in.sampleRate * 0.018)));
        if (fade > 1) {
            for (int i = 0; i < fade; i++) {
                float t = i / (float) (fade - 1);
                int outStart = i * channels;
                int srcStart = (startFrame + i) * channels;
                int outEndFrame = targetFrames - fade + i;
                int srcEndFrame = endFrame - fade + i;
                int outEnd = outEndFrame * channels;
                int srcEnd = srcEndFrame * channels;
                for (int c = 0; c < channels; c++) {
                    stretched[outStart + c] = equalPower(in.samples[srcStart + c], stretched[outStart + c], t);
                    stretched[outEnd + c] = equalPower(stretched[outEnd + c], in.samples[srcEnd + c], t);
                }
            }
        }

        int outFrames = in.frameCount() - sourceFrames + targetFrames;
        float[] out = new float[outFrames * channels];
        int prefixSamples = startFrame * channels;
        System.arraycopy(in.samples, 0, out, 0, prefixSamples);
        System.arraycopy(stretched, 0, out, prefixSamples, stretched.length);
        int suffixSamples = (in.frameCount() - endFrame) * channels;
        System.arraycopy(in.samples, endFrame * channels, out,
                (startFrame + targetFrames) * channels, suffixSamples);
        return new AudioData(in.sampleRate, channels, out);
    }

    private static native float[] nativeSynthesizeVowel(float[] monoSamples, int sampleRate, int targetFrames);

    private static float[] estimateChannelGains(AudioData in, int start, int end) {
        float[] gains = new float[in.channels];
        if (in.channels == 1) {
            gains[0] = 1f;
            return gains;
        }
        int margin = Math.min((end - start) / 4, Math.max(1, in.sampleRate / 50));
        int from = Math.min(end - 1, start + margin);
        int to = Math.max(from + 1, end - margin);
        double monoEnergy = 0.0;
        double[] channelEnergy = new double[in.channels];
        for (int f = from; f < to; f++) {
            float mono = in.monoAt(f);
            monoEnergy += mono * mono;
            int base = f * in.channels;
            for (int c = 0; c < in.channels; c++) {
                double v = in.samples[base + c];
                channelEnergy[c] += v * v;
            }
        }
        monoEnergy = Math.sqrt(monoEnergy / Math.max(1, to - from));
        for (int c = 0; c < in.channels; c++) {
            double rms = Math.sqrt(channelEnergy[c] / Math.max(1, to - from));
            gains[c] = (float) clamp(rms / Math.max(1e-6, monoEnergy), 0.35, 1.8);
        }
        return gains;
    }

    private static void matchRms(float[] source, float[] synth) {
        int sMargin = source.length / 5;
        int yMargin = synth.length / 5;
        double sourceRms = rms(source, sMargin, source.length - sMargin);
        double synthRms = rms(synth, yMargin, synth.length - yMargin);
        if (sourceRms < 1e-7 || synthRms < 1e-7) return;
        double gain = clamp(sourceRms / synthRms, 0.30, 3.0);
        for (int i = 0; i < synth.length; i++) synth[i] *= (float) gain;
    }

    private static double rms(float[] x, int from, int to) {
        from = Math.max(0, Math.min(from, x.length));
        to = Math.max(from + 1, Math.min(to, x.length));
        double e = 0.0;
        for (int i = from; i < to; i++) e += x[i] * x[i];
        return Math.sqrt(e / Math.max(1, to - from));
    }

    private static void removeDc(float[] x) {
        double mean = 0.0;
        for (float v : x) mean += v;
        mean /= Math.max(1, x.length);
        for (int i = 0; i < x.length; i++) x[i] -= (float) mean;
    }

    private static void softLimit(float[] x) {
        float peak = 0f;
        for (float v : x) peak = Math.max(peak, Math.abs(v));
        if (peak <= 0.98f) return;
        float gain = 0.98f / peak;
        for (int i = 0; i < x.length; i++) x[i] *= gain;
    }

    private static float equalPower(float a, float b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        double angle = t * Math.PI * 0.5;
        return AudioData.clamp((float) (a * Math.cos(angle) + b * Math.sin(angle)));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
