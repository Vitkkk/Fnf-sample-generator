package com.vitkkk.vocalstretcher;

import java.util.ArrayList;
import java.util.List;

/**
 * True time-scale modification for monophonic vocals.
 *
 * Unlike the v0.1 engine, this never cycles a fixed block of audio. The source
 * cursor always moves forward through the selected region. Voiced material is
 * rebuilt with pitch-synchronous overlap-add (PSOLA); uncertain/unvoiced
 * material falls back to WSOLA with a forward-moving analysis cursor.
 */
final class StretchEngineV2 {
    private static final double TWO_PI = Math.PI * 2.0;

    private StretchEngineV2() {}

    static AudioData stretchRegion(AudioData in, int startFrame, int endFrame, int targetFrames) {
        startFrame = clamp(startFrame, 0, in.frameCount());
        endFrame = clamp(endFrame, startFrame, in.frameCount());
        int sourceFrames = endFrame - startFrame;
        if (sourceFrames < Math.max(64, in.sampleRate / 200)) {
            throw new IllegalArgumentException("Selected region is too short for high-quality stretching.");
        }
        if (targetFrames < sourceFrames) {
            throw new IllegalArgumentException("Target duration must be at least the selected duration.");
        }
        if (targetFrames == sourceFrames) {
            float[] copy = in.samples.clone();
            return new AudioData(in.sampleRate, in.channels, copy);
        }

        PitchEstimate pitch = estimatePitch(in, startFrame, endFrame);
        float[] stretched;
        if (pitch.confidence >= 0.34) {
            stretched = psolaStretch(in, startFrame, endFrame, targetFrames, pitch);
        } else {
            stretched = wsolaStretch(in, startFrame, endFrame, targetFrames);
        }

        // Preserve the exact beginning/end of the user's region so consonant
        // transitions and attacks are not turned into a smeared stretch.
        protectEdges(in, startFrame, endFrame, stretched, targetFrames, pitch);

        int outFrames = in.frameCount() - sourceFrames + targetFrames;
        float[] out = new float[outFrames * in.channels];
        int prefixSamples = startFrame * in.channels;
        System.arraycopy(in.samples, 0, out, 0, prefixSamples);
        System.arraycopy(stretched, 0, out, prefixSamples, stretched.length);
        int suffixSamples = (in.frameCount() - endFrame) * in.channels;
        System.arraycopy(in.samples, endFrame * in.channels, out,
                (startFrame + targetFrames) * in.channels, suffixSamples);
        return new AudioData(in.sampleRate, in.channels, out);
    }

    private static float[] psolaStretch(AudioData in, int start, int end, int targetFrames,
                                        PitchEstimate pitch) {
        int sourceFrames = end - start;
        double factor = targetFrames / (double) sourceFrames;
        int nominalPeriod = clamp((int) Math.round(pitch.periodFrames),
                Math.max(10, in.sampleRate / 1300), Math.max(24, in.sampleRate / 55));

        List<Integer> marks = buildPitchMarks(in, start, end, nominalPeriod);
        if (marks.size() < 5) return wsolaStretch(in, start, end, targetFrames);

        int channels = in.channels;
        float[] out = new float[targetFrames * channels];
        float[] weights = new float[targetFrames];

        double outputCenter = 0.0;
        while (outputCenter < targetFrames) {
            // Critical difference from v0.1: output time maps monotonically to
            // source time. We never modulo/wrap source marks.
            double sourceRelative = outputCenter / factor;
            double sourceAbsolute = start + sourceRelative;
            int markIndex = nearestMarkIndex(marks, sourceAbsolute);
            int sourceMark = marks.get(markIndex);
            double localPeriod = localPeriod(marks, markIndex, nominalPeriod);

            int radius = clamp((int) Math.round(localPeriod * 1.55), nominalPeriod,
                    Math.max(nominalPeriod + 2, (int) Math.round(nominalPeriod * 2.2)));
            int outCenter = (int) Math.round(outputCenter);

            for (int rel = -radius; rel <= radius; rel++) {
                int of = outCenter + rel;
                int sf = sourceMark + rel;
                if (of < 0 || of >= targetFrames || sf < start || sf >= end) continue;
                double u = (rel + radius) / (double) Math.max(1, radius * 2);
                float w = (float) (0.5 - 0.5 * Math.cos(TWO_PI * u));
                int ob = of * channels;
                int sb = sf * channels;
                for (int c = 0; c < channels; c++) out[ob + c] += in.samples[sb + c] * w;
                weights[of] += w;
            }

            // Preserve pitch/vibrato rate: synthesis hop follows the local
            // source pitch period, not the time-stretch factor.
            outputCenter += Math.max(4.0, localPeriod);
        }

        normalize(out, weights, channels);
        repairSilentHoles(out, weights, channels);
        return out;
    }

