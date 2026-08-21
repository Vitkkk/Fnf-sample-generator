package com.vitkkk.vocalstretcher;

/**
 * Clean-room waveform time stretcher inspired by AckieSound's public technical
 * description of the WaveTone/VocalShifter time-stretch method.
 *
 * Core idea:
 * - ~50 ms waveform blocks;
 * - overlapping synthesis;
 * - nominal source position advances continuously through the selected audio;
 * - around each nominal boundary, search for the best phase-compatible join
 *   by minimizing normalized squared waveform error;
 * - sine-window overlap/add for smooth amplitude through the joins.
 *
 * This contains no VocalShifter code or binaries.
 */
final class WaveStretchEngine {
    private WaveStretchEngine() {}

    static AudioData stretchRegion(AudioData in, int startFrame, int endFrame, int targetFrames) {
        if (in == null) throw new IllegalArgumentException("Áudio inválido.");
        startFrame = clamp(startFrame, 0, in.frameCount());
        endFrame = clamp(endFrame, startFrame, in.frameCount());
        int sourceFrames = endFrame - startFrame;
        if (sourceFrames <= 0) throw new IllegalArgumentException("Área de stretch vazia.");
        if (targetFrames < sourceFrames) {
            throw new IllegalArgumentException("Wave Stretch desta versão é somente para alongar.");
        }

        if (targetFrames == sourceFrames) {
            float[] copy = new float[in.samples.length];
            System.arraycopy(in.samples, 0, copy, 0, copy.length);
            return new AudioData(in.sampleRate, in.channels, copy);
        }

        float[] source = new float[sourceFrames * in.channels];
        System.arraycopy(in.samples, startFrame * in.channels, source, 0, source.length);
        float[] stretched = stretchSamples(source, sourceFrames, in.channels, in.sampleRate, targetFrames);

        int outFrames = in.frameCount() - sourceFrames + targetFrames;
        float[] out = new float[outFrames * in.channels];
        int prefixSamples = startFrame * in.channels;
        System.arraycopy(in.samples, 0, out, 0, prefixSamples);
        System.arraycopy(stretched, 0, out, prefixSamples, stretched.length);
        int suffixFrames = in.frameCount() - endFrame;
        System.arraycopy(in.samples, endFrame * in.channels, out,
                (startFrame + targetFrames) * in.channels, suffixFrames * in.channels);
        return new AudioData(in.sampleRate, in.channels, out);
    }

