package com.vitkkk.vocalstretcher;

import android.app.Activity;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Last-resort import layer used by v0.5.2.
 *
 * Order:
 * 1) Existing Android/WAV decoder.
 * 2) Copy to a local cache file so the bytes are guaranteed seekable.
 * 3) Direct AIFF/AIFC PCM parser.
 * 4) Native miniaudio fallback (WAV/FLAC/MP3).
 * 5) Report the actual file signature if nothing can decode it.
 */
final class ResilientAudioDecoder {
    private ResilientAudioDecoder() {}

    static AudioData decode(Activity context, Uri uri) throws Exception {
        Exception androidFailure = null;
        try {
            return AudioDecoder.decode(context, uri);
        } catch (Exception e) {
            androidFailure = e;
        }

        File cached = null;
        try {
            cached = copyToCache(context, uri);
            byte[] header = readHeader(cached, 32);
            String signature = describeSignature(header);

            if (isAiff(header)) {
                try {
                    return decodeAiff(cached);
                } catch (Exception aiffFailure) {
                    AudioData nativeResult = NativeAudioDecoder.decodeFile(cached.getAbsolutePath());
                    if (nativeResult != null) return nativeResult;
                    throw new IOException("Arquivo detectado como " + signature
                            + ", mas o parser AIFF falhou: " + shortMessage(aiffFailure), aiffFailure);
                }
            }

            AudioData nativeResult = NativeAudioDecoder.decodeFile(cached.getAbsolutePath());
            if (nativeResult != null) return nativeResult;

            throw new IOException(
                    "Formato detectado: " + signature
                            + ". Android e decoder nativo não conseguiram abrir. "
                            + "Detalhe Android: " + shortMessage(androidFailure));
        } finally {
            if (cached != null && cached.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cached.delete();
            }
        }
    }

