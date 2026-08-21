package com.vitkkk.vocalstretcher;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

final class AudioPlayer {
    interface Listener {
        void onProgress(int frame);
        void onComplete();
    }

    private volatile boolean stopRequested = false;
    private volatile boolean playing = false;
    private AudioTrack track;

    boolean isPlaying() {
        return playing;
    }

    synchronized void play(AudioData data, int startFrame, int endFrame, Listener listener) {
        stop();
        stopRequested = false;
        int channels = data.channels;
        int channelMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuffer = AudioTrack.getMinBufferSize(data.sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT);
        int bufferBytes = Math.max(minBuffer, 4096 * channels * 4);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(data.sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(channelMask)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build();
        track.play();
        playing = true;

        Thread thread = new Thread(() -> {
            int frame = clamp(startFrame, 0, data.frameCount());
            int end = clamp(endFrame, frame, data.frameCount());
            try {
                while (!stopRequested && frame < end) {
                    int frames = Math.min(2048, end - frame);
                    int written = track.write(data.samples, frame * channels, frames * channels, AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) break;
                    frame += written / channels;
                    if (listener != null) listener.onProgress(frame);
                }
            } catch (Exception ignored) {
            } finally {
                synchronized (AudioPlayer.this) {
                    try {
                        if (track != null) {
                            track.stop();
                            track.flush();
                            track.release();
                        }
                    } catch (Exception ignored) {}
                    track = null;
                    playing = false;
                }
                if (listener != null && !stopRequested) listener.onComplete();
            }
        }, "VocalStretcher-Playback");
        thread.start();
    }

    synchronized void stop() {
        stopRequested = true;
        playing = false;
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (Exception ignored) {}
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