    private static float[] stretchSamples(float[] source, int sourceFrames, int channels,
                                          int sampleRate, int targetFrames) {
        if (sourceFrames < 64) throw new IllegalArgumentException("Trecho curto demais para Wave Stretch.");

        // AckieSound's article suggests roughly 50 ms. Keep that target but
        // shrink gracefully for short chromatic vowels.
        int idealBlock = Math.max(256, (int) Math.round(sampleRate * 0.050));
        int block = Math.min(sourceFrames, idealBlock);
        block = Math.max(64, block);

        // 75% overlap gives the squared-error search plenty of common waveform
        // to match while making the synthesis less grain-like at large ratios.
        int synthesisHop = Math.max(16, block / 4);
        int overlap = block - synthesisHop;

        double period = estimatePeriod(source, sourceFrames, channels, sampleRate);
        int defaultSearch = (int) Math.round(sampleRate * 0.010);
        int pitchSearch = period > 0.0 ? (int) Math.round(period * 2.25) : defaultSearch;
        int searchRadius = clamp(pitchSearch,
                Math.max(8, (int) Math.round(sampleRate * 0.003)),
                Math.max(16, (int) Math.round(sampleRate * 0.018)));
        searchRadius = Math.min(searchRadius, Math.max(1, sourceFrames - block));

        float[] accum = new float[targetFrames * channels];
        float[] weights = new float[targetFrames];
        double[] window = buildSineWindow(block);

        int maxSourceStart = Math.max(0, sourceFrames - block);
        int maxOutputStart = Math.max(0, targetFrames - block);
        int outPos = 0;
        int previousCandidate = 0;
        boolean first = true;

        while (outPos < targetFrames) {
            int nominal;
            if (maxOutputStart <= 0 || maxSourceStart <= 0) {
                nominal = 0;
            } else {
                double u = clamp(outPos / (double) maxOutputStart, 0.0, 1.0);
                nominal = (int) Math.round(u * maxSourceStart);
            }

            int candidate;
            if (first) {
                candidate = 0;
            } else if (outPos >= maxOutputStart) {
                // Make the last grain explicitly include the original tail.
                candidate = maxSourceStart;
            } else {
                candidate = findBestCandidate(source, sourceFrames, channels,
                        accum, weights, targetFrames, outPos, block, overlap,
                        nominal, previousCandidate, searchRadius, period);
            }

            addBlock(source, sourceFrames, channels, candidate,
                    accum, weights, targetFrames, outPos, block, window);
            previousCandidate = candidate;
            first = false;
            if (outPos >= maxOutputStart) break;
            outPos = Math.min(maxOutputStart, outPos + synthesisHop);
        }

        float[] result = new float[targetFrames * channels];
        for (int f = 0; f < targetFrames; f++) {
            float w = weights[f];
            int base = f * channels;
            if (w > 1e-7f) {
                for (int c = 0; c < channels; c++) {
                    result[base + c] = finiteClamp(accum[base + c] / w);
                }
            } else {
                // This should rarely happen. Fill any uncovered edge using a
                // monotonic source mapping rather than silence or repetition.
                int srcFrame = targetFrames <= 1 ? 0 :
                        (int) Math.round(f * (sourceFrames - 1.0) / (targetFrames - 1.0));
                srcFrame = clamp(srcFrame, 0, sourceFrames - 1);
                for (int c = 0; c < channels; c++) {
                    result[base + c] = source[srcFrame * channels + c];
                }
            }
        }

        preserveOriginalEdges(source, sourceFrames, result, targetFrames, channels, sampleRate);
        return result;
    }

    private static int findBestCandidate(float[] source, int sourceFrames, int channels,
                                         float[] accum, float[] weights, int targetFrames,
                                         int outPos, int block, int overlap, int nominal,
                                         int previousCandidate, int searchRadius, double period) {
        int maxSourceStart = Math.max(0, sourceFrames - block);
        int from = clamp(nominal - searchRadius, 0, maxSourceStart);
        int to = clamp(nominal + searchRadius, from, maxSourceStart);
        if (from == to) return from;

        // Do not allow a phase search to teleport far backwards in the source.
        // Small backwards movement of roughly one period is useful for phase
        // alignment and is exactly what makes the join disappear.
        int backwardAllowance = period > 0.0
                ? Math.max(4, (int) Math.round(period * 1.25))
                : Math.max(4, searchRadius / 3);
        from = Math.max(from, previousCandidate - backwardAllowance);

        int compareFrames = Math.min(overlap, Math.max(64, (int) Math.round(block * 0.55)));
        compareFrames = Math.min(compareFrames, targetFrames - outPos);
        if (compareFrames < 16) return nominal;

        int compareStep = sampleRateStep(sourceFrames, compareFrames);
        int candidateStep = Math.max(1, compareStep / 2);
        double bestScore = Double.POSITIVE_INFINITY;
        int best = nominal;

        for (int cand = from; cand <= to; cand += candidateStep) {
            if (cand + compareFrames > sourceFrames) break;
            double score = normalizedSquaredError(source, channels, cand,
                    accum, weights, outPos, compareFrames, compareStep);
            double distance = (cand - nominal) / (double) Math.max(1, searchRadius);
            // Tiny bias keeps the search local when two pitch-compatible
            // candidates are essentially equally good.
            score += 0.025 * distance * distance;
            if (score < bestScore) {
                bestScore = score;
                best = cand;
            }
        }

        // Refine at sample resolution around the coarse winner.
        int refine = Math.max(2, candidateStep * 2);
        int r0 = clamp(best - refine, from, to);
        int r1 = clamp(best + refine, r0, to);
        for (int cand = r0; cand <= r1; cand++) {
            if (cand + compareFrames > sourceFrames) break;
            double score = normalizedSquaredError(source, channels, cand,
                    accum, weights, outPos, compareFrames, Math.max(1, compareStep / 2));
            double distance = (cand - nominal) / (double) Math.max(1, searchRadius);
            score += 0.025 * distance * distance;
            if (score < bestScore) {
                bestScore = score;
                best = cand;
            }
        }
        return clamp(best, 0, maxSourceStart);
    }

