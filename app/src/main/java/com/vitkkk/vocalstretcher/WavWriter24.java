package com.vitkkk.vocalstretcher;

import java.io.IOException;
import java.io.OutputStream;

final class WavWriter24 {
    private WavWriter24() {}

    static void write(AudioData data, OutputStream out) throws IOException {
        long frames = data.frameCount();
        int channels = data.channels;
        int blockAlign = channels * 3;
        long dataBytes = frames * blockAlign;
        if (dataBytes > 0xffffffffL - 44) throw new IOException("WAV is too large for standard RIFF.");

        writeAscii(out, "RIFF");
        writeLE32(out, 36 + dataBytes);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeLE32(out, 16);
        writeLE16(out, 1);
        writeLE16(out, channels);
        writeLE32(out, data.sampleRate);
        writeLE32(out, (long) data.sampleRate * blockAlign);
        writeLE16(out, blockAlign);
        writeLE16(out, 24);
        writeAscii(out, "data");
        writeLE32(out, dataBytes);

        for (float sample : data.samples) {
            int v = Math.round(AudioData.clamp(sample) * 8388607f);
            out.write(v & 0xff);
            out.write((v >> 8) & 0xff);
            out.write((v >> 16) & 0xff);
        }
        out.flush();
    }

    private static void writeAscii(OutputStream out, String s) throws IOException {
        for (int i = 0; i < s.length(); i++) out.write((byte) s.charAt(i));
    }

    private static void writeLE16(OutputStream out, long v) throws IOException {
        out.write((int) (v & 0xff));
        out.write((int) ((v >> 8) & 0xff));
    }

    private static void writeLE32(OutputStream out, long v) throws IOException {
        out.write((int) (v & 0xff));
        out.write((int) ((v >> 8) & 0xff));
        out.write((int) ((v >> 16) & 0xff));
        out.write((int) ((v >> 24) & 0xff));
    }
}
