package com.vitkkk.vocalstretcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v0.5 workflow:
 * - the imported audio is only a SOURCE for locating the vowel;
 * - stretch creates a separate AudioData containing ONLY the selected sample;
 * - preview plays only that generated sample;
 * - export writes only that generated sample.
 *
 * The currently approved stretch engine is intentionally left unchanged.
 */
public class MainActivityV5 extends Activity {
    private static final int REQ_IMPORT_AUDIO = 1001;
    private static final int REQ_EXPORT_FOLDER = 1002;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AudioPlayer player = new AudioPlayer();

    private WaveformViewV2 waveformView;
    private TextView timeLabel;
    private TextView markerLabel;
    private TextView selectionLabel;
    private TextView sampleLabel;
    private TextView statusLabel;
    private Button importButton;
    private Button playButton;
    private Button markStartButton;
    private Button testStartButton;
    private Button cutButton;
    private Button previewButton;
    private Button generateButton;
    private Button exportButton;
    private EditText durationInput;

    // Immutable source audio during one editing session.
    private AudioData sourceAudio;

    // Separate generated asset. Never replaces sourceAudio.
    private AudioData processedSample;
    private int processedSourceStart = -1;
    private int processedSourceEnd = -1;
    private int processedTargetFrames = -1;

    private int playheadFrame = 0;
    private int auditionStart = -1;
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private boolean waitingForSecondCut = false;
    private boolean busy = false;
    private boolean suppressDurationWatcher = false;

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
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Vocal Stretcher v0.5");
        title.setTextSize(24f);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, fullWrap());

        importButton = new Button(this);
        importButton.setText("Importar áudio fonte");
        importButton.setOnClickListener(v -> chooseAudio());
        root.addView(importButton, fullWrap());

        waveformView = new WaveformViewV2(this);
        waveformView.setOnPlayheadChangedListener(frame -> {
            playheadFrame = frame;
            updateTimeLabel();
        });
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        waveParams.topMargin = dp(8);
        waveParams.bottomMargin = dp(4);
        root.addView(waveformView, waveParams);

        TextView hint = new TextView(this);
        hint.setText("Áudio acima = apenas fonte  •  1 dedo: cursor  •  2 dedos: zoom/arrastar");
        hint.setTextColor(Color.DKGRAY);
        hint.setTextSize(12f);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(hint, fullWrap());

        timeLabel = new TextView(this);
        timeLabel.setText("00:00.000 / 00:00.000");
        timeLabel.setTextColor(Color.BLACK);
        timeLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        timeLabel.setPadding(0, dp(2), 0, dp(4));
        root.addView(timeLabel, fullWrap());

        LinearLayout transport = horizontalRow();
        playButton = new Button(this);
        playButton.setText("Play fonte");
        playButton.setOnClickListener(v -> toggleSourcePlayback());
        cutButton = new Button(this);
        cutButton.setText("Corte início");
        cutButton.setOnClickListener(v -> markCut());
        transport.addView(playButton, weighted());
        transport.addView(cutButton, weighted());
        root.addView(transport, fullWrap());

        LinearLayout auditionRow = horizontalRow();
        markStartButton = new Button(this);
        markStartButton.setText("Começar aqui");
        markStartButton.setOnClickListener(v -> setAuditionStart());
        testStartButton = new Button(this);
        testStartButton.setText("Testar daqui");
        testStartButton.setOnClickListener(v -> testAuditionStart());
        auditionRow.addView(markStartButton, weighted());
        auditionRow.addView(testStartButton, weighted());
        root.addView(auditionRow, fullWrap());

        markerLabel = new TextView(this);
        markerLabel.setText("Marcador azul: não definido");
        markerLabel.setTextColor(Color.rgb(35, 95, 220));
        markerLabel.setTextSize(12f);
        markerLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(markerLabel, fullWrap());

        selectionLabel = new TextView(this);
        selectionLabel.setText("Nenhuma região selecionada");
        selectionLabel.setTextColor(Color.DKGRAY);
        selectionLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        selectionLabel.setPadding(0, dp(3), 0, dp(3));
        root.addView(selectionLabel, fullWrap());

        LinearLayout durationRow = horizontalRow();
        TextView durationTitle = new TextView(this);
        durationTitle.setText("Duração do sample: ");
        durationTitle.setTextColor(Color.BLACK);
        durationInput = new EditText(this);
        durationInput.setSingleLine(true);
        durationInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        durationInput.setHint("1.000");
        durationInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!suppressDurationWatcher) {
                    invalidateProcessedSample();
                    refreshUi();
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        TextView seconds = new TextView(this);
        seconds.setText(" s");
        seconds.setTextColor(Color.BLACK);
        durationRow.addView(durationTitle);
        durationRow.addView(durationInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        durationRow.addView(seconds);
        root.addView(durationRow, fullWrap());

        LinearLayout processRow = horizontalRow();
        previewButton = new Button(this);
        previewButton.setText("Preview sample");
        previewButton.setOnClickListener(v -> previewSample());
        generateButton = new Button(this);
        generateButton.setText("Gerar sample");
        generateButton.setOnClickListener(v -> generateSample(false));
        processRow.addView(previewButton, weighted());
        processRow.addView(generateButton, weighted());
        root.addView(processRow, fullWrap());

        sampleLabel = new TextView(this);
        sampleLabel.setText("Sample processado: ainda não gerado");
        sampleLabel.setTextColor(Color.DKGRAY);
        sampleLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        sampleLabel.setPadding(0, dp(3), 0, dp(3));
        root.addView(sampleLabel, fullWrap());

        exportButton = new Button(this);
        exportButton.setText("Salvar somente o sample WAV 24-bit");
        exportButton.setOnClickListener(v -> chooseExportFolder());
        root.addView(exportButton, fullWrap());

        statusLabel = new TextView(this);
        statusLabel.setText("Importe um áudio. Ele será usado somente para localizar e recortar a vogal.");
        statusLabel.setTextColor(Color.DKGRAY);
        statusLabel.setTextSize(12f);
        statusLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        statusLabel.setPadding(0, dp(4), 0, 0);
        root.addView(statusLabel, fullWrap());

        setContentView(root);
    }

    private void chooseAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_IMPORT_AUDIO);
    }

    private void chooseExportFolder() {
        if (processedSample == null || busy) return;
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
            } catch (Exception ignored) {}
            importAudio(uri);
        } else if (requestCode == REQ_EXPORT_FOLDER) {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {}
            exportSampleToFolder(uri);
        }
    }

    private void importAudio(Uri uri) {
        stopPlaybackLabels();
        setBusy(true, "Decodificando áudio fonte…");
        worker.execute(() -> {
            try {
                AudioData decoded = AudioDecoder.decode(this, uri);
                mainHandler.post(() -> {
                    sourceAudio = decoded;
                    playheadFrame = 0;
                    auditionStart = -1;
                    selectionStart = -1;
                    selectionEnd = -1;
                    waitingForSecondCut = false;
                    invalidateProcessedSample();
                    waveformView.setAudio(decoded);
                    waveformView.setPlayhead(0);
                    waveformView.setAuditionStart(-1);
                    waveformView.setSelection(-1, -1);
                    cutButton.setText("Corte início");
                    setBusy(false, String.format(Locale.US,
                            "Fonte carregada: %.2f s • %d Hz • %d canal%s",
                            decoded.durationSeconds(), decoded.sampleRate, decoded.channels,
                            decoded.channels == 1 ? "" : "s"));
                    refreshUi();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, "Falha ao importar.");
                    showError("Não foi possível decodificar o áudio", e);
                });
            }
        });
    }

    private void toggleSourcePlayback() {
        if (sourceAudio == null || busy) return;
        if (player.isPlaying()) {
            stopPlaybackLabels();
            return;
        }
        int start = clamp(playheadFrame, 0, Math.max(0, sourceAudio.frameCount() - 1));
        playButton.setText("Stop");
        player.play(sourceAudio, start, sourceAudio.frameCount(), new AudioPlayer.Listener() {
            @Override
            public void onProgress(int frame) {
                mainHandler.post(() -> {
                    playheadFrame = clamp(frame, 0, sourceAudio.frameCount());
                    waveformView.setPlayhead(playheadFrame);
                    updateTimeLabel();
                });
            }

            @Override
            public void onComplete() {
                mainHandler.post(() -> playButton.setText("Play fonte"));
            }
        });
    }

    private void setAuditionStart() {
        if (sourceAudio == null || busy) return;
        stopPlaybackLabels();
        auditionStart = clamp(playheadFrame, 0, Math.max(0, sourceAudio.frameCount() - 1));
        waveformView.setAuditionStart(auditionStart);
        statusLabel.setText("Início de teste marcado. Use 'Testar daqui' para conferir silêncio/ataque.");
        refreshUi();
    }

    private void testAuditionStart() {
        if (sourceAudio == null || busy || auditionStart < 0) return;
        player.stop();
        playButton.setText("Play fonte");
        int testLength = (int) Math.round(sourceAudio.sampleRate * 1.35);
        int end = Math.min(sourceAudio.frameCount(), auditionStart + testLength);
        player.play(sourceAudio, auditionStart, end, new AudioPlayer.Listener() {
            @Override public void onProgress(int frame) {}
            @Override public void onComplete() {}
        });
    }

    private void markCut() {
        if (sourceAudio == null || busy) return;
        stopPlaybackLabels();
        invalidateProcessedSample();

        if (!waitingForSecondCut) {
            selectionStart = clamp(playheadFrame, 0, Math.max(0, sourceAudio.frameCount() - 1));
            selectionEnd = -1;
            waitingForSecondCut = true;
            waveformView.setSelection(selectionStart, -1);
            cutButton.setText("Corte fim");
            statusLabel.setText("Início marcado. Agora leve o cursor ao fim da vogal.");
        } else {
            selectionEnd = clamp(playheadFrame, 0, sourceAudio.frameCount());
            if (selectionEnd < selectionStart) {
                int t = selectionStart;
                selectionStart = selectionEnd;
                selectionEnd = t;
            }
            int minimum = Math.max(64, sourceAudio.sampleRate / 150);
            if (selectionEnd - selectionStart < minimum) {
                Toast.makeText(this, "Região curta demais.", Toast.LENGTH_LONG).show();
                selectionEnd = -1;
                waveformView.setSelection(selectionStart, -1);
                refreshUi();
                return;
            }
            waitingForSecondCut = false;
            waveformView.setSelection(selectionStart, selectionEnd);
            cutButton.setText("Corte início");
            double original = (selectionEnd - selectionStart) / (double) sourceAudio.sampleRate;
            suppressDurationWatcher = true;
            durationInput.setText(String.format(Locale.US, "%.3f", Math.max(1.0, original * 2.0)));
            suppressDurationWatcher = false;
            statusLabel.setText("Região pronta. Preview e exportação usarão SOMENTE este sample.");
        }
        refreshUi();
    }

    private void previewSample() {
        if (processedSample != null && cacheMatchesCurrentSettings()) {
            playProcessedSample();
            return;
        }
        generateSample(true);
    }

    private void generateSample(boolean autoPlay) {
        if (!hasFinishedSelection() || busy) return;
        int targetFrames = readTargetFrames();
        if (!validateStretch(targetFrames)) return;

        stopPlaybackLabels();
        setBusy(true, "Sintetizando somente o sample selecionado…");
        AudioData base = sourceAudio;
        int start = selectionStart;
        int end = selectionEnd;

        worker.execute(() -> {
            try {
                // Preserve the synthesis engine that produced the approved sound.
                AudioData stretchedFull = StretchEngineV2.stretchRegion(base, start, end, targetFrames);
                AudioData sampleOnly = extractSample(stretchedFull, start, targetFrames);

                mainHandler.post(() -> {
                    processedSample = sampleOnly;
                    processedSourceStart = start;
                    processedSourceEnd = end;
                    processedTargetFrames = targetFrames;
                    setBusy(false, String.format(Locale.US,
                            "Sample pronto: %.3f s. O áudio fonte não foi alterado.",
                            sampleOnly.durationSeconds()));
                    refreshUi();
                    if (autoPlay) playProcessedSample();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, "Falha na síntese.");
                    showError("Não foi possível gerar o sample", e);
                });
            }
        });
    }

    private AudioData extractSample(AudioData stretchedFull, int sampleStartFrame, int sampleFrames) {
        int start = clamp(sampleStartFrame, 0, stretchedFull.frameCount());
        int end = clamp(start + sampleFrames, start, stretchedFull.frameCount());
        int frames = end - start;
        if (frames <= 0) throw new IllegalStateException("A síntese não produziu um sample válido.");
        float[] out = new float[frames * stretchedFull.channels];
        System.arraycopy(stretchedFull.samples, start * stretchedFull.channels,
                out, 0, out.length);
        return new AudioData(stretchedFull.sampleRate, stretchedFull.channels, out);
    }

    private void playProcessedSample() {
        if (processedSample == null || busy) return;
        player.stop();
        playButton.setText("Play fonte");
        statusLabel.setText("Preview: tocando somente o sample processado.");
        player.play(processedSample, 0, processedSample.frameCount(), new AudioPlayer.Listener() {
            @Override public void onProgress(int frame) {}
            @Override public void onComplete() {
                mainHandler.post(() -> statusLabel.setText("Preview concluído. Sample pronto para salvar."));
            }
        });
    }

    private int readTargetFrames() {
        if (!hasFinishedSelection()) return -1;
        String text = durationInput.getText().toString().trim().replace(',', '.');
        if (text.isEmpty()) return -1;
        try {
            double seconds = Double.parseDouble(text);
            if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 120.0) return -1;
            long frames = Math.round(seconds * sourceAudio.sampleRate);
            return frames > Integer.MAX_VALUE ? -1 : (int) frames;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean validateStretch(int targetFrames) {
        if (targetFrames <= 0) {
            Toast.makeText(this, "Digite uma duração válida, até 120 segundos.", Toast.LENGTH_LONG).show();
            return false;
        }
        int original = selectionEnd - selectionStart;
        if (targetFrames < original) {
            Toast.makeText(this, "A nova duração deve ser maior ou igual à região original.", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private boolean cacheMatchesCurrentSettings() {
        if (processedSample == null || sourceAudio == null) return false;
        int target = readTargetFrames();
        return selectionStart == processedSourceStart
                && selectionEnd == processedSourceEnd
                && target == processedTargetFrames;
    }

    private void exportSampleToFolder(Uri treeUri) {
        if (processedSample == null || busy) return;
        setBusy(true, "Salvando somente o sample WAV 24-bit…");
        AudioData toWrite = processedSample;
        worker.execute(() -> {
            try {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String fileName = "VocalSample_v05_" + stamp + ".wav";
                ContentResolver resolver = getContentResolver();
                String treeId = DocumentsContract.getTreeDocumentId(treeUri);
                Uri directoryUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
                Uri outUri = DocumentsContract.createDocument(resolver, directoryUri, "audio/wav", fileName);
                if (outUri == null) throw new IOException("Android não conseguiu criar o arquivo nessa pasta.");
                try (OutputStream raw = resolver.openOutputStream(outUri, "w")) {
                    if (raw == null) throw new IOException("Não foi possível abrir o arquivo de saída.");
                    try (BufferedOutputStream out = new BufferedOutputStream(raw, 64 * 1024)) {
                        WavWriter24.write(toWrite, out);
                    }
                }
                mainHandler.post(() -> {
                    setBusy(false, "Sample salvo: " + fileName);
                    Toast.makeText(this, "Sample salvo: " + fileName, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, "Falha ao salvar.");
                    showError("Não foi possível salvar o sample", e);
                });
            }
        });
    }

    private void invalidateProcessedSample() {
        processedSample = null;
        processedSourceStart = -1;
        processedSourceEnd = -1;
        processedTargetFrames = -1;
    }

    private void stopPlaybackLabels() {
        player.stop();
        if (playButton != null) playButton.setText("Play fonte");
    }

    private boolean hasFinishedSelection() {
        return sourceAudio != null && !waitingForSecondCut
                && selectionStart >= 0 && selectionEnd > selectionStart;
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        if (statusLabel != null) statusLabel.setText(message);
        refreshUi();
    }

    private void refreshUi() {
        if (importButton == null) return;
        boolean hasAudio = sourceAudio != null;
        boolean selected = hasFinishedSelection();
        importButton.setEnabled(!busy);
        playButton.setEnabled(hasAudio && !busy);
        markStartButton.setEnabled(hasAudio && !busy);
        testStartButton.setEnabled(hasAudio && !busy && auditionStart >= 0);
        cutButton.setEnabled(hasAudio && !busy);
        previewButton.setEnabled(selected && !busy);
        generateButton.setEnabled(selected && !busy);
        durationInput.setEnabled(selected && !busy);
        exportButton.setEnabled(processedSample != null && !busy);
        updateTimeLabel();
        updateSelectionLabel();
        updateMarkerLabel();
        updateSampleLabel();
    }

    private void updateTimeLabel() {
        if (sourceAudio == null) {
            timeLabel.setText("00:00.000 / 00:00.000");
            return;
        }
        timeLabel.setText(formatTime(playheadFrame, sourceAudio.sampleRate) + " / "
                + formatTime(sourceAudio.frameCount(), sourceAudio.sampleRate));
    }

    private void updateMarkerLabel() {
        if (sourceAudio == null || auditionStart < 0) {
            markerLabel.setText("Marcador azul: não definido");
        } else {
            markerLabel.setText("Marcador azul: " + formatTime(auditionStart, sourceAudio.sampleRate)
                    + "  •  Testar daqui = 1,35 s");
        }
    }

    private void updateSelectionLabel() {
        if (sourceAudio == null || selectionStart < 0) {
            selectionLabel.setText("Nenhuma região selecionada");
        } else if (waitingForSecondCut || selectionEnd <= selectionStart) {
            selectionLabel.setText("Corte início: " + formatTime(selectionStart, sourceAudio.sampleRate)
                    + " • escolha o fim");
        } else {
            double duration = (selectionEnd - selectionStart) / (double) sourceAudio.sampleRate;
            selectionLabel.setText(String.format(Locale.US,
                    "Sample fonte: %s → %s  (%.3f s)",
                    formatTime(selectionStart, sourceAudio.sampleRate),
                    formatTime(selectionEnd, sourceAudio.sampleRate), duration));
        }
    }

    private void updateSampleLabel() {
        if (processedSample == null) {
            sampleLabel.setText("Sample processado: ainda não gerado");
            sampleLabel.setTextColor(Color.DKGRAY);
        } else {
            sampleLabel.setText(String.format(Locale.US,
                    "Sample processado: %.3f s • pronto para salvar sozinho",
                    processedSample.durationSeconds()));
            sampleLabel.setTextColor(Color.rgb(30, 120, 55));
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

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private LinearLayout.LayoutParams fullWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
