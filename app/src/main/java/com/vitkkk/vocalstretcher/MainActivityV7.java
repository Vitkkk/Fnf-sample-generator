package com.vitkkk.vocalstretcher;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** v0.5.8 test shell: same editor as v0.5.7, Wave Stretch engine underneath. */
public class MainActivityV7 extends MainActivityV6 {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        annotateWaveStretchBuild();
    }

    private void annotateWaveStretchBuild() {
        View contentView = findViewById(android.R.id.content);
        if (!(contentView instanceof ViewGroup)) return;
        ViewGroup content = (ViewGroup) contentView;
        if (content.getChildCount() == 0 || !(content.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) content.getChildAt(0);

        if (root.getChildCount() > 0 && root.getChildAt(0) instanceof TextView) {
            ((TextView) root.getChildAt(0)).setText("Vocal Stretcher v0.5.8");
        }

        TextView engine = new TextView(this);
        engine.setText("Motor desta build: WAVE STRETCH • waveform + erro quadrático");
        engine.setTextSize(12f);
        engine.setTextColor(Color.rgb(95, 65, 170));
        engine.setGravity(Gravity.CENTER_HORIZONTAL);
        engine.setPadding(0, 0, 0, dpLocal(5));
        root.addView(engine, Math.min(1, root.getChildCount()), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dpLocal(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