    private static double normalizedSquaredError(float[] source, int channels, int sourceStart,
                                                 float[] accum, float[] weights, int outputStart,
                                                 int frames, int step) {
        double srcMean = 0.0;
        double outMean = 0.0;
        int count = 0;
        for (int k = 0; k < frames; k += step) {
            int of = outputStart + k;
            if (of >= weights.length || weights[of] <= 1e-7f) continue;
            srcMean += mono(source, (sourceStart + k) * channels, channels);
            outMean += monoAccum(accum, weights[of], of * channels, channels);
            count++;
        }
        if (count < 8) return 0.0;
        srcMean /= count;
        outMean /= count;

        double srcEnergy = 1e-12;
        double outEnergy = 1e-12;
        for (int k = 0; k < frames; k += step) {
            int of = outputStart + k;
            if (of >= weights.length || weights[of] <= 1e-7f) continue;
            double a = mono(source, (sourceStart + k) * channels, channels) - srcMean;
            double b = monoAccum(accum, weights[of], of * channels, channels) - outMean;
            srcEnergy += a * a;
            outEnergy += b * b;
        }
        double srcScale = Math.sqrt(srcEnergy / count) + 1e-9;
        double outScale = Math.sqrt(outEnergy / count) + 1e-9;

        double error = 0.0;
        for (int k = 0; k < frames; k += step) {
            int of = outputStart + k;
            if (of >= weights.length || weights[of] <= 1e-7f) continue;
            double a = (mono(source, (sourceStart + k) * channels, channels) - srcMean) / srcScale;
            double b = (monoAccum(accum, weights[of], of * channels, channels) - outMean) / outScale;
            double d = a - b;
            error += d * d;
        }
        double rmsRatio = Math.log(srcScale / outScale);
        return error / count + 0.035 * rmsRatio * rmsRatio;
    }

    private static void addBlock(float[] source, int sourceFrames, int channels, int sourceStart,
                                 float[] accum, float[] weights, int targetFrames,
                                 int outputStart, int block, double[] window) {
        int usable = Math.min(block, Math.min(sourceFrames - sourceStart, targetFrames - outputStart));
        for (int n = 0; n < usable; n++) {
            float w = (float) window[n];
            int srcBase = (sourceStart + n) * channels;
            int outBase = (outputStart + n) * channels;
            for (int c = 0; c < channels; c++) {
                accum[outBase + c] += source[srcBase + c] * w;
            }
            weights[outputStart + n] += w;
        }
    }

    private static double[] buildSineWindow(int block) {
        double[] w = new double[block];
        for (int i = 0; i < block; i++) {
            // A half-sine is smooth at both ends. Weight normalization after
            // overlap/add keeps level stable even with 75% overlap.
            w[i] = Math.sin(Math.PI * (i + 0.5) / block);
        }
        return w;
    }

