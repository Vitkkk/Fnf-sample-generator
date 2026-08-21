package com.vitkkk.vocalstretcher;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Resilient audio decoder.
 *
 * Import order:
 * 1) Own RIFF/WAVE parser (PCM 8/16/24/32, float 32/64, extensible WAV)
 * 2) Android MediaExtractor using the Content URI
 * 3) MediaExtractor using a raw FileDescriptor
 * 4) Copy source to app cache and let MediaExtractor open a normal file path
 *
 * The extra paths matter because some Android document providers expose URIs
 * that MediaExtractor cannot instantiate directly even though the bytes are a
 * perfectly valid audio file.
 */
final class AudioDecoder {
    private AudioDecoder() {}

    static AudioData decode(Activity context, Uri uri) throws Exception {
        Exception wavFailure = null;
        try {
            AudioData wav = tryDecodeWav(context, uri);
            if (wav != null) return wav;
        } catch (Exception e) {
            // A file that really looks like WAV should report this only after the
            // generic Android paths have also had a chance to open it.
            wavFailure = e;
        }

        Exception uriFailure = null;
        try {
            return decodeWithExtractor(context, uri, ExtractorSource.URI);
        } catch (Exception e) {
            uriFailure = e;
        }

        Exception fdFailure = null;
        try {
            return decodeWithExtractor(context, uri, ExtractorSource.FILE_DESCRIPTOR);
        } catch (Exception e) {
            fdFailure = e;
        }

        File cached = null;
        try {
            cached = copyToCache(context, uri);
            return decodeWithPath(cached.getAbsolutePath());
        } catch (Exception cacheFailure) {
            StringBuilder message = new StringBuilder("Não foi possível abrir este áudio.");
            if (wavFailure != null) {
                message.append(" WAV: ").append(shortMessage(wavFailure)).append('.');
            }
            if (uriFailure != null) {
                message.append(" URI: ").append(shortMessage(uriFailure)).append('.');
            }
            if (fdFailure != null) {
                message.append(" FD: ").append(shortMessage(fdFailure)).append('.');
            }
            message.append(" Cache: ").append(shortMessage(cacheFailure)).append('.');
            throw new IOException(message.toString(), cacheFailure);
        } finally {
            if (cached != null && cached.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cached.delete();
            }
        }
    }

    private enum ExtractorSource { URI, FILE_DESCRIPTOR }

