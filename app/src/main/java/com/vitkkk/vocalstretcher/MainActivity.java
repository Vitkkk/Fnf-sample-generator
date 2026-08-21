package com.vitkkk.vocalstretcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_IMPORT_AUDIO = 1001;
    private static final int REQ_EXPORT_FOLDER = 1002;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WaveformView waveformView;
    private TextView timeLabel;
    private TextView selectionLabel;
    private TextView statusLabel;
    private Button importButton;
    private Button playButton;
    private Button cutButton;
    private Button previewButton;
    private Button applyButton;
    private Button exportButton;
    private EditText durationInput;

    private AudioData audio;
    private AudioData previewAudio;
    private int playheadFrame = 0;
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private boolean waitingForSecondCut = false;
    private boolean busy = false;
    private int previewTargetFrames = -1;
    private AudioData previewBaseAudio;
    private final Player player = new Player();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        player.stop();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Vocal Stretcher");
        title.setTextSize(26f);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        importButton = new Button(this);
        importButton.setText("Import audio");
        importButton.setOnClickListener(v -> chooseAudio());
        root.addView(importButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        waveformView = new WaveformView(this);
        waveformView.setOnPlayheadChangedListener(frame -> {
            playheadFrame = frame;
            updateTimeLabel();
        });
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        waveParams.topMargin = dp(12);
        waveParams.bottomMargin = dp(8);
        root.addView(waveformView, waveParams);

        TextView gestureHint = new TextView(this);
        gestureHint.setText("1 finger: scrub  •  2 fingers: zoom / pan");
        gestureHint.setTextColor(Color.DKGRAY);
        gestureHint.setTextSize(12f);
        gestureHint.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(gestureHint);

        timeLabel = new TextView(this);
        timeLabel.setText("00:00.000 / 00:00.000");
        timeLabel.setTextSize(15f);
        timeLabel.setTextColor(Color.BLACK);
        timeLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        timeLabel.setPadding(0, dp(4), 0, dp(8));
        root.addView(timeLabel);

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        playButton = new Button(this);
        playButton.setText("Play");
        playButton.setOnClickListener(v -> togglePlayback());
        cutButton = new Button(this);
        cutButton.setText("Cut start");
        cutButton.setOnClickListener(v -> markCut());
        transport.addView(playButton, weighted());
        transport.addView(cutButton, weighted());
        root.addView(transport, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        selectionLabel = new TextView(this);
        selectionLabel.setText("No region selected");
        selectionLabel.setTextColor(Color.DKGRAY);
        selectionLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        selectionLabel.setPadding(0, dp(6), 0, dp(4));
        root.addView(selectionLabel);

        LinearLayout durationRow = new LinearLayout(this);
        durationRow.setOrientation(LinearLayout.HORIZONTAL);
        durationRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView durationTitle = new TextView(this);
        durationTitle.setText("Target duration: ");
        durationTitle.setTextColor(Color.BLACK);
        durationInput = new EditText(this);
        durationInput.setSingleLine(true);
        durationInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        durationInput.setHint("1.500");
        TextView seconds = new TextView(this);
        seconds.setText(" s");
        seconds.setTextColor(Color.BLACK);
        durationRow.addView(durationTitle);
        durationRow.addView(durationInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        durationRow.addView(seconds);
        root.addView(durationRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout processRow = new LinearLayout(this);
        processRow.setOrientation(LinearLayout.HORIZONTAL);
        previewButton = new Button(this);
        previewButton.setText("Preview");
        previewButton.setOnClickListener(v -> previewStretch());
        applyButton = new Button(this);
        applyButton.setText("Apply stretch");
        applyButton.setOnClickListener(v -> applyStretch());
        processRow.addView(previewButton, weighted());
        processRow.addView(applyButton, weighted());
        root.addView(processRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        exportButton = new Button(this);
        exportButton.setText("Export WAV");
        exportButton.setOnClickListener(v -> chooseExportFolder());
        root.addView(exportButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusLabel = new TextView(this);
        statusLabel.setText("Import an audio file to begin.");
        statusLabel.setTextColor(Color.DKGRAY);
        statusLabel.setTextSize(12f);
        statusLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        statusLabel.setPadding(0, dp(6), 0, 0);
        root.addView(statusLabel);

        setContentView(root);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void chooseAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_IMPORT_AUDIO);
    }

    private void chooseExportFolder() {
        if (audio == null || busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_EXPORT_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_AUDIO) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            importAudio(uri);
        } else if (requestCode == REQ_EXPORT_FOLDER) {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {
            }
            exportToFolder(uri);
        }
    }

    private void importAudio(Uri uri) {
        player.stop();
        setBusy(true, "Decoding audio…");
        worker.execute(() -> {
            try {
                AudioData decoded = AudioDecoder.decode(this, uri);
                mainHandler.post(() -> {
                    audio = decoded;
                    previewAudio = null;
                    previewBaseAudio = null;
                    previewTargetFrames = -1;
                    selectionStart = -1;
                    selectionEnd = -1;
                    waitingForSecondCut = false;
                    playheadFrame = 0;
                    waveformView.setAudio(decoded);
                    waveformView.setSelection(-1, -1);
                    waveformView.setPlayhead(0);
                    cutButton.setText("Cut start");
                    setBusy(false, String.format(Locale.US,
                            "Loaded %.2f s • %d Hz • %d channel%s",
                            decoded.durationSeconds(), decoded.sampleRate, decoded.channels,
                            decoded.channels == 1 ? "" : "s"));
                    refreshUi();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, "Import failed.");
                    showError("Could not decode this audio file", e);
                });
            }
        });
    }

    private void togglePlayback() {
        if (audio == null || busy) return;
        if (player.isPlaying()) {
            player.stop();
            playButton.setText("Play");
            return;
        }
        int start = clamp(playheadFrame, 0, Math.max(0, audio.frameCount() - 1));
        playButton.setText("Stop");
        player.play(audio, start, audio.frameCount(), new Player.Listener() {
            @Override
            public void onProgress(int frame) {
                mainHandler.post(() -> {
                    playheadFrame = clamp(frame, 0, audio.frameCount());
                    waveformView.setPlayhead(playheadFrame);
                    updateTimeLabel();
                });
            }

            @Override
            public void onComplete() {
                mainHandler.post(() -> playButton.setText("Play"));
            }
        });
    }

    private void markCut() {
        if (audio == null || busy) return;
        player.stop();
        playButton.setText("Play");

        if (!waitingForSecondCut) {
            selectionStart = clamp(playheadFrame, 0, audio.frameCount() - 1);
            selectionEnd = -1;
            waitingForSecondCut = true;
            waveformView.setSelection(selectionStart, -1);
            cutButton.setText("Cut end");
            statusLabel.setText("Move the playhead to the end of the vowel/region, then tap Cut end.");
        } else {
            selectionEnd = clamp(playheadFrame, 0, audio.frameCount());
            if (selectionEnd < selectionStart) {
                int t = selectionStart;
                selectionStart = selectionEnd;
                selectionEnd = t;
            }
            int minimum = Math.max(32, audio.sampleRate / 100);
            if (selectionEnd - selectionStart < minimum) {
                Toast.makeText(this, "Selection is too short. Choose at least about 10 ms.", Toast.LENGTH_LONG).show();
                selectionEnd = -1;
                waveformView.setSelection(selectionStart, -1);
                return;
            }
            waitingForSecondCut = false;
            waveformView.setSelection(selectionStart, selectionEnd);
            cutButton.setText("Cut start");
            double original = (selectionEnd - selectionStart) / (double) audio.sampleRate;
            double suggested = Math.max(1.0, original * 2.0);
            durationInput.setText(String.format(Locale.US, "%.3f", suggested));
            previewAudio = null;
            previewBaseAudio = null;
            previewTargetFrames = -1;
            statusLabel.setText("Region selected. Choose the new duration, Preview, then Apply stretch.");
        }
        refreshUi();
    }

    private int readTargetFrames() {
        if (audio == null || selectionStart < 0 || selectionEnd <= selectionStart) return -1;
        String text = durationInput.getText().toString().trim().replace(',', '.');
        if (text.isEmpty()) return -1;
        try {
            double seconds = Double.parseDouble(text);
            if (!Double.isFinite(seconds) || seconds <= 0.0 || seconds > 120.0) return -1;
            long frames = Math.round(seconds * audio.sampleRate);
            if (frames > Integer.MAX_VALUE) return -1;
            return (int) frames;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean validateStretch(int targetFrames) {
        if (targetFrames <= 0) {
            Toast.makeText(this, "Enter a valid duration (maximum 120 seconds).", Toast.LENGTH_LONG).show();
            return false;
        }
        int originalFrames = selectionEnd - selectionStart;
        if (targetFrames < originalFrames) {
            Toast.makeText(this,
                    "This first version is for sustaining/lengthening. Target duration must be at least the selected duration.",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void previewStretch() {
        if (audio == null || busy || selectionStart < 0 || selectionEnd <= selectionStart) return;
        int targetFrames = readTargetFrames();
        if (!validateStretch(targetFrames)) return;
        player.stop();
        playButton.setText("Play");
        setBusy(true, "Synthesizing preview…");
        AudioData base = audio;
        int start = selectionStart;
        int end = selectionEnd;
        worker.execute(() -> {
            try {
                AudioData stretched = StretchEngine.stretchRegion(base, start, end, targetFrames);
                mainHandler.post(() -> {
                    previewAudio = stretched;
                    previewBaseAudio = base;
                    previewTargetFrames = targetFrames;
                    setBusy(false, "Preview ready. Listening around the edited region.");
                    int before = Math.max(0, start - base.sampleRate / 3);
                    int after = Math.min(stretched.frameCount(), start + targetFrames + base.sampleRate / 2);
                    player.play(stretched, before, after, new Player.Listener() {
                        @Override
                        public void onProgress(int frame) {
                            // Preview intentionally does not move the editor's original playhead.
                        }

                        @Override
                        public void onComplete() {
                        }
                    });
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, "Preview failed.");
                    showError("Could not synthesize this region", e);
                });
            }
        });
    }

    private void applyStretch() {
        if (audio == null || busy || selectionStart < 0 || selectionEnd <= selectionStart) return;
        int targetFrames = readTargetFrames();
        if (!validateStretch(targetFrames)) return;
        player.stop();
        playButton.setText("Play");

        if (previewAudio != null && previewBaseAudio == audio && previewTargetFrames == targetFrames) {
            applyProcessedAudio(previewAudio, targetFrames);
            return;
        }

        setBusy(true, "Synthesizing and applying stretch…");
        AudioData base = audio;
        int start = selectionStart;
        int end = selectionEnd;
        worker.execute(() -> {
            try {
                AudioData stretched = StretchEngine.stretchRegion(base, start, end, targetFrames);
                mainHandler.post(() -> {
                    setBusy(false, "Stretch applied.");
                    applyProcessedAudio(stretched, targetFrames);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, "Stretch failed.");
                    showError("Could not apply the stretch", e);
                });
            }
        });
    }

    private void applyProcessedAudio(AudioData stretched, int targetFrames) {
        audio = stretched;
        selectionEnd = selectionStart + targetFrames;
        playheadFrame = selectionStart;
        waveformView.setAudio(audio);
        waveformView.setSelection(selectionStart, selectionEnd);
        waveformView.setPlayhead(playheadFrame);
        previewAudio = null;
        previewBaseAudio = null;
        previewTargetFrames = -1;
        waitingForSecondCut = false;
        cutButton.setText("Cut start");
        statusLabel.setText("Stretch applied. You can select another region or export the WAV.");
        refreshUi();
    }

    private void exportToFolder(Uri treeUri) {
        if (audio == null || busy) return;
        setBusy(true, "Exporting 24-bit WAV…");
        AudioData toWrite = audio;
        worker.execute(() -> {
            try {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String fileName = "VocalStretcher_" + stamp + ".wav";
                ContentResolver resolver = getContentResolver();
                String treeId = DocumentsContract.getTreeDocumentId(treeUri);
                Uri directoryUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
                Uri outUri = DocumentsContract.createDocument(resolver, directoryUri, "audio/wav", fileName);
                if (outUri == null) throw new IOException("Android could not create the output file in this folder.");
                try (OutputStream raw = resolver.openOutputStream(outUri, "w");
                     BufferedOutputStream out = new BufferedOutputStream(raw, 64 * 1024)) {
                    if (raw == null) throw new IOException("Could not open the output file.");
                    WavWriter.write24Bit(toWrite, out);
                }
                mainHandler.post(() -> {
                    setBusy(false, "Saved " + fileName);
                    Toast.makeText(this, "Saved: " + fileName, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, "Export failed.");
                    showError("Could not export the WAV", e);
                });
            }
        });
    }

    private void setBusy(boolean isBusy, String message) {
        busy = isBusy;
        statusLabel.setText(message);
        refreshUi();
    }

    private void refreshUi() {
        boolean hasAudio = audio != null;
        boolean hasSelection = hasAudio && !waitingForSecondCut && selectionStart >= 0 && selectionEnd > selectionStart;
        importButton.setEnabled(!busy);
        playButton.setEnabled(hasAudio && !busy);
        cutButton.setEnabled(hasAudio && !busy);
        previewButton.setEnabled(hasSelection && !busy);
        applyButton.setEnabled(hasSelection && !busy);
        durationInput.setEnabled(hasSelection && !busy);
        exportButton.setEnabled(hasAudio && !busy);
        updateTimeLabel();
        updateSelectionLabel();
    }

    private void updateTimeLabel() {
        if (audio == null) {
            timeLabel.setText("00:00.000 / 00:00.000");
            return;
        }
        timeLabel.setText(formatTime(playheadFrame, audio.sampleRate) + " / "
                + formatTime(audio.frameCount(), audio.sampleRate));
    }

    private void updateSelectionLabel() {
        if (audio == null || selectionStart < 0) {
            selectionLabel.setText("No region selected");
        } else if (waitingForSecondCut || selectionEnd <= selectionStart) {
            selectionLabel.setText("Start: " + formatTime(selectionStart, audio.sampleRate) + " • choose end");
        } else {
            double duration = (selectionEnd - selectionStart) / (double) audio.sampleRate;
            selectionLabel.setText(String.format(Locale.US,
                    "Selected %s → %s  (%.3f s)",
                    formatTime(selectionStart, audio.sampleRate),
                    formatTime(selectionEnd, audio.sampleRate), duration));
        }
    }

    private static String formatTime(int frame, int sampleRate) {
        if (sampleRate <= 0) return "00:00.000";
        long ms = Math.round(frame * 1000.0 / sampleRate);
        long minutes = ms / 60000;
        long seconds = (ms / 1000) % 60;
        long millis = ms % 1000;
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis);
    }

    private void showError(String title, Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class AudioData {
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
                stereo[f * 2] = clampSample(left * 0.8f + extra * 0.2f);
                stereo[f * 2 + 1] = clampSample(right * 0.8f + extra * 0.2f);
            }
            return new AudioData(sampleRate, 2, stereo);
        }
    }

    private static final class FloatCollector {
        private float[] data = new float[65536];
        private int size = 0;

        void add(float value) {
            ensure(size + 1);
            data[size++] = value;
        }

        private void ensure(int needed) {
            if (needed <= data.length) return;
            int next = Math.max(needed, data.length + data.length / 2);
            float[] expanded = new float[next];
            System.arraycopy(data, 0, expanded, 0, size);
            data = expanded;
        }

        float[] toArray() {
            float[] out = new float[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }

    private static final class AudioDecoder {
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
                                codec.queueInputBuffer(inIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long time = extractor.getSampleTime();
                                codec.queueInputBuffer(inIndex, 0, size, Math.max(0, time), 0);
                                extractor.advance();
                            }
                        }
                    }

                    int outIndex = codec.dequeueOutputBuffer(info, 10000);
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat outputFormat = codec.getOutputFormat();
                        if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        }
                        if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        }
                        if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                        }
                    } else if (outIndex >= 0) {
                        ByteBuffer output = codec.getOutputBuffer(outIndex);
                        if (output != null && info.size > 0) {
                            ByteBuffer pcm = output.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                            pcm.position(info.offset);
                            pcm.limit(info.offset + info.size);
                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                while (pcm.remaining() >= 4) collector.add(clampSample(pcm.getFloat()));
                            } else if (pcmEncoding == AudioFormat.ENCODING_PCM_8BIT) {
                                while (pcm.hasRemaining()) {
                                    int unsigned = pcm.get() & 0xff;
                                    collector.add((unsigned - 128) / 128f);
                                }
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
                } catch (Exception ignored) {
                }
                extractor.release();
            }
        }
    }

    private static final class Player {
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

            Thread t = new Thread(() -> {
                int frame = clamp(startFrame, 0, data.frameCount());
                int end = clamp(endFrame, frame, data.frameCount());
                try {
                    while (!stopRequested && frame < end) {
                        int frames = Math.min(2048, end - frame);
                        int sampleOffset = frame * channels;
                        int sampleCount = frames * channels;
                        int written = track.write(data.samples, sampleOffset, sampleCount, AudioTrack.WRITE_BLOCKING);
                        if (written <= 0) break;
                        frame += written / channels;
                        if (listener != null) listener.onProgress(frame);
                    }
                } catch (Exception ignored) {
                } finally {
                    synchronized (Player.this) {
                        try {
                            if (track != null) {
                                track.stop();
                                track.flush();
                                track.release();
                            }
                        } catch (Exception ignored) {
                        }
                        track = null;
                        playing = false;
                    }
                    if (listener != null && !stopRequested) listener.onComplete();
                }
            }, "VocalStretcher-Playback");
            t.start();
        }

        synchronized void stop() {
            stopRequested = true;
            playing = false;
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static final class StretchEngine {
        private static final double TWO_PI = Math.PI * 2.0;

        static AudioData stretchRegion(AudioData in, int startFrame, int endFrame, int targetFrames) {
            startFrame = clamp(startFrame, 0, in.frameCount());
            endFrame = clamp(endFrame, startFrame, in.frameCount());
            int selectedFrames = endFrame - startFrame;
            if (selectedFrames <= 0) throw new IllegalArgumentException("Empty selection.");
            if (targetFrames < selectedFrames) throw new IllegalArgumentException("Target is shorter than the selection.");
            if (targetFrames == selectedFrames) {
                float[] copy = new float[in.samples.length];
                System.arraycopy(in.samples, 0, copy, 0, copy.length);
                return new AudioData(in.sampleRate, in.channels, copy);
            }

            PitchEstimate pitch = estimatePitch(in, startFrame, endFrame);
            float[] stretchedSelection = synthesizeSelection(in, startFrame, endFrame, targetFrames, pitch);

            int outFrames = in.frameCount() - selectedFrames + targetFrames;
            float[] out = new float[outFrames * in.channels];
            int prefixSamples = startFrame * in.channels;
            System.arraycopy(in.samples, 0, out, 0, prefixSamples);
            System.arraycopy(stretchedSelection, 0, out, prefixSamples, stretchedSelection.length);
            int suffixSamples = (in.frameCount() - endFrame) * in.channels;
            System.arraycopy(in.samples, endFrame * in.channels, out,
                    (startFrame + targetFrames) * in.channels, suffixSamples);
            return new AudioData(in.sampleRate, in.channels, out);
        }

        private static float[] synthesizeSelection(AudioData in, int start, int end,
                                                   int targetFrames, PitchEstimate pitch) {
            int selected = end - start;
            int period = clamp((int) Math.round(pitch.periodFrames),
                    Math.max(8, in.sampleRate / 1400), Math.max(16, in.sampleRate / 55));
            int guard = Math.min(selected / 4, Math.max(period * 3, (int) (in.sampleRate * 0.030)));
            guard = Math.max(Math.min(period * 2, selected / 5), guard);
            if (guard * 2 >= selected) guard = Math.max(period, selected / 5);

            int stableStart = start + guard;
            int stableEnd = end - guard;
            if (stableEnd - stableStart < period * 4) {
                stableStart = start + Math.min(period, selected / 6);
                stableEnd = end - Math.min(period, selected / 6);
            }

            List<Integer> marks = buildPitchMarks(in, stableStart, stableEnd, period);
            float[] synth;
            if (pitch.confidence >= 0.28 && marks.size() >= 4) {
                synth = psolaHold(in, start, end, targetFrames, period, marks);
            } else {
                synth = loopFallback(in, start, end, targetFrames, period, stableStart, stableEnd);
            }

            int xfade = Math.min(guard / 2, Math.max(period * 2, in.sampleRate / 125));
            xfade = Math.max(1, xfade);
            int channels = in.channels;

            int attackHardEnd = Math.max(0, guard - xfade);
            for (int f = 0; f < attackHardEnd && f < targetFrames && f < selected; f++) {
                copyFrame(in.samples, (start + f) * channels, synth, f * channels, channels);
            }
            for (int i = 0; i < xfade; i++) {
                int outFrame = attackHardEnd + i;
                int srcFrame = start + outFrame;
                if (outFrame >= targetFrames || srcFrame >= end) break;
                float a = i / (float) Math.max(1, xfade - 1);
                for (int c = 0; c < channels; c++) {
                    int oi = outFrame * channels + c;
                    float original = in.samples[srcFrame * channels + c];
                    synth[oi] = equalPower(original, synth[oi], a);
                }
            }

            int releaseOutStart = Math.max(0, targetFrames - guard);
            int releaseSrcStart = Math.max(start, end - guard);
            for (int i = 0; i < guard && releaseOutStart + i < targetFrames; i++) {
                int outFrame = releaseOutStart + i;
                int srcFrame = releaseSrcStart + i;
                if (srcFrame >= end) break;
                if (i < xfade) {
                    float a = i / (float) Math.max(1, xfade - 1);
                    for (int c = 0; c < channels; c++) {
                        int oi = outFrame * channels + c;
                        float original = in.samples[srcFrame * channels + c];
                        synth[oi] = equalPower(synth[oi], original, a);
                    }
                } else {
                    copyFrame(in.samples, srcFrame * channels, synth, outFrame * channels, channels);
                }
            }
            return synth;
        }

        private static float[] psolaHold(AudioData in, int selectionStart, int selectionEnd,
                                         int targetFrames, int period, List<Integer> marks) {
            int channels = in.channels;
            float[] out = new float[targetFrames * channels];
            float[] weight = new float[targetFrames];
            int radius = Math.max(period, (int) Math.round(period * 1.45));
            int sourceIndex = 0;
            int outCenter = 0;

            while (outCenter < targetFrames + radius) {
                int srcMark = marks.get(sourceIndex);
                for (int rel = -radius; rel <= radius; rel++) {
                    int outFrame = outCenter + rel;
                    int srcFrame = srcMark + rel;
                    if (outFrame < 0 || outFrame >= targetFrames) continue;
                    if (srcFrame < selectionStart || srcFrame >= selectionEnd) continue;
                    double phase = (rel + radius) / (double) Math.max(1, radius * 2);
                    float w = (float) (0.5 - 0.5 * Math.cos(TWO_PI * phase));
                    int outBase = outFrame * channels;
                    int srcBase = srcFrame * channels;
                    for (int c = 0; c < channels; c++) out[outBase + c] += in.samples[srcBase + c] * w;
                    weight[outFrame] += w;
                }

                int next = (sourceIndex + 1) % marks.size();
                int hop;
                if (next > sourceIndex) hop = marks.get(next) - srcMark;
                else hop = period;
                hop = clamp(hop, Math.max(1, (int) (period * 0.72)), Math.max(2, (int) (period * 1.32)));
                outCenter += hop;
                sourceIndex = next;
            }

            for (int f = 0; f < targetFrames; f++) {
                float w = weight[f];
                if (w > 1e-5f) {
                    int base = f * channels;
                    for (int c = 0; c < channels; c++) out[base + c] /= w;
                }
            }
            return out;
        }

        private static float[] loopFallback(AudioData in, int selectionStart, int selectionEnd,
                                            int targetFrames, int period, int stableStart, int stableEnd) {
            int channels = in.channels;
            float[] out = new float[targetFrames * channels];
            int stableLength = Math.max(1, stableEnd - stableStart);
            int desired = Math.min(stableLength, Math.max(period * 6, (int) (in.sampleRate * 0.12)));
            int loopLength = Math.max(period * 2, (desired / Math.max(1, period)) * period);
            loopLength = Math.min(loopLength, stableLength);
            if (loopLength <= 0) loopLength = Math.min(selectionEnd - selectionStart, Math.max(1, period * 2));
            int center = (stableStart + stableEnd) / 2;
            int loopStart = clamp(center - loopLength / 2, selectionStart,
                    Math.max(selectionStart, selectionEnd - loopLength));
            int seam = Math.min(Math.max(period * 2, 8), Math.max(1, loopLength / 4));

            for (int f = 0; f < targetFrames; f++) {
                int pos = f % loopLength;
                int src = loopStart + pos;
                int outBase = f * channels;
                if (f >= loopLength && pos < seam) {
                    int tail = loopStart + loopLength - seam + pos;
                    float a = pos / (float) Math.max(1, seam - 1);
                    for (int c = 0; c < channels; c++) {
                        out[outBase + c] = equalPower(in.samples[tail * channels + c],
                                in.samples[src * channels + c], a);
                    }
                } else {
                    copyFrame(in.samples, src * channels, out, outBase, channels);
                }
            }
            return out;
        }

        private static PitchEstimate estimatePitch(AudioData in, int start, int end) {
            int length = end - start;
            int window = Math.min(length, Math.max(512, (int) (in.sampleRate * 0.080)));
            if (window < 128) return new PitchEstimate(Math.max(16, in.sampleRate / 220.0), 0.0);
            int center = (start + end) / 2;
            int wStart = clamp(center - window / 2, start, Math.max(start, end - window));
            int minLag = Math.max(8, in.sampleRate / 1200);
            int maxLag = Math.min(window / 2, Math.max(minLag + 1, in.sampleRate / 65));
            if (maxLag <= minLag) return new PitchEstimate(Math.max(16, in.sampleRate / 220.0), 0.0);

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
                double xy = 0.0;
                double xx = 0.0;
                double yy = 0.0;
                int n = window - lag;
                for (int i = 0; i < n; i++) {
                    double a = x[i];
                    double b = x[i + lag];
                    xy += a * b;
                    xx += a * a;
                    yy += b * b;
                }
                double value = (xx > 1e-12 && yy > 1e-12) ? xy / Math.sqrt(xx * yy) : 0.0;
                corr[lag] = value;
                if (value > best) {
                    best = value;
                    bestLag = lag;
                }
            }

            double threshold = Math.max(0.28, best * 0.92);
            for (int lag = minLag + 1; lag < bestLag; lag++) {
                if (corr[lag] >= threshold && corr[lag] >= corr[lag - 1] && corr[lag] >= corr[lag + 1]) {
                    bestLag = lag;
                    best = corr[lag];
                    break;
                }
            }

            double refined = bestLag;
            if (bestLag > minLag && bestLag < maxLag) {
                double y1 = corr[bestLag - 1];
                double y2 = corr[bestLag];
                double y3 = corr[bestLag + 1];
                double denom = y1 - 2.0 * y2 + y3;
                if (Math.abs(denom) > 1e-9) refined += 0.5 * (y1 - y3) / denom;
            }
            return new PitchEstimate(refined, Math.max(0.0, Math.min(1.0, best)));
        }

        private static List<Integer> buildPitchMarks(AudioData in, int start, int end, int period) {
            List<Integer> marks = new ArrayList<>();
            if (end - start < period * 2) return marks;
            int center = (start + end) / 2;
            int anchor = findPeak(in, center, Math.max(2, period / 2), start, end, 0);
            float anchorValue = in.monoAt(anchor);
            int sign = anchorValue >= 0f ? 1 : -1;
            marks.add(anchor);

            int last = anchor;
            while (last + period < end - 1) {
                int predicted = last + period;
                int mark = findPeak(in, predicted, Math.max(2, period / 4), start, end, sign);
                if (mark <= last) break;
                marks.add(mark);
                last = mark;
            }

            last = anchor;
            List<Integer> left = new ArrayList<>();
            while (last - period > start) {
                int predicted = last - period;
                int mark = findPeak(in, predicted, Math.max(2, period / 4), start, end, sign);
                if (mark >= last) break;
                left.add(mark);
                last = mark;
            }
            Collections.reverse(left);
            left.addAll(marks);
            return left;
        }

        private static int findPeak(AudioData in, int predicted, int radius,
                                    int minFrame, int maxFrame, int sign) {
            int from = clamp(predicted - radius, minFrame, Math.max(minFrame, maxFrame - 1));
            int to = clamp(predicted + radius, from + 1, maxFrame);
            int bestFrame = from;
            float bestScore = -Float.MAX_VALUE;
            for (int f = from; f < to; f++) {
                float v = in.monoAt(f);
                float score;
                if (sign > 0) score = v;
                else if (sign < 0) score = -v;
                else score = Math.abs(v);
                if (score > bestScore) {
                    bestScore = score;
                    bestFrame = f;
                }
            }
            return bestFrame;
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

    private static final class WavWriter {
        static void write24Bit(AudioData data, OutputStream out) throws IOException {
            long frames = data.frameCount();
            int channels = data.channels;
            int bits = 24;
            int blockAlign = channels * 3;
            long dataBytes = frames * blockAlign;
            if (dataBytes > 0xffffffffL - 44) throw new IOException("WAV is too large for standard RIFF.");
            long riffSize = 36 + dataBytes;

            writeAscii(out, "RIFF");
            writeLE32(out, riffSize);
            writeAscii(out, "WAVE");
            writeAscii(out, "fmt ");
            writeLE32(out, 16);
            writeLE16(out, 1);
            writeLE16(out, channels);
            writeLE32(out, data.sampleRate);
            writeLE32(out, (long) data.sampleRate * blockAlign);
            writeLE16(out, blockAlign);
            writeLE16(out, bits);
            writeAscii(out, "data");
            writeLE32(out, dataBytes);

            for (float sample : data.samples) {
                float s = clampSample(sample);
                int v = Math.round(s * 8388607f);
                out.write(v & 0xff);
                out.write((v >> 8) & 0xff);
                out.write((v >> 16) & 0xff);
            }
            out.flush();
        }

        private static void writeAscii(OutputStream out, String text) throws IOException {
            for (int i = 0; i < text.length(); i++) out.write((byte) text.charAt(i));
        }

        private static void writeLE16(OutputStream out, long value) throws IOException {
            out.write((int) (value & 0xff));
            out.write((int) ((value >> 8) & 0xff));
        }

        private static void writeLE32(OutputStream out, long value) throws IOException {
            out.write((int) (value & 0xff));
            out.write((int) ((value >> 8) & 0xff));
            out.write((int) ((value >> 16) & 0xff));
            out.write((int) ((value >> 24) & 0xff));
        }
    }

    private static void copyFrame(float[] src, int srcOffset, float[] dst, int dstOffset, int channels) {
        System.arraycopy(src, srcOffset, dst, dstOffset, channels);
    }

    private static float equalPower(float a, float b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        double angle = t * Math.PI * 0.5;
        return (float) (a * Math.cos(angle) + b * Math.sin(angle));
    }

    private static float clampSample(float value) {
        if (!Float.isFinite(value)) return 0f;
        return Math.max(-1f, Math.min(1f, value));
    }

    private static final class WaveformView extends View {
        interface OnPlayheadChangedListener {
            void onChanged(int frame);
        }

        private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint boundaryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector scaleDetector;

        private AudioData audio;
        private OnPlayheadChangedListener listener;
        private int playhead = 0;
        private int selectionStart = -1;
        private int selectionEnd = -1;
        private double framesPerPixel = 1.0;
        private double visibleStart = 0.0;
        private float lastTwoFingerCenterX = Float.NaN;

        WaveformView(Activity context) {
            super(context);
            setBackgroundColor(Color.rgb(250, 250, 250));
            wavePaint.setColor(Color.rgb(25, 25, 25));
            wavePaint.setStrokeWidth(1f);
            centerPaint.setColor(Color.rgb(210, 210, 210));
            centerPaint.setStrokeWidth(1f);
            playheadPaint.setColor(Color.rgb(210, 32, 32));
            playheadPaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 1.5f);
            selectionPaint.setColor(Color.argb(55, 255, 145, 0));
            boundaryPaint.setColor(Color.rgb(235, 125, 0));
            boundaryPaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 1.5f);

            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    if (audio == null || getWidth() <= 0) return false;
                    double old = framesPerPixel;
                    double focusFrame = visibleStart + detector.getFocusX() * old;
                    double max = Math.max(1.0, audio.frameCount() / (double) Math.max(1, getWidth()));
                    double next = old / Math.max(0.25f, Math.min(4f, detector.getScaleFactor()));
                    framesPerPixel = Math.max(1.0, Math.min(max, next));
                    visibleStart = focusFrame - detector.getFocusX() * framesPerPixel;
                    clampViewport();
                    invalidate();
                    return true;
                }
            });
        }

        void setOnPlayheadChangedListener(OnPlayheadChangedListener listener) {
            this.listener = listener;
        }

        void setAudio(AudioData data) {
            audio = data;
            playhead = 0;
            visibleStart = 0.0;
            fitToScreen();
            invalidate();
        }

        void setPlayhead(int frame) {
            if (audio == null) return;
            playhead = clamp(frame, 0, audio.frameCount());
            ensurePlayheadVisible();
            invalidate();
        }

        void setSelection(int start, int end) {
            selectionStart = start;
            selectionEnd = end;
            invalidate();
        }

        private void fitToScreen() {
            if (audio == null || getWidth() <= 0) return;
            framesPerPixel = Math.max(1.0, audio.frameCount() / (double) getWidth());
            visibleStart = 0.0;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (audio != null && oldw == 0) fitToScreen();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            float mid = height / 2f;
            canvas.drawLine(0, mid, width, mid, centerPaint);
            if (audio == null || width <= 0 || height <= 0) return;

            if (selectionStart >= 0) {
                float x1 = frameToX(selectionStart);
                float x2 = selectionEnd > selectionStart ? frameToX(selectionEnd) : x1;
                if (selectionEnd > selectionStart) {
                    RectF rect = new RectF(Math.min(x1, x2), 0, Math.max(x1, x2), height);
                    canvas.drawRect(rect, selectionPaint);
                    canvas.drawLine(x2, 0, x2, height, boundaryPaint);
                }
                canvas.drawLine(x1, 0, x1, height, boundaryPaint);
            }

            float amplitude = height * 0.42f;
            int totalFrames = audio.frameCount();
            for (int x = 0; x < width; x++) {
                int from = clamp((int) Math.floor(visibleStart + x * framesPerPixel), 0, totalFrames);
                int to = clamp((int) Math.ceil(visibleStart + (x + 1) * framesPerPixel), from + 1, totalFrames);
                if (from >= totalFrames) break;
                int span = Math.max(1, to - from);
                int step = Math.max(1, span / 64);
                float min = 1f;
                float max = -1f;
                for (int f = from; f < to; f += step) {
                    float v = audio.monoAt(f);
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
                if (max < min) {
                    min = 0f;
                    max = 0f;
                }
                canvas.drawLine(x, mid - max * amplitude, x, mid - min * amplitude, wavePaint);
            }

            float px = frameToX(playhead);
            canvas.drawLine(px, 0, px, height, playheadPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (audio == null) return true;
            scaleDetector.onTouchEvent(event);

            if (event.getPointerCount() >= 2) {
                float center = (event.getX(0) + event.getX(1)) * 0.5f;
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !Float.isNaN(lastTwoFingerCenterX)) {
                    float dx = center - lastTwoFingerCenterX;
                    visibleStart -= dx * framesPerPixel;
                    clampViewport();
                    invalidate();
                }
                lastTwoFingerCenterX = center;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP
                    || event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                lastTwoFingerCenterX = Float.NaN;
            }

            if (!scaleDetector.isInProgress()) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE:
                    case MotionEvent.ACTION_UP:
                        setPlayheadFromX(event.getX());
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                }
            }
            return true;
        }

        private void setPlayheadFromX(float x) {
            if (audio == null) return;
            int frame = clamp((int) Math.round(visibleStart + x * framesPerPixel), 0, audio.frameCount());
            playhead = frame;
            invalidate();
            if (listener != null) listener.onChanged(frame);
        }

        private float frameToX(int frame) {
            return (float) ((frame - visibleStart) / framesPerPixel);
        }

        private void ensurePlayheadVisible() {
            if (audio == null || getWidth() <= 0) return;
            double visibleEnd = visibleStart + getWidth() * framesPerPixel;
            if (playhead < visibleStart) visibleStart = playhead;
            else if (playhead > visibleEnd) visibleStart = playhead - getWidth() * framesPerPixel * 0.8;
            clampViewport();
        }

        private void clampViewport() {
            if (audio == null || getWidth() <= 0) return;
            double visibleFrames = getWidth() * framesPerPixel;
            double maxStart = Math.max(0.0, audio.frameCount() - visibleFrames);
            visibleStart = Math.max(0.0, Math.min(maxStart, visibleStart));
        }
    }
}
