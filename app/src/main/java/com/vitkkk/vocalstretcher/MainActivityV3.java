package com.vitkkk.vocalstretcher;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

/**
 * v0.3 keeps the tested editor interaction from v0.2 but swaps the processing
 * backend to WORLD. This small subclass updates the visible labels without
 * duplicating the entire editor implementation.
 */
public class MainActivityV3 extends MainActivityV2 {
    private boolean rewritingStatus = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rewriteLabels(getWindow().getDecorView());
    }

    private void rewriteLabels(View view) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            String text = String.valueOf(textView.getText());
            if ("Vocal Stretcher v0.2".equals(text)) {
                textView.setText("Vocal Stretcher v0.3 • WORLD");
            } else if (textView instanceof Button && "Preview stretch".equals(text)) {
                textView.setText("Preview WORLD");
            }

            if ("Importe um áudio para começar.".equals(text)) {
                textView.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable editable) {
                        if (rewritingStatus) return;
                        String current = editable.toString();
                        String replacement = current
                                .replace("PSOLA/WSOLA progressivo", "WORLD vocoder")
                                .replace("stretch progressivo, sem repetir o bloco em fileira",
                                        "ressíntese WORLD: F0 + formantes + aperiodicidade, sem duplicar waveform")
                                .replace("Sintetizando e aplicando stretch…",
                                        "Ressintetizando a vogal com WORLD…");
                        if (!replacement.equals(current)) {
                            rewritingStatus = true;
                            textView.setText(replacement);
                            rewritingStatus = false;
                        }
                    }
                });
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) rewriteLabels(group.getChildAt(i));
        }
    }
}
