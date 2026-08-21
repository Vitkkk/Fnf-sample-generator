package com.vitkkk.vocalstretcher;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * v0.5.2 import path.
 *
 * The document URI is first copied to a normal local file. This avoids provider
 * quirks completely. Android MediaExtractor then gets one chance on that local
 * file; if it cannot demux it, miniaudio independently tries WAV/FLAC/MP3.
 * Errors include the real byte signature of the source file.
 */
final class AudioDecoder {
    private AudioDecoder() {}

    static AudioData decode(Activity context, Uri uri) throws Exception {
        File cached = copyToCache(context, uri);
        try {
            byte[] header = readHeader(cached, 32);
            String signature = describeSignature(header);

            Exception androidFailure = null;
            try {
                return decodeWithAndroid(cached.getAbsolutePath());
            } catch (Exception e) {
                androidFailure = e;
            }

            AudioData nativeDecoded = NativeAudioDecoder.decodeFile(cached.getAbsolutePath());
            if (nativeDecoded != null) return nativeDecoded;

            throw new IOException("Formato detectado: " + signature
                    + ". O MediaExtractor e o decoder nativo não conseguiram abrir esse arquivo. "
                    + "Android: " + shortMessage(androidFailure));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            cached.delete();
        }
    }

    private static AudioData decodeWithAndroid(String path) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(path);

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
            if (audioTrack < 0 || trackFormat == null) {
                throw new IOException("Nenhuma faixa de áudio encontrada.");
            }

            String mime = trackFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IOException("MIME de áudio ausente.");
            int sampleRate = trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

            extractor.selectTrack(audioTrack);

            if ("audio/raw".equals(mime)) {
                int encoding = trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ? trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        : AudioFormat.ENCODING_PCM_16BIT;
                return decodeRawExtractor(extractor, sampleRate, channels, encoding);
            }

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(trackFormat, null, null, 0);
            codec.start();

            FloatCollector collector = new FloatCollector();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            int idleLoops = 0;