    /**
     * Waveform-similarity overlap-add fallback. Each analysis frame advances
     * forward through the selection. Frames overlap heavily when stretching,
     * but the source is never tiled/restarted.
     */
    private static float[] wsolaStretch(AudioData in, int start, int end, int targetFrames) {
        int channels = in.channels;
        int sourceFrames = end - start;
        double factor = targetFrames / (double) sourceFrames;

        int frameSize = clamp((int) Math.round(in.sampleRate * 0.046), 512, 4096);
        if ((frameSize & 1) != 0) frameSize++;
        frameSize = Math.min(frameSize, Math.max(128, sourceFrames));
        int synthHop = Math.max(32, frameSize / 4);
        double analysisHop = synthHop / factor;
        int search = Math.min(frameSize / 4, Math.max(16, (int) (in.sampleRate * 0.006)));
        int overlap = frameSize - synthHop;

        float[] out = new float[targetFrames * channels];
        float[] weights = new float[targetFrames];
        float[] window = hann(frameSize);

        double expectedSource = start;
        int previousSource = start;
        int outputPos = 0;
        boolean first = true;

        while (outputPos < targetFrames) {
            int expected = clamp((int) Math.round(expectedSource), start, Math.max(start, end - frameSize));
            int chosen;
            if (first) {
                chosen = expected;
                first = false;
            } else {
                int minCandidate = Math.max(previousSource, expected - search);
                int maxCandidate = Math.min(Math.max(start, end - frameSize), expected + search);
                chosen = findBestForwardCandidate(in, minCandidate, maxCandidate,
                        out, outputPos, overlap, channels);
            }

            addWindow(in, chosen, out, outputPos, targetFrames, window, weights);
            previousSource = chosen;
            outputPos += synthHop;
            expectedSource += analysisHop;
            if (expectedSource > end - frameSize) expectedSource = end - frameSize;
        }

        normalize(out, weights, channels);
        repairSilentHoles(out, weights, channels);
        return out;
    }