    private static double estimatePeriod(float[] source, int frames, int channels, int sampleRate) {
        int maxAnalysis = Math.min(frames, Math.max(512, (int) Math.round(sampleRate * 0.100)));
        int start = Math.max(0, (frames - maxAnalysis) / 2);
        int minLag = Math.max(4, sampleRate / 1200);
        int maxLag = Math.min(maxAnalysis / 2, sampleRate / 55);
        if (maxLag <= minLag + 2) return 0.0;

        double mean = 0.0;
        for (int i = 0; i < maxAnalysis; i++) {
            mean += mono(source, (start + i) * channels, channels);
        }
        mean /= maxAnalysis;

        double bestCorr = -1.0;
        int bestLag = minLag;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double xy = 0.0, xx = 0.0, yy = 0.0;
            int n = maxAnalysis - lag;
            int step = Math.max(1, n / 1200);
            for (int i = 0; i < n; i += step) {
                double a = mono(source, (start + i) * channels, channels) - mean;
                double b = mono(source, (start + i + lag) * channels, channels) - mean;
                xy += a * b;
                xx += a * a;
                yy += b * b;
            }
            double corr = (xx > 1e-12 && yy > 1e-12) ? xy / Math.sqrt(xx * yy) : -1.0;
            if (corr > bestCorr) {
                bestCorr = corr;
                bestLag = lag;
            }
        }
        return bestCorr >= 0.12 ? bestLag : 0.0;
    }

    private static void preserveOriginalEdges(float[] source, int sourceFrames,
                                              float[] result, int targetFrames,
                                              int channels, int sampleRate) {
        int hard = Math.min(Math.min(sourceFrames / 8, targetFrames / 8),
                Math.max(1, (int) Math.round(sampleRate * 0.003)));
        int fade = Math.min(Math.min(sourceFrames / 6, targetFrames / 6),
                Math.max(2, (int) Math.round(sampleRate * 0.006)));

        for (int f = 0; f < hard; f++) {
            copyFrame(source, f, result, f, channels);
            copyFrame(source, sourceFrames - 1 - f, result, targetFrames - 1 - f, channels);
        }
        for (int i = 0; i < fade; i++) {
            float t = (i + 1f) / (fade + 1f);
            int outStart = hard + i;
            int srcStart = Math.min(sourceFrames - 1, hard + i);
            if (outStart < targetFrames) {
                blendFrame(source, srcStart, result, outStart, channels, 1f - t);
            }

            int outEnd = targetFrames - 1 - hard - i;
            int srcEnd = sourceFrames - 1 - hard - i;
            if (outEnd >= 0 && srcEnd >= 0) {
                blendFrame(source, srcEnd, result, outEnd, channels, 1f - t);
            }
        }
    }

    private static void blendFrame(float[] source, int srcFrame, float[] result,
                                   int outFrame, int channels, float sourceAmount) {
        sourceAmount = (float) clamp(sourceAmount, 0.0, 1.0);
        float synthAmount = 1f - sourceAmount;
        int s = srcFrame * channels;
        int o = outFrame * channels;
        for (int c = 0; c < channels; c++) {
            result[o + c] = finiteClamp(source[s + c] * sourceAmount + result[o + c] * synthAmount);
        }
    }

    private static void copyFrame(float[] source, int srcFrame, float[] result,
                                  int outFrame, int channels) {
        System.arraycopy(source, srcFrame * channels, result, outFrame * channels, channels);
    }

    private static double mono(float[] data, int base, int channels) {
        double sum = 0.0;
        for (int c = 0; c < channels; c++) sum += data[base + c];
        return sum / channels;
    }

    private static double monoAccum(float[] accum, float weight, int base, int channels) {
        double sum = 0.0;
        for (int c = 0; c < channels; c++) sum += accum[base + c] / weight;
        return sum / channels;
    }

    private static int sampleRateStep(int sourceFrames, int compareFrames) {
        int amount = Math.min(sourceFrames, compareFrames);
        if (amount > 4096) return 4;
        if (amount > 2048) return 3;
        if (amount > 1024) return 2;
        return 1;
    }

    private static float finiteClamp(float v) {
        if (!Float.isFinite(v)) return 0f;
        return Math.max(-1f, Math.min(1f, v));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