    private static File copyToCache(Activity context, Uri uri) throws IOException {
        File file = File.createTempFile("vocal_import_probe_", ".bin", context.getCacheDir());
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new IOException("O Android não forneceu os bytes do arquivo.");
            byte[] buffer = new byte[128 * 1024];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                out.write(buffer, 0, read);
                total += read;
            }
            out.flush();
            if (total == 0) throw new IOException("O arquivo está vazio (0 bytes).");
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw e;
        }
        return file;
    }

    private static byte[] readHeader(File file, int length) throws IOException {
        byte[] out = new byte[length];
        int total = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (total < out.length) {
                int read = in.read(out, total, out.length - total);
                if (read < 0) break;
                if (read == 0) continue;
                total += read;
            }
        }
        if (total == out.length) return out;
        byte[] trimmed = new byte[total];
        System.arraycopy(out, 0, trimmed, 0, total);
        return trimmed;
    }

    private static boolean isAiff(byte[] h) {
        return h.length >= 12 && ascii(h, 0, "FORM")
                && (ascii(h, 8, "AIFF") || ascii(h, 8, "AIFC"));
    }

    private static String describeSignature(byte[] h) {
        if (h.length == 0) return "arquivo vazio";
        if (h.length >= 12 && (ascii(h, 0, "RIFF") || ascii(h, 0, "RF64")) && ascii(h, 8, "WAVE")) {
            return "WAV/RIFF";
        }
        if (isAiff(h)) return ascii(h, 8, "AIFC") ? "AIFC" : "AIFF";
        if (ascii(h, 0, "fLaC")) return "FLAC";
        if (ascii(h, 0, "OggS")) return "Ogg (Vorbis/Opus)";
        if (ascii(h, 0, "caff")) return "CAF/Core Audio";
        if (ascii(h, 0, "ID3")) return "MP3 com ID3";
        if (h.length >= 2 && (h[0] & 0xff) == 0xff && ((h[1] & 0xe0) == 0xe0)) {
            int layerBits = (h[1] >> 1) & 0x03;
            if (layerBits != 0) return "MP3/MPEG Audio";
            return "AAC/ADTS ou MPEG Audio";
        }
        if (h.length >= 12 && ascii(h, 4, "ftyp")) return "MP4/M4A";
        if (ascii(h, 0, "ADIF")) return "AAC/ADIF";
        if (ascii(h, 0, "MThd")) return "MIDI (não é áudio PCM)";

        StringBuilder hex = new StringBuilder();
        int n = Math.min(16, h.length);
        for (int i = 0; i < n; i++) {
            if (i > 0) hex.append(' ');
            hex.append(String.format(Locale.US, "%02X", h[i] & 0xff));
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(12, h.length); i++) {
            int v = h[i] & 0xff;
            text.append(v >= 32 && v <= 126 ? (char) v : '.');
        }
        return "desconhecido [ASCII=" + text + ", HEX=" + hex + "]";
    }

    private static AudioData decodeAiff(File file) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
            byte[] form = new byte[12];
            require(readFully(in, form, 0, form.length), "AIFF truncado no cabeçalho.");
            require(ascii(form, 0, "FORM"), "Cabeçalho FORM ausente.");
            boolean aifc = ascii(form, 8, "AIFC");
            require(aifc || ascii(form, 8, "AIFF"), "FORM não contém AIFF/AIFC.");

            int channels = -1;
            int bits = -1;
            int sampleRate = -1;
            long declaredFrames = -1;
            String compression = aifc ? null : "NONE";

            while (true) {
                byte[] chunk = new byte[8];
                if (!readFully(in, chunk, 0, 8)) break;
                String id = new String(chunk, 0, 4, StandardCharsets.US_ASCII);
                long size = u32be(chunk, 4);
                if (size > Integer.MAX_VALUE && !"SSND".equals(id)) {
                    throw new IOException("Chunk AIFF grande demais: " + id);
                }

                if ("COMM".equals(id)) {
                    if (size < 18 || size > 1024 * 1024) throw new IOException("COMM AIFF inválido: " + size);
                    byte[] comm = new byte[(int) size];
                    require(readFully(in, comm, 0, comm.length), "COMM AIFF truncado.");
                    channels = u16be(comm, 0);
                    declaredFrames = u32be(comm, 2);
                    bits = u16be(comm, 6);
                    double sr = extended80(comm, 8);
                    if (!Double.isFinite(sr) || sr < 1000 || sr > 768000) {
                        throw new IOException("Sample rate AIFF inválido: " + sr);
                    }
                    sampleRate = (int) Math.round(sr);
                    if (aifc && comm.length >= 22) {
                        compression = new String(comm, 18, 4, StandardCharsets.US_ASCII);
                    }
                } else if ("SSND".equals(id)) {
                    require(channels > 0 && bits > 0 && sampleRate > 0, "SSND apareceu antes de COMM.");
                    require(size >= 8, "SSND AIFF inválido.");
                    byte[] soundHeader = new byte[8];
                    require(readFully(in, soundHeader, 0, 8), "SSND AIFF truncado.");
                    long offset = u32be(soundHeader, 0);
                    if (offset > size - 8) throw new IOException("Offset SSND inválido: " + offset);
                    skipFully(in, offset);
                    long audioBytes = size - 8 - offset;
                    return decodeAiffPcm(in, channels, sampleRate, bits, compression, audioBytes, declaredFrames);
                } else {
                    skipFully(in, size);
                }

                if ((size & 1L) != 0) skipFully(in, 1);
            }
            throw new IOException("AIFF sem chunk SSND.");
        }
    }

    private static AudioData decodeAiffPcm(InputStream in, int channels, int sampleRate, int bits,
                                           String compression, long audioBytes,
                                           long declaredFrames) throws Exception {
        if (channels <= 0 || channels > 32) throw new IOException("Canais AIFF inválidos: " + channels);
        if (!(bits == 8 || bits == 16 || bits == 24 || bits == 32 || bits == 64)) {
            throw new IOException("AIFF de " + bits + " bits não suportado.");
        }

        String comp = compression == null ? "NONE" : compression;
        boolean little = "sowt".equals(comp);
        boolean float32 = "fl32".equalsIgnoreCase(comp) || "FL32".equals(comp);
        boolean float64 = "fl64".equalsIgnoreCase(comp) || "FL64".equals(comp);
        boolean integer = "NONE".equals(comp) || "twos".equals(comp) || "sowt".equals(comp);
        if (!integer && !float32 && !float64) {
            throw new IOException("Compressão AIFC não suportada: '" + comp + "'.");
        }
        if (float32 && bits != 32) throw new IOException("AIFC fl32 com bits=" + bits);
        if (float64 && bits != 64) throw new IOException("AIFC fl64 com bits=" + bits);

        int bytesPerSample = bits / 8;
        long samplesByBytes = audioBytes / Math.max(1, bytesPerSample);
        long maxSamples = 80L * 1000L * 1000L;
        if (samplesByBytes > maxSamples) throw new IOException("AIFF grande demais para editar no celular.");

        int expected = (int) Math.min(Integer.MAX_VALUE,
                Math.min(samplesByBytes, Math.max(0, declaredFrames) * Math.max(1, channels)));
        FloatCollector collector = new FloatCollector(Math.max(65536, Math.min(expected, 2_000_000)));
        byte[] b = new byte[Math.max(8, bytesPerSample)];
        long remain = audioBytes;

        while (remain >= bytesPerSample) {
            if (!readFully(in, b, 0, bytesPerSample)) break;
            remain -= bytesPerSample;
            float sample;
            if (float32) {
                int raw = little ? i32le(b, 0) : i32be(b, 0);
                float f = Float.intBitsToFloat(raw);
                sample = Float.isFinite(f) ? f : 0f;
            } else if (float64) {
                long raw = little ? i64le(b, 0) : i64be(b, 0);
                double d = Double.longBitsToDouble(raw);
                sample = Double.isFinite(d) ? (float) d : 0f;
            } else if (bits == 8) {
                sample = b[0] / 128f; // AIFF 8-bit PCM is signed.
            } else if (bits == 16) {
                int v = little ? ((b[0] & 0xff) | (b[1] << 8))
                        : ((b[1] & 0xff) | (b[0] << 8));
                sample = (short) v / 32768f;
            } else if (bits == 24) {
                int v;
                if (little) {
                    v = (b[0] & 0xff) | ((b[1] & 0xff) << 8) | ((b[2] & 0xff) << 16);
                } else {
                    v = (b[2] & 0xff) | ((b[1] & 0xff) << 8) | ((b[0] & 0xff) << 16);
                }
                if ((v & 0x800000) != 0) v |= 0xff000000;
                sample = v / 8388608f;
            } else {
                int v = little ? i32le(b, 0) : i32be(b, 0);
                sample = (float) (v / 2147483648.0);
            }
            collector.add(AudioData.clamp(sample));
        }

        float[] samples = collector.toArray();
        if (samples.length == 0) throw new IOException("AIFF não contém PCM.");
        int usable = samples.length - samples.length % channels;
        if (usable != samples.length) {
            float[] trimmed = new float[usable];
            System.arraycopy(samples, 0, trimmed, 0, usable);
            samples = trimmed;
        }
        return AudioData.reduceToAtMostStereo(sampleRate, channels, samples);
    }

    private static double extended80(byte[] b, int o) {
        int se = ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
        boolean negative = (se & 0x8000) != 0;
        int exponent = se & 0x7fff;
        if (exponent == 0 && allZero(b, o + 2, 8)) return 0.0;
        if (exponent == 0x7fff) return Double.NaN;

        long hi = u32be(b, o + 2);
        long lo = u32be(b, o + 6);
        double mantissa = hi * 4294967296.0 + lo;
        double value = Math.scalb(mantissa, exponent - 16383 - 63);
        return negative ? -value : value;
    }

    private static boolean allZero(byte[] b, int o, int n) {
        for (int i = 0; i < n; i++) if (b[o + i] != 0) return false;
        return true;
    }

    private static boolean ascii(byte[] b, int o, String s) {
        if (o < 0 || o + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) {
            if (b[o + i] != (byte) s.charAt(i)) return false;
        }
        return true;
    }

    private static int u16be(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }

    private static long u32be(byte[] b, int o) {
        return ((long) (b[o] & 0xff) << 24)
                | ((long) (b[o + 1] & 0xff) << 16)
                | ((long) (b[o + 2] & 0xff) << 8)
                | (long) (b[o + 3] & 0xff);
    }

    private static int i32be(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
                | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static int i32le(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8)
                | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static long i64be(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[o + i] & 0xffL);
        return v;
    }

    private static long i64le(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xffL);
        return v;
    }

    private static boolean readFully(InputStream in, byte[] b, int o, int n) throws IOException {
        int done = 0;
        while (done < n) {
            int read = in.read(b, o + done, n - done);
            if (read < 0) return false;
            if (read == 0) continue;
            done += read;
        }
        return true;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long remain = n;
        byte[] scratch = null;
        while (remain > 0) {
            long skipped = in.skip(remain);
            if (skipped > 0) {
                remain -= skipped;
                continue;
            }
            if (scratch == null) scratch = new byte[8192];
            int read = in.read(scratch, 0, (int) Math.min(scratch.length, remain));
            if (read < 0) throw new IOException("Arquivo truncado.");
            remain -= read;
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static String shortMessage(Throwable t) {
        if (t == null) return "sem detalhe";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m.trim();
    }

    private static final class FloatCollector {
        private float[] data;
        private int size;

        FloatCollector(int initial) {
            data = new float[Math.max(1024, initial)];
        }

        void add(float v) {
            if (size >= data.length) {
                int next = data.length + Math.max(1024, data.length / 2);
                float[] expanded = new float[next];
                System.arraycopy(data, 0, expanded, 0, size);
                data = expanded;
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