    private static int findBestForwardCandidate(AudioData in, int from, int to,
                                                float[] output, int outputPos,
                                                int overlap, int channels) {
        if (to <= from || outputPos <= 0) return from;
        int compare = Math.min(overlap, outputPos);
        int step = Math.max(1, compare / 160);
        double bestScore = -Double.MAX_VALUE;
        int best = from;

        for (int candidate = from; candidate <= to; candidate += Math.max(1, step / 2)) {
            double xy = 0.0, xx = 0.0, yy = 0.0;
            int outStart = outputPos - compare;
            for (int i = 0; i < compare; i += step) {
                float a = monoFromInterleaved(output, outStart + i, channels);
                float b = in.monoAt(candidate + i);
                xy += a * b;
                xx += a * a;
                yy += b * b;
            }
            double score = (xx > 1e-12 && yy > 1e-12) ? xy / Math.sqrt(xx * yy) : -1.0;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static void addWindow(AudioData in, int sourcePos, float[] out, int outputPos,
                                  int targetFrames, float[] window, float[] weights) {
        int channels = in.channels;
        for (int i = 0; i < window.length; i++) {
            int of = outputPos + i;
            int sf = sourcePos + i;
            if (of < 0 || of >= targetFrames || sf < 0 || sf >= in.frameCount()) continue;
            float w = window[i];
            int ob = of * channels;
            int sb = sf * channels;
            for (int c = 0; c < channels; c++) out[ob + c] += in.samples[sb + c] * w;
            weights[of] += w;
        }
    }

    private static void protectEdges(AudioData in, int start, int end, float[] stretched,
                                     int targetFrames, PitchEstimate pitch) {
        int sourceFrames = end - start;
        int period = clamp((int) Math.round(pitch.periodFrames), 16, in.sampleRate / 50);
        int guard = Math.min(sourceFrames / 5,
                Math.max(period * 2, (int) Math.round(in.sampleRate * 0.018)));
        guard = Math.min(guard, targetFrames / 5);
        if (guard < 4) return;
        int fade = Math.max(2, Math.min(guard, Math.max(period, in.sampleRate / 250)));
        int channels = in.channels;

        // Copy earliest edge exactly, then crossfade into synthesized material.
        int hard = Math.max(0, guard - fade);
        for (int f = 0; f < hard; f++) {
            copyFrame(in.samples, (start + f) * channels, stretched, f * channels, channels);
        }
        for (int i = 0; i < fade; i++) {
            int f = hard + i;
            if (f >= targetFrames || start + f >= end) break;
            float t = i / (float) Math.max(1, fade - 1);
            for (int c = 0; c < channels; c++) {
                int oi = f * channels + c;
                stretched[oi] = equalPower(in.samples[(start + f) * channels + c], stretched[oi], t);
            }
        }

        // Same for release/end, mapped to the end of the longer result.
        int outStart = targetFrames - guard;
        int srcStart = end - guard;
        for (int i = 0; i < guard; i++) {
            int of = outStart + i;
            int sf = srcStart + i;
            if (of < 0 || of >= targetFrames || sf < start || sf >= end) continue;
            if (i < fade) {
                float t = i / (float) Math.max(1, fade - 1);
                for (int c = 0; c < channels; c++) {
                    int oi = of * channels + c;
                    stretched[oi] = equalPower(stretched[oi], in.samples[sf * channels + c], t);
                }
            } else {
                copyFrame(in.samples, sf * channels, stretched, of * channels, channels);
            }
        }
    }

    private static PitchEstimate estimatePitch(AudioData in, int start, int end) {
        int available = end - start;
        int window = Math.min(available, Math.max(1024, (int) Math.round(in.sampleRate * 0.11)));
        if (window < 256) return new PitchEstimate(in.sampleRate / 220.0, 0.0);
        int center = (start + end) / 2;
        int wStart = clamp(center - window / 2, start, Math.max(start, end - window));
        int minLag = Math.max(10, in.sampleRate / 1200);
        int maxLag = Math.min(window / 2, Math.max(minLag + 2, in.sampleRate / 60));

        float[] x = new float[window];
        double mean = 0.0;
        for (int i = 0; i < window; i++) {
            x[i] = in.monoAt(wStart + i);
            mean += x[i];
        }
        mean /= window;
        for (int i = 0; i < window; i++) x[i] -= (float) mean;

        double[] corr = new double[maxLag + 1];
        double best = -1.0;
        int bestLag = minLag;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double xy = 0.0, xx = 0.0, yy = 0.0;
            int n = window - lag;
            for (int i = 0; i < n; i++) {
                double a = x[i], b = x[i + lag];
                xy += a * b;
                xx += a * a;
                yy += b * b;
            }
            double v = (xx > 1e-12 && yy > 1e-12) ? xy / Math.sqrt(xx * yy) : 0.0;
            corr[lag] = v;
            if (v > best) {
                best = v;
                bestLag = lag;
            }
        }

        // Prefer the first strong local maximum, reducing octave-halving errors.
        double threshold = Math.max(0.32, best * 0.90);
        for (int lag = minLag + 1; lag < bestLag; lag++) {
            if (corr[lag] >= threshold && corr[lag] >= corr[lag - 1] && corr[lag] >= corr[lag + 1]) {
                bestLag = lag;
                best = corr[lag];
                break;
            }
        }

        double refined = bestLag;
        if (bestLag > minLag && bestLag < maxLag) {
            double y1 = corr[bestLag - 1], y2 = corr[bestLag], y3 = corr[bestLag + 1];
            double d = y1 - 2.0 * y2 + y3;
            if (Math.abs(d) > 1e-9) refined += 0.5 * (y1 - y3) / d;
        }
        return new PitchEstimate(refined, clamp01(best));
    }

    private static List<Integer> buildPitchMarks(AudioData in, int start, int end, int period) {
        List<Integer> marks = new ArrayList<>();
        if (end - start < period * 4) return marks;
        int anchorExpected = (start + end) / 2;
        int anchor = findPeak(in, anchorExpected, Math.max(3, period / 2), start, end, 0);
        int sign = in.monoAt(anchor) >= 0f ? 1 : -1;
        marks.add(anchor);

        int current = anchor;
        while (current + period < end - 2) {
            int next = findPeak(in, current + period, Math.max(3, period / 3), start, end, sign);
            if (next <= current) break;
            marks.add(next);
            current = next;
        }

        List<Integer> left = new ArrayList<>();
        current = anchor;
        while (current - period > start + 1) {
            int next = findPeak(in, current - period, Math.max(3, period / 3), start, end, sign);
            if (next >= current) break;
            left.add(next);
            current = next;
        }
        for (int i = left.size() - 1; i >= 0; i--) marks.add(0, left.get(i));
        return marks;
    }

    private static int nearestMarkIndex(List<Integer> marks, double sourceFrame) {
        int lo = 0, hi = marks.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (marks.get(mid) < sourceFrame) lo = mid + 1;
            else hi = mid;
        }
        if (lo > 0) {
            double d0 = Math.abs(marks.get(lo) - sourceFrame);
            double d1 = Math.abs(marks.get(lo - 1) - sourceFrame);
            if (d1 <= d0) return lo - 1;
        }
        return lo;
    }

