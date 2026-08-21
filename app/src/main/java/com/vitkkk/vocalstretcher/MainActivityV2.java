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
import android.text.InputType;
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

public class MainActivityV2 extends Activity {
    private static final int REQ_IMPORT_AUDIO = 1001;
    private static final int REQ_EXPORT_FOLDER = 1002;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AudioPlayer player = new AudioPlayer();

    private WaveformViewV2 waveformView;
    private TextView timeLabel;
    private TextView markerLabel;
    private TextView selectionLabel;
    private TextView statusLabel;
    private Button importButton;
    private Button playButton;
    private Button markStartButton;
    private Button testStartButton;
    private Button cutButton;
    private Button previewButton;
    private Button applyButton;
    private Button exportButton;
    private EditText durationInput;

    private AudioData audio;
    private AudioData previewAudio;
    private AudioData previewBaseAudio;
    private int previewTargetFrames = -1;

    private int playheadFrame = 0;
    private int auditionStart = -1;
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private boolean waitingForSecondCut = false;
    private boolean busy = false;

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
        title.setText("Vocal Stretcher v0.2");
        title.setTextSize(24f);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, fullWrap());

        importButton = new Button(this);
        importButton.setText("Importar áudio");
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
        hint.setText("1 dedo: cursor  •  2 dedos: zoom e arrastar");
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
        playButton.setText("Play");
        playButton.setOnClickListener(v -> togglePlayback());
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
        durationTitle.setText("Nova duração: ");
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
        root.addView(durationRow, fullWrap());

        LinearLayout processRow = horizontalRow();
        previewButton = new Button(this);
        previewButton.setText("Preview stretch");
        previewButton.setOnClickListener(v -> previewStretch());
        applyButton = new Button(this);
        applyButton.setText("Aplicar");
        applyButton.setOnClickListener(v -> applyStretch());
        processRow.addView(previewButton, weighted());
        processRow.addView(applyButton, weighted());
        root.addView(processRow, fullWrap());

        exportButton = new Button(this);
        exportButton.setText("Exportar WAV 24-bit");
        exportButton.setOnClickListener(v -> chooseExportFolder());
        root.addView(exportButton, fullWrap());

        statusLabel = new TextView(this);
        statusLabel.setText("Importe um áudio para começar.");
        statusLabel.setTextColor(Color.DKGRAY);
        statusLabel.setTextSize(12f);
        statusLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        statusLabel.setPadding(0, dp(4), 0, 0);
        root.addView(statusLabel, fullWrap());

        setContentView(root);
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private LinearLayout.LayoutParams fullWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
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
            } catch (Exception ignored) {}
            importAudio(uri);
        } else if (requestCode == REQ_EXPORT_FOLDER) {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {}
            exportToFolder(uri);
        }
    }

    private void importAudio(Uri uri) {
        stopPlaybackLabels();
        setBusy(true, "Decodificando áudio…");
        worker.execute(() -> {
            try {
                AudioData decoded = AudioDecoder.decode(this, uri);
                mainHandler.post(() -> {
                    audio = decoded;
                    previewAudio = null;
                    previewBaseAudio = null;
                    previewTargetFrames = -1;
                    playheadFrame = 0;
                    auditionStart = -1;
                    selectionStart = -1;
                    selectionEnd = -1;
                    waitingForSecondCut = false;
                    waveformView.setAudio(decoded);
                    waveformView.setPlayhead(0);
                    waveformView.setAuditionStart(-1);
                    waveformView.setSelection(-1, -1);
                    cutButton.setText("Corte início");
                    setBusy(false, String.format(Locale.US,
                            "Carregado: %.2f s • %d Hz • %d canal%s",
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

    private void togglePlayback() {
        if (audio == null || busy) return;
        if (player.isPlaying()) {
            stopPlaybackLabels();
            return;
        }
        int start = clamp(playheadFrame, 0, Math.max(0, audio.frameCount() - 1));
        playButton.setText("Stop");
        player.play(audio, start, audio.frameCount(), new AudioPlayer.Listener() {
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

    private void setAuditionStart() {
        if (audio == null || busy) return;
        stopPlaybackLabels();
        auditionStart = clamp(playheadFrame, 0, Math.max(0, audio.frameCount() - 1));
        waveformView.setAuditionStart(auditionStart);
        markerLabel.setText("Marcador azul: " + formatTime(auditionStart, audio.sampleRate));
        statusLabel.setText("Início de teste marcado. Toque 'Testar daqui' várias vezes para conferir silêncio/ataque.");
        refreshUi();
    }

    private void testAuditionStart() {
        if (audio == null || busy || auditionStart < 0) return;
        // Always restart from the exact marker so repeated taps are useful.
        player.stop();
        playButton.setText("Play");
        int testLength = (int) Math.round(audio.sampleRate * 1.35);
        int end = Math.min(audio.frameCount(), auditionStart + testLength);
        player.play(audio, auditionStart, end, new AudioPlayer.Listener() {
            @Override public void onProgress(int frame) {}
            @Override public void onComplete() {}
        });
    }

    private void markCut() {
        if (audio == null || busy) return;
        stopPlaybackLabels();

        if (!waitingForSecondCut) {
            selectionStart = clamp(playheadFrame, 0, Math.max(0, audio.frameCount() - 1));
            selectionEnd = -1;
            waitingForSecondCut = true;
            waveformView.setSelection(selectionStart, -1);
            cutButton.setText("Corte fim");
            statusLabel.setText("Início laranja marcado. Leve o cursor ao fim da vogal e toque 'Corte fim'.");
        } else {
            selectionEnd = clamp(playheadFrame, 0, audio.frameCount());
            if (selectionEnd < selectionStart) {
                int t = selectionStart;
                selectionStart = selectionEnd;
                selectionEnd = t;
            }
            int minimum = Math.max(64, audio.sampleRate / 150);
            if (selectionEnd - selectionStart < minimum) {
                Toast.makeText(this, "Região curta demais. Selecione pelo menos alguns milissegundos da vogal.", Toast.LENGTH_LONG).show();
                selectionEnd = -1;
                waveformView.setSelection(selectionStart, -1);
                return;
            }
            waitingForSecondCut = false;
            waveformView.setSelection(selectionStart, selectionEnd);
            cutButton.setText("Corte início");
            double original = (selectionEnd - selectionStart) / (double) audio.sampleRate;
            durationInput.setText(String.format(Locale.US, "%.3f", Math.max(1.0, original * 2.0)));
            invalidatePreview();
            statusLabel.setText("Região pronta. Esta versão faz stretch progressivo, sem repetir o bloco em fileira.");
        }
        refreshUi();
    }

    private void previewStretch() {
        if (!hasFinishedSelection() || busy) return;
        int targetFrames = readTargetFrames();
        if (!validateStretch(targetFrames)) return;
        stopPlaybackLabels();
        setBusy(true, "Sintetizando preview com PSOLA/WSOLA progressivo…");
        AudioData base = audio;
        int start = selectionStart;
        int end = selectionEnd;
        worker.execute(() -> {
            try {
                AudioData stretched = StretchEngineV2.stretchRegion(base, start, end, targetFrames);
                mainHandler.post(() -> {
                    previewAudio = stretched;
                    previewBaseAudio = base;
                    previewTargetFrames = targetFrames;
                    setBusy(false, "Preview pronto.");
                    int before = Math.max(0, start - base.sampleRate / 4);
                    int after = Math.min(stretched.frameCount(), start + targetFrames + base.sampleRate / 3);
                    player.play(stretched, before, after, new AudioPlayer.Listener() {
                        @Override public void onProgress(int frame) {}
                        @Override public void onComplete() {}
                    });
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, "Falha no preview.");
                    showError("Não foi possível sintetizar essa região", e);
                });
            }
        });
    }

    private void applyStretch() {
        if (!hasFinishedSelection() || busy) return;
        int targetFrames = readTargetFrames();
        if (!validateStretch(targetFrames)) return;
        stopPlaybackLabels();

        if (previewAudio != null && previewBaseAudio == audio && previewTargetFrames == targetFrames) {
            applyProcessedAudio(previewAudio, targetFrames);
            return;
        }

        setBusy(true, "Sintetizando e aplicando stretch…");
        AudioData base = audio;
        int start = selectionStart;
        int end = selectionEnd;
        worker.execute(() -> {
            try {
                AudioData stretched = StretchEngineV2.stretchRegion(base, start, end, targetFrames);
                mainHandler.post(() -> {
                    setBusy(false, "Stretch aplicado.");
                    applyProcessedAudio(stretched, targetFrames);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, "Falha no stretch.");
                    showError("Não foi possível aplicar o stretch", e);
                });
            }
        });
    }

    private void applyProcessedAudio(AudioData stretched, int targetFrames) {
        audio = stretched;
        selectionEnd = selectionStart + targetFrames;
        playheadFrame = selectionStart;
        auditionStart = -1;
        waveformView.setAudio(audio);
        waveformView.setSelection(selectionStart, selectionEnd);
        waveformView.setPlayhead(playheadFrame);
        waveformView.setAuditionStart(-1);
        markerLabel.setText("Marcador azul: não definido");
        waitingForSecondCut = false;
        cutButton.setText("Corte início");
        invalidatePreview();
        statusLabel.setText("Stretch aplicado. O áudio fora dos cortes continua sem time-stretch.");
        refreshUi();
    }

    private int readTargetFrames() {
        if (!hasFinishedSelection()) return -1;
        String text = durationInput.getText().toString().trim().replace(',', '.');
        if (text.isEmpty()) return -1;
        try {
            double seconds = Double.parseDouble(text);
            if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 120.0) return -1;
            long frames = Math.round(seconds * audio.sampleRate);
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
            Toast.makeText(this, "Por enquanto o app só alonga: a nova duração deve ser maior ou igual à original.", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void exportToFolder(Uri treeUri) {
        if (audio == null || busy) return;
        setBusy(true, "Exportando WAV 24-bit…");
        AudioData toWrite = audio;
        worker.execute(() -> {
            try {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String fileName = "VocalStretcher_v02_" + stamp + ".wav";
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
                    setBusy(false, "Salvo: " + fileName);
                    Toast.makeText(this, "Salvo: " + fileName, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, "Falha ao exportar.");
                    showError("Não foi possível exportar o WAV", e);
                });
            }
        });
    }

    private void stopPlaybackLabels() {
        player.stop();
        if (playButton != null) playButton.setText("Play");
    }

    private void invalidatePreview() {
        previewAudio = null;
        previewBaseAudio = null;
        previewTargetFrames = -1;
    }

    private boolean hasFinishedSelection() {
        return audio != null && !waitingForSecondCut && selectionStart >= 0 && selectionEnd > selectionStart;
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        statusLabel.setText(message);
        refreshUi();
    }

    private void refreshUi() {
        boolean hasAudio = audio != null;
        boolean selected = hasFinishedSelection();
        importButton.setEnabled(!busy);
        playButton.setEnabled(hasAudio && !busy);
        markStartButton.setEnabled(hasAudio && !busy);
        testStartButton.setEnabled(hasAudio && !busy && auditionStart >= 0);
        cutButton.setEnabled(hasAudio && !busy);
        previewButton.setEnabled(selected && !busy);
        applyButton.setEnabled(selected && !busy);
        durationInput.setEnabled(selected && !busy);
        exportButton.setEnabled(hasAudio && !busy);
        updateTimeLabel();
        updateSelectionLabel();
        updateMarkerLabel();
    }

    private void updateTimeLabel() {
        if (audio == null) {
            timeLabel.setText("00:00.000 / 00:00.000");
            return;
        }
        timeLabel.setText(formatTime(playheadFrame, audio.sampleRate) + " / "
                + formatTime(audio.frameCount(), audio.sampleRate));
    }

    private void updateMarkerLabel() {
        if (audio == null || auditionStart < 0) {
            markerLabel.setText("Marcador azul: não definido");
        } else {
            markerLabel.setText("Marcador azul: " + formatTime(auditionStart, audio.sampleRate)
                    + "  •  Testar daqui = 1,35 s");
        }
    }

    private void updateSelectionLabel() {
        if (audio == null || selectionStart < 0) {
            selectionLabel.setText("Nenhuma região selecionada");
        } else if (waitingForSecondCut || selectionEnd <= selectionStart) {
            selectionLabel.setText("Corte início: " + formatTime(selectionStart, audio.sampleRate) + " • escolha o fim");
        } else {
            double duration = (selectionEnd - selectionStart) / (double) audio.sampleRate;
            selectionLabel.setText(String.format(Locale.US, "Região: %s → %s  (%.3f s)",
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

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
