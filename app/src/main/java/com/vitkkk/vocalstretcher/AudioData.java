package com.vitkkk.vocalstretcher;

final class AudioData {
    final int sampleRate;
    final int channels;
    final float[] samples;

    AudioData(int sampleRate, int channels, float[] samples) {
        if (sampleRate <= 0 || channels <= 0 || samples == null) {
            throw new IllegalArgumentException("Invalid audio data");
        }
        int usable = samples.length - (samples.length % channels);
        if (usable != samples.length) {
            float[] trimmed = new float[usable];
            System.arraycopy(samples, 0, trimmed, 0, usable);
            samples = trimmed;
        }
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.samples = samples;
    }

    int frameCount() {
        return samples.length / channels;
    }

    double durationSeconds() {
        return frameCount() / (double) sampleRate;
    }

    float monoAt(int frame) {
        if (frame < 0 || frame >= frameCount()) return 0f;
        int base = frame * channels;
        float sum = 0f;
        for (int c = 0; c < channels; c++) sum += samples[base + c];
        return sum / channels;
    }

    static AudioData reduceToAtMostStereo(int sampleRate, int channels, float[] input) {
        if (channels <= 2) return new AudioData(sampleRate, channels, input);
        int frames = input.length / channels;
        float[] stereo = new float[frames * 2];
        for (int f = 0; f < frames; f++) {
            int src = f * channels;
            float left = input[src];
            float right = input[src + 1];
            float extra = 0f;
            for (int c = 2; c < channels; c++) extra += input[src + c];
            extra /= Math.max(1, channels - 2);
            stereo[f * 2] = clamp(left * 0.8f + extra * 0.2f);
            stereo[f * 2 + 1] = clamp(right * 0.8f + extra * 0.2f);
        }
        return new AudioData(sampleRate, 2, stereo);
    }

    static float clamp(float v) {
        if (!Float.isFinite(v)) return 0f;
        return Math.max(-1f, Math.min(1f, v));
    }
}