    private static double localPeriod(List<Integer> marks, int index, int fallback) {
        double sum = 0.0;
        int count = 0;
        if (index > 0) {
            sum += marks.get(index) - marks.get(index - 1);
            count++;
        }
        if (index + 1 < marks.size()) {
            sum += marks.get(index + 1) - marks.get(index);
            count++;
        }
        if (count == 0) return fallback;
        return clamp(sum / count, fallback * 0.68, fallback * 1.42);
    }

    private static int findPeak(AudioData in, int expected, int radius, int min, int max, int sign) {
        int from = clamp(expected - radius, min, Math.max(min, max - 1));
        int to = clamp(expected + radius, from + 1, max);
        int bestFrame = from;
        float best = -Float.MAX_VALUE;
        for (int f = from; f < to; f++) {
            float v = in.monoAt(f);
            float score = sign > 0 ? v : sign < 0 ? -v : Math.abs(v);
            if (score > best) {
                best = score;
                bestFrame = f;
            }
        }
        return bestFrame;
    }

    private static float[] hann(int size) {
        float[] w = new float[size];
        if (size <= 1) {
            if (size == 1) w[0] = 1f;
            return w;
        }
        for (int i = 0; i < size; i++) {
            w[i] = (float) (0.5 - 0.5 * Math.cos(TWO_PI * i / (size - 1.0)));
        }
        return w;
    }

    private static void normalize(float[] out, float[] weights, int channels) {
        for (int f = 0; f < weights.length; f++) {
            if (weights[f] > 1e-5f) {
                int base = f * channels;
                float inv = 1f / weights[f];
                for (int c = 0; c < channels; c++) out[base + c] *= inv;
            }
        }
    }

    private static void repairSilentHoles(float[] out, float[] weights, int channels) {
        int lastValid = -1;
        for (int f = 0; f < weights.length; f++) {
            if (weights[f] > 1e-5f) {
                lastValid = f;
            } else if (lastValid >= 0) {
                int src = lastValid * channels;
                int dst = f * channels;
                for (int c = 0; c < channels; c++) out[dst + c] = out[src + c];
            }
        }
        int nextValid = -1;
        for (int f = weights.length - 1; f >= 0; f--) {
            if (weights[f] > 1e-5f) {
                nextValid = f;
            } else if (nextValid >= 0) {
                int src = nextValid * channels;
                int dst = f * channels;
                for (int c = 0; c < channels; c++) out[dst + c] = out[src + c];
            }
        }
    }

    private static float monoFromInterleaved(float[] data, int frame, int channels) {
        if (frame < 0 || frame * channels >= data.length) return 0f;
        float sum = 0f;
        int base = frame * channels;
        for (int c = 0; c < channels; c++) sum += data[base + c];
        return sum / channels;
    }

    private static void copyFrame(float[] src, int srcOffset, float[] dst, int dstOffset, int channels) {
        System.arraycopy(src, srcOffset, dst, dstOffset, channels);
    }

    private static float equalPower(float a, float b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        double angle = t * Math.PI * 0.5;
        return (float) (a * Math.cos(angle) + b * Math.sin(angle));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static final class PitchEstimate {
        final double periodFrames;
        final double confidence;

        PitchEstimate(double periodFrames, double confidence) {
            this.periodFrames = periodFrames;
            this.confidence = confidence;
        }
    }
}
