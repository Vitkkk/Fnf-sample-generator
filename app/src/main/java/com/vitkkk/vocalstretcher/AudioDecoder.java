package com.vitkkk.vocalstretcher;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class AudioDecoder {
    private AudioDecoder() {}

    static AudioData decode(Activity context, Uri uri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            int audioTrack = -1;
            MediaFormat trackFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    trackFormat = f;
                    break;
                }
            }
            if (audioTrack < 0 || trackFormat == null) throw new IOException("No audio track found.");
            String mime = trackFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IOException("Unknown audio format.");

            int sampleRate = trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

            extractor.selectTrack(audioTrack);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(trackFormat, null, null, 0);
            codec.start();

            FloatCollector collector = new FloatCollector();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inIndex);
                        if (input == null) throw new IOException("Decoder input buffer unavailable.");
                        input.clear();
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, Math.max(0, extractor.getSampleTime()), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = codec.getOutputFormat();
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                } else if (outIndex >= 0) {
                    ByteBuffer output = codec.getOutputBuffer(outIndex);
                    if (output != null && info.size > 0) {
                        ByteBuffer pcm = output.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                        pcm.position(info.offset);
                        pcm.limit(info.offset + info.size);
                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            while (pcm.remaining() >= 4) collector.add(AudioData.clamp(pcm.getFloat()));
                        } else if (pcmEncoding == AudioFormat.ENCODING_PCM_8BIT) {
                            while (pcm.hasRemaining()) collector.add(((pcm.get() & 0xff) - 128) / 128f);
                        } else {
                            while (pcm.remaining() >= 2) collector.add(pcm.getShort() / 32768f);
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIndex, false);
                    if (eos) outputDone = true;
                }
            }

            float[] samples = collector.toArray();
            if (samples.length == 0) throw new IOException("Decoder produced no PCM audio.");
            return AudioData.reduceToAtMostStereo(sampleRate, channels, samples);
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {}
            extractor.release();
        }
    }

    private static final class FloatCollector {
        private float[] data = new float[65536];
        private int size = 0;

        void add(float value) {
            if (size >= data.length) {
                float[] next = new float[data.length + data.length / 2];
                System.arraycopy(data, 0, next, 0, size);
                data = next;
            }
            data[size++] = value;
        }

        float[] toArray() {
            float[] out = new float[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }
}
