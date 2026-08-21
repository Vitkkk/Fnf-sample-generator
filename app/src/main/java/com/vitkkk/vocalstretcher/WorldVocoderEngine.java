package com.vitkkk.vocalstretcher;

import java.util.Arrays;

/**
 * WORLD-based vowel synthesis.
 *
 * v0.5.5:
 * - chooses the cleanest analysis source among L, R and L+R for stereo files;
 * - uses octave-locked pitch tracking for one-note chromatic samples;
 * - uses a formant/spectral identity anchor so A cannot drift into I/O;
 * - preserves natural loudness/pitch motion inside safe limits;
 * - attack/release outside the periodic core remain original PCM.
 */
final class WorldVocoderEngine {
    static {
        System.loadLibrary("vocal_world");
    }

    private WorldVocoderEngine() {}

    private static final class AnalysisChoice {
        final float[] samples;
        final int[] coreInfo;
        final int mode; // 0..channels-1 = channel, channels = mono/mid
        final int score;

        AnalysisChoice(float[] samples, int[] coreInfo, int mode, int score) {
            this.samples = samples;
            this.coreInfo = coreInfo;
            this.mode = mode;
            this.score = score;
        }
    }

    static AudioData stretchRegion(AudioData in, int startFrame, int endFrame, int targetFrames) {
        startFrame = clamp(startFrame, 0, in.frameCount());
        endFrame = clamp(endFrame, startFrame, in.frameCount());
        int sourceFrames = endFrame - startFrame;

        int minimumFrames = Math.max(256, (int) Math.round(in.sampleRate * 0.050));
        if (sourceFrames < minimumFrames) {
            throw new IllegalArgumentException("Selecione pelo menos ~50 ms da vogal para a síntese WORLD.");
        }
        if (targetFrames < sourceFrames) {
            throw new IllegalArgumentException("A nova duração precisa ser maior ou igual à região selecionada.");
        }
        if (targetFrames == sourceFrames) {
            return new AudioData(in.sampleRate, in.channels, in.samples.clone());
        }

        AnalysisChoice analysis = chooseBestAnalysis(in, startFrame, endFrame);
        if (analysis == null || analysis.coreInfo == null || analysis.coreInfo.length < 3) {
            throw new IllegalArgumentException(
                    "Não consegui detectar periodicidade suficiente nesse trecho. Se você está ouvindo uma vogal limpa, tente ampliar só alguns milissegundos para cada lado e tente novamente.");
        }

        int coreStart = clamp(analysis.coreInfo[0], 0, sourceFrames - 1);
        int coreEnd = clamp(analysis.coreInfo[1], coreStart + 1, sourceFrames);
        int quality = analysis.coreInfo[2];
        int coreSourceFrames = coreEnd - coreStart;

        int minimumCore = Math.max(96, (int) Math.round(in.sampleRate * 0.025));
        if (coreSourceFrames < minimumCore || quality < 120) {
            throw new IllegalArgumentException(
                    "O trecho ficou curto ou pouco periódico demais para alongar com segurança. Tente pegar um pouco mais da mesma vogal.");
        }

        int attackFrames = coreStart;
        int releaseFrames = sourceFrames - coreEnd;
        int targetCoreFrames = targetFrames - attackFrames - releaseFrames;
        if (targetCoreFrames < coreSourceFrames) targetCoreFrames = coreSourceFrames;

        float[] coreMono = Arrays.copyOfRange(analysis.samples, coreStart, coreEnd);
        float[] synthesized = nativeSynthesizeIdentityLocked(coreMono, in.sampleRate, targetCoreFrames);
        if (synthesized == null || synthesized.length != targetCoreFrames) {
            throw new IllegalStateException(
                    "O sintetizador não conseguiu preservar pitch/formantes desse trecho. Tente mover os cortes alguns milissegundos.");
        }

        removeDc(synthesized);
        matchRms(coreMono, synthesized);
        softLimit(synthesized);

        int channels = in.channels;
        int absoluteCoreStart = startFrame + coreStart;
        int absoluteCoreEnd = startFrame + coreEnd;
        float[] channelGain = estimateChannelGains(
                in, absoluteCoreStart, absoluteCoreEnd, coreMono);
        float[] stretched = new float[targetFrames * channels];

        if (attackFrames > 0) {
            System.arraycopy(in.samples, startFrame * channels, stretched, 0,
                    attackFrames * channels);
        }

        int coreOutStart = attackFrames;
        for (int f = 0; f < targetCoreFrames; f++) {
            float s = synthesized[f];
            int base = (coreOutStart + f) * channels;
            for (int c = 0; c < channels; c++) {
                stretched[base + c] = AudioData.clamp(s * channelGain[c]);
            }
        }

        int releaseOutStart = attackFrames + targetCoreFrames;
        if (releaseFrames > 0) {
            System.arraycopy(in.samples, absoluteCoreEnd * channels, stretched,
                    releaseOutStart * channels, releaseFrames * channels);
        }

        int fade = Math.min(coreSourceFrames / 4, targetCoreFrames / 4);
        fade = Math.min(fade, Math.max(16, (int) Math.round(in.sampleRate * 0.012)));
        if (fade > 1) {
            for (int i = 0; i < fade; i++) {
                float t = i / (float) (fade - 1);

                int outStart = (coreOutStart + i) * channels;
                int srcStart = (absoluteCoreStart + i) * channels;

                int outEndFrame = coreOutStart + targetCoreFrames - fade + i;
                int srcEndFrame = absoluteCoreEnd - fade + i;
                int outEnd = outEndFrame * channels;
                int srcEnd = srcEndFrame * channels;

                for (int c = 0; c < channels; c++) {
                    stretched[outStart + c] = equalPower(
                            in.samples[srcStart + c], stretched[outStart + c], t);
                    stretched[outEnd + c] = equalPower(
                            stretched[outEnd + c], in.samples[srcEnd + c], t);
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

    private static AnalysisChoice chooseBestAnalysis(AudioData in, int startFrame, int endFrame) {
        int frames = endFrame - startFrame;
        AnalysisChoice best = null;

        // Try each physical channel first. This avoids phase cancellation from
        // chorus/delay/stereo processing changing the vowel during analysis.
        for (int c = 0; c < in.channels; c++) {
            float[] candidate = new float[frames];
            for (int f = 0; f < frames; f++) {
                candidate[f] = in.samples[(startFrame + f) * in.channels + c];
            }
            best = chooseIfBetter(best, candidate, c, in.sampleRate, frames);
        }

        // Also try the normal mono/mid sum because many files are clean centered vocals.
        if (in.channels > 1) {
            float[] mid = new float[frames];
            for (int f = 0; f < frames; f++) mid[f] = in.monoAt(startFrame + f);
            best = chooseIfBetter(best, mid, in.channels, in.sampleRate, frames);
        }

        return best;
    }

    private static AnalysisChoice chooseIfBetter(AnalysisChoice current, float[] candidate,
                                                 int mode, int sampleRate, int totalFrames) {
        int[] info = nativeFindStableVoicedCore(candidate, sampleRate);
        if (info == null || info.length < 3) return current;
        int start = clamp(info[0], 0, Math.max(0, totalFrames - 1));
        int end = clamp(info[1], start + 1, totalFrames);
        int core = end - start;
        int coverageBonus = (int) Math.round(220.0 * core / Math.max(1, totalFrames));
        int score = info[2] + coverageBonus;
        if (current == null || score > current.score) {
            return new AnalysisChoice(candidate, info, mode, score);
        }
        return current;
    }

    /** Returns {startSampleInclusive, endSampleExclusive, quality0to1000}. */
    private static native int[] nativeFindStableVoicedCore(float[] monoSamples, int sampleRate);

    private static native float[] nativeSynthesizeIdentityLocked(
            float[] monoSamples, int sampleRate, int targetFrames);

    private static float[] estimateChannelGains(AudioData in, int start, int end,
                                                float[] analysisCore) {
        float[] gains = new float[in.channels];
        if (in.channels == 1) {
            gains[0] = 1f;
            return gains;
        }

        int refMargin = analysisCore.length / 5;
        double referenceRms = rms(analysisCore, refMargin, analysisCore.length - refMargin);
        referenceRms = Math.max(1e-6, referenceRms);

        int margin = Math.min((end - start) / 4, Math.max(1, in.sampleRate / 50));
        int from = Math.min(end - 1, start + margin);
        int to = Math.max(from + 1, end - margin);
        double[] channelEnergy = new double[in.channels];
        for (int f = from; f < to; f++) {
            int base = f * in.channels;
            for (int c = 0; c < in.channels; c++) {
                double v = in.samples[base + c];
                channelEnergy[c] += v * v;
            }
        }
        for (int c = 0; c < in.channels; c++) {
            double channelRms = Math.sqrt(channelEnergy[c] / Math.max(1, to - from));
            gains[c] = (float) clamp(channelRms / referenceRms, 0.25, 2.0);
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