    private static AudioData decodeWithExtractor(Activity context, Uri uri,
                                                 ExtractorSource source) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        ParcelFileDescriptor pfd = null;
        try {
            if (source == ExtractorSource.URI) {
                extractor.setDataSource(context, uri, null);
            } else {
                pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) throw new IOException("Android não forneceu FileDescriptor para o arquivo.");
                extractor.setDataSource(pfd.getFileDescriptor());
            }
            return decodeExtractor(extractor);
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static AudioData decodeWithPath(String path) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(path);
            return decodeExtractor(extractor);
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    private static AudioData decodeExtractor(MediaExtractor extractor) throws Exception {
        MediaCodec codec = null;
        try {
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
            if (mime == null) throw new IOException("Formato de áudio desconhecido.");

            int sampleRate = trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

            // Some extractors expose PCM/WAV directly as audio/raw. In this case
            // there is no codec to instantiate; consume extractor samples as PCM.
            if ("audio/raw".equals(mime)) {
                int encoding = trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ? trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        : AudioFormat.ENCODING_PCM_16BIT;
                extractor.selectTrack(audioTrack);
                return decodeRawExtractor(extractor, sampleRate, channels, encoding);
            }

            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            extractor.selectTrack(audioTrack);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(trackFormat, null, null, 0);
            codec.start();

            FloatCollector collector = new FloatCollector();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int idleLoops = 0;

            while (!outputDone) {
                boolean progressed = false;
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inIndex);
                        if (input == null) throw new IOException("Buffer de entrada do decoder indisponível.");
                        input.clear();
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size,
                                    Math.max(0, extractor.getSampleTime()), 0);
                            extractor.advance();
                        }
                        progressed = true;
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
                    progressed = true;
                } else if (outIndex >= 0) {
                    ByteBuffer output = codec.getOutputBuffer(outIndex);
                    if (output != null && info.size > 0) {
                        ByteBuffer pcm = output.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                        pcm.position(info.offset);
                        pcm.limit(info.offset + info.size);
                        appendPcm(collector, pcm, pcmEncoding);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIndex, false);
                    if (eos) outputDone = true;
                    progressed = true;
                }

                if (progressed) idleLoops = 0;
                else if (++idleLoops > 500) throw new IOException("Decoder travou sem produzir áudio.");
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
            while (pcm.remaining() >= 4) collector.add(pcm.getInt() / 2147483648f);
        } else {
            while (pcm.remaining() >= 2) collector.add(pcm.getShort() / 32768f);
        }
    }

    /** Returns null when the file is not a RIFF/WAVE file. */
    private static AudioData tryDecodeWav(Activity context, Uri uri) throws Exception {
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("Não foi possível abrir o stream do arquivo.");
            BufferedInputStream in = new BufferedInputStream(raw, 64 * 1024);

            byte[] riff = new byte[12];
            if (!readFully(in, riff, 0, 12)) return null;
            boolean isRiff = asciiEquals(riff, 0, "RIFF") || asciiEquals(riff, 0, "RF64");
            if (!isRiff || !asciiEquals(riff, 8, "WAVE")) return null;

            int formatTag = -1;
            int channels = -1;
            int sampleRate = -1;
            int bits = -1;
            int validBits = -1;
            int blockAlign = -1;
            long dataSize = -1;

            while (true) {
                byte[] chunkHeader = new byte[8];
                if (!readFully(in, chunkHeader, 0, 8)) break;
                String id = new String(chunkHeader, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                long chunkSize = u32le(chunkHeader, 4);

                if ("fmt ".equals(id)) {
                    if (chunkSize < 16 || chunkSize > 1024 * 1024) {
                        throw new IOException("Chunk fmt WAV inválido: " + chunkSize);
                    }
                    byte[] fmt = new byte[(int) chunkSize];
                    if (!readFully(in, fmt, 0, fmt.length)) throw new IOException("WAV fmt truncado.");
                    formatTag = u16le(fmt, 0);
                    channels = u16le(fmt, 2);
                    sampleRate = (int) u32le(fmt, 4);
                    blockAlign = u16le(fmt, 12);
                    bits = u16le(fmt, 14);
                    validBits = bits;

                    // WAVE_FORMAT_EXTENSIBLE: actual sub-format GUID starts at byte 24.
                    if (formatTag == 0xfffe && fmt.length >= 40) {
                        validBits = u16le(fmt, 18);
                        int subFormat = u16le(fmt, 24);
                        if (subFormat == 1 || subFormat == 3) formatTag = subFormat;
                    }
                } else if ("data".equals(id)) {
                    if (formatTag < 0) throw new IOException("WAV data apareceu antes de fmt.");
                    dataSize = chunkSize;
                    return decodeWavData(in, formatTag, channels, sampleRate, bits,
                            validBits, blockAlign, dataSize);
                } else {
                    skipFully(in, chunkSize);
                }

                // RIFF chunks are word-aligned.
                if ((chunkSize & 1L) != 0) skipFully(in, 1);
            }
            throw new IOException("WAV sem chunk data.");
        }
    }

    private static AudioData decodeWavData(InputStream in, int formatTag, int channels,
                                           int sampleRate, int bits, int validBits,
                                           int blockAlign, long dataSize) throws Exception {
        if (channels <= 0 || channels > 32) throw new IOException("Número de canais WAV inválido: " + channels);
        if (sampleRate < 1000 || sampleRate > 768000) throw new IOException("Sample rate WAV inválido: " + sampleRate);
        if (blockAlign <= 0) blockAlign = channels * Math.max(1, (bits + 7) / 8);
        if (dataSize < 0) throw new IOException("Tamanho WAV inválido.");

        boolean pcm = formatTag == 1;
        boolean ieeeFloat = formatTag == 3;
        if (!pcm && !ieeeFloat) {
            throw new IOException("WAV comprimido/formato " + formatTag + " não suportado pelo parser direto.");
        }
        if (pcm && !(bits == 8 || bits == 16 || bits == 24 || bits == 32)) {
            throw new IOException("PCM WAV de " + bits + " bits não suportado.");
        }
        if (ieeeFloat && !(bits == 32 || bits == 64)) {
            throw new IOException("Float WAV de " + bits + " bits não suportado.");
        }

        int bytesPerSample = bits / 8;
        long framesLong = dataSize / Math.max(1, blockAlign);
        if (framesLong > Integer.MAX_VALUE / Math.max(1, channels)) {
            throw new IOException("WAV grande demais para editar no celular.");
        }

        int expectedSamples = (int) framesLong * channels;
        FloatCollector collector = new FloatCollector(Math.max(65536, Math.min(expectedSamples, 2_000_000)));
        byte[] sampleBytes = new byte[Math.max(8, bytesPerSample)];
        long bytesRemaining = dataSize;

        while (bytesRemaining >= bytesPerSample) {
            if (!readFully(in, sampleBytes, 0, bytesPerSample)) break;
            bytesRemaining -= bytesPerSample;
            float sample;
            if (ieeeFloat) {
                if (bits == 32) {
                    int raw = (sampleBytes[0] & 0xff)
                            | ((sampleBytes[1] & 0xff) << 8)
                            | ((sampleBytes[2] & 0xff) << 16)
                            | ((sampleBytes[3] & 0xff) << 24);
                    sample = AudioData.clamp(Float.intBitsToFloat(raw));
                } else {
                    long raw = 0;
                    for (int i = 0; i < 8; i++) raw |= ((long) sampleBytes[i] & 0xffL) << (8 * i);
                    double d = Double.longBitsToDouble(raw);
                    sample = AudioData.clamp(Double.isFinite(d) ? (float) d : 0f);
                }
            } else if (bits == 8) {
                sample = ((sampleBytes[0] & 0xff) - 128) / 128f;
            } else if (bits == 16) {
                int v = (sampleBytes[0] & 0xff) | (sampleBytes[1] << 8);
                sample = (short) v / 32768f;
            } else if (bits == 24) {
                int v = (sampleBytes[0] & 0xff)
                        | ((sampleBytes[1] & 0xff) << 8)
                        | ((sampleBytes[2] & 0xff) << 16);
                if ((v & 0x800000) != 0) v |= 0xff000000;
                sample = v / 8388608f;
            } else {
                int v = (sampleBytes[0] & 0xff)
                        | ((sampleBytes[1] & 0xff) << 8)
                        | ((sampleBytes[2] & 0xff) << 16)
                        | (sampleBytes[3] << 24);
                // If extensible validBits is smaller, scaling by 32-bit full scale
                // remains correct for the usual left-aligned WAVE extensible PCM.
                sample = (float) (v / 2147483648.0);
            }
            collector.add(AudioData.clamp(sample));
        }

        float[] samples = collector.toArray();
        if (samples.length == 0) throw new IOException("WAV não contém amostras de áudio.");
        int usable = samples.length - samples.length % channels;
        if (usable != samples.length) {
            float[] trimmed = new float[usable];
            System.arraycopy(samples, 0, trimmed, 0, usable);
            samples = trimmed;
        }
        return AudioData.reduceToAtMostStereo(sampleRate, channels, samples);
    }

    private static File copyToCache(Activity context, Uri uri) throws IOException {
        File file = File.createTempFile("vocal_import_", ".audio", context.getCacheDir());
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new IOException("Não foi possível abrir o arquivo para cache.");
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) out.write(buffer, 0, read);
            }
            out.flush();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw e;
        }
        return file;
    }

    private static boolean readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int read = in.read(buffer, offset + done, length - done);
            if (read < 0) return false;
            if (read == 0) continue;
            done += read;
        }
        return true;
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        byte[] scratch = null;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (scratch == null) scratch = new byte[8192];
            int read = in.read(scratch, 0, (int) Math.min(scratch.length, remaining));
            if (read < 0) throw new IOException("Arquivo truncado ao pular chunk WAV.");
            remaining -= read;
        }
    }

    private static boolean asciiEquals(byte[] b, int offset, String text) {
        if (offset < 0 || offset + text.length() > b.length) return false;
        for (int i = 0; i < text.length(); i++) {
            if ((byte) text.charAt(i) != b[offset + i]) return false;
        }
        return true;
    }

    private static int u16le(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static long u32le(byte[] b, int o) {
        return ((long) b[o] & 0xffL)
                | (((long) b[o + 1] & 0xffL) << 8)
                | (((long) b[o + 2] & 0xffL) << 16)
                | (((long) b[o + 3] & 0xffL) << 24);
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m.trim();
    }

    private static final class FloatCollector {
        private float[] data;
        private int size = 0;

        FloatCollector() { this(65536); }

        FloatCollector(int initialCapacity) {
            data = new float[Math.max(1024, initialCapacity)];
        }

        void add(float value) {
            if (size >= data.length) {
                int nextSize = Math.max(size + 1, data.length + Math.max(1024, data.length / 2));
                float[] next = new float[nextSize];
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