            while (!outputDone) {
                boolean progressed = false;

                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        if (input == null) throw new IOException("Buffer de entrada indisponível.");
                        input.clear();
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size,
                                    Math.max(0, extractor.getSampleTime()), 0);
                            extractor.advance();
                        }
                        progressed = true;
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    progressed = true;
                } else if (outputIndex >= 0) {
                    ByteBuffer output = codec.getOutputBuffer(outputIndex);
                    if (output != null && info.size > 0) {
                        ByteBuffer pcm = output.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                        pcm.position(info.offset);
                        pcm.limit(info.offset + info.size);
                        appendPcm(collector, pcm, pcmEncoding);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);
                    if (eos) outputDone = true;
                    progressed = true;
                }

                if (progressed) idleLoops = 0;
                else if (++idleLoops > 500) throw new IOException("Decoder ficou sem produzir áudio.");
            }

            float[] samples = collector.toArray();
            if (samples.length == 0) throw new IOException("Decoder não produziu PCM.");
            return AudioData.reduceToAtMostStereo(sampleRate, channels, samples);
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {}
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    private static AudioData decodeRawExtractor(MediaExtractor extractor, int sampleRate,
                                                int channels, int encoding) throws IOException {
        FloatCollector collector = new FloatCollector();
        ByteBuffer buffer = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        while (true) {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            buffer.position(0);
            buffer.limit(size);
            appendPcm(collector, buffer, encoding);
            if (!extractor.advance()) break;
        }
        float[] samples = collector.toArray();
        if (samples.length == 0) throw new IOException("Faixa PCM vazia.");
        return AudioData.reduceToAtMostStereo(sampleRate, channels, samples);
    }

    private static void appendPcm(FloatCollector collector, ByteBuffer pcm, int encoding) {
        pcm.order(ByteOrder.LITTLE_ENDIAN);
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            while (pcm.remaining() >= 4) collector.add(AudioData.clamp(pcm.getFloat()));
        } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
            while (pcm.hasRemaining()) collector.add(((pcm.get() & 0xff) - 128) / 128f);
        } else if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
            while (pcm.remaining() >= 3) {
                int v = (pcm.get() & 0xff) | ((pcm.get() & 0xff) << 8) | ((pcm.get() & 0xff) << 16);
                if ((v & 0x800000) != 0) v |= 0xff000000;
                collector.add(v / 8388608f);
            }
        } else if (encoding == AudioFormat.ENCODING_PCM_32BIT) {
            while (pcm.remaining() >= 4) collector.add((float) (pcm.getInt() / 2147483648.0));
        } else {
            while (pcm.remaining() >= 2) collector.add(pcm.getShort() / 32768f);
        }
    }

    private static File copyToCache(Activity context, Uri uri) throws IOException {
        File outFile = File.createTempFile("vocal_import_", ".audio", context.getCacheDir());
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(outFile)) {
            if (in == null) throw new IOException("O Android não forneceu os bytes do arquivo.");
            byte[] buffer = new byte[128 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                out.write(buffer, 0, read);
                total += read;
            }
            out.flush();
            if (total == 0) throw new IOException("O arquivo selecionado tem 0 bytes.");
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
            throw e;
        }
        return outFile;
    }

    private static byte[] readHeader(File file, int max) throws IOException {
        byte[] bytes = new byte[max];
        int total = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (total < max) {
                int read = in.read(bytes, total, max - total);
                if (read < 0) break;
                if (read == 0) continue;
                total += read;
            }
        }
        if (total == max) return bytes;
        byte[] smaller = new byte[total];
        System.arraycopy(bytes, 0, smaller, 0, total);
        return smaller;
    }

    private static String describeSignature(byte[] h) {
        if (h.length == 0) return "arquivo vazio";
        if (h.length >= 12 && (ascii(h, 0, "RIFF") || ascii(h, 0, "RF64")) && ascii(h, 8, "WAVE")) return "WAV/RIFF";
        if (h.length >= 12 && ascii(h, 0, "FORM") && ascii(h, 8, "AIFF")) return "AIFF";
        if (h.length >= 12 && ascii(h, 0, "FORM") && ascii(h, 8, "AIFC")) return "AIFC";
        if (ascii(h, 0, "fLaC")) return "FLAC";
        if (ascii(h, 0, "OggS")) return "Ogg (Vorbis/Opus)";
        if (ascii(h, 0, "caff")) return "CAF/Core Audio";
        if (ascii(h, 0, "ID3")) return "MP3 com ID3";
        if (h.length >= 12 && ascii(h, 4, "ftyp")) return "MP4/M4A";
        if (ascii(h, 0, "ADIF")) return "AAC/ADIF";
        if (ascii(h, 0, "MThd")) return "MIDI (não é áudio renderizado)";
        if (h.length >= 2 && (h[0] & 0xff) == 0xff && ((h[1] & 0xe0) == 0xe0)) return "MPEG Audio/AAC";

        StringBuilder hex = new StringBuilder();
        StringBuilder text = new StringBuilder();
        int n = Math.min(16, h.length);
        for (int i = 0; i < n; i++) {
            if (i > 0) hex.append(' ');
            hex.append(String.format(Locale.US, "%02X", h[i] & 0xff));
            int v = h[i] & 0xff;
            text.append(v >= 32 && v <= 126 ? (char) v : '.');
        }
        return "desconhecido [ASCII=" + text + ", HEX=" + hex + "]";
    }

    private static boolean ascii(byte[] b, int offset, String s) {
        if (offset < 0 || offset + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) if (b[offset + i] != (byte) s.charAt(i)) return false;
        return true;
    }

    private static String shortMessage(Throwable t) {
        if (t == null) return "sem detalhe";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m.trim();
    }

    private static final class FloatCollector {
        private float[] data = new float[65536];
        private int size;

        void add(float v) {
            if (size >= data.length) {
                int nextLength = data.length + Math.max(1024, data.length / 2);
                float[] next = new float[nextLength];
                System.arraycopy(data, 0, next, 0, size);
                data = next;
            }
            data[size++] = v;
        }

        float[] toArray() {
            float[] out = new float[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }
}
