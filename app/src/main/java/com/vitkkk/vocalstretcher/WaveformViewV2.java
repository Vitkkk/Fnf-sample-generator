package com.vitkkk.vocalstretcher;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

final class WaveformViewV2 extends View {
    interface OnPlayheadChangedListener {
        void onChanged(int frame);
    }

    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boundaryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint auditionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;

    private AudioData audio;
    private OnPlayheadChangedListener listener;
    private int playhead = 0;
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private int auditionStart = -1;
    private double framesPerPixel = 1.0;
    private double visibleStart = 0.0;
    private float lastTwoFingerCenterX = Float.NaN;

    WaveformViewV2(Activity context) {
        super(context);
        setBackgroundColor(Color.rgb(250, 250, 250));
        wavePaint.setColor(Color.rgb(25, 25, 25));
        wavePaint.setStrokeWidth(1f);
        centerPaint.setColor(Color.rgb(215, 215, 215));
        centerPaint.setStrokeWidth(1f);
        playheadPaint.setColor(Color.rgb(210, 32, 32));
        playheadPaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 1.5f);
        selectionPaint.setColor(Color.argb(52, 255, 145, 0));
        boundaryPaint.setColor(Color.rgb(235, 125, 0));
        boundaryPaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 1.6f);
        auditionPaint.setColor(Color.rgb(35, 95, 220));
        auditionPaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 2f);

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
        selectionStart = -1;
        selectionEnd = -1;
        auditionStart = -1;
        fitToScreen();
        invalidate();
    }

    void setPlayhead(int frame) {
        if (audio == null) return;
        playhead = clamp(frame, 0, audio.frameCount());
        ensureFrameVisible(playhead);
        invalidate();
    }

    void setSelection(int start, int end) {
        selectionStart = start;
        selectionEnd = end;
        invalidate();
    }

    void setAuditionStart(int frame) {
        auditionStart = frame;
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
                canvas.drawRect(new RectF(Math.min(x1, x2), 0, Math.max(x1, x2), height), selectionPaint);
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

        if (auditionStart >= 0) {
            float ax = frameToX(auditionStart);
            canvas.drawLine(ax, 0, ax, height, auditionPaint);
            float d = getResources().getDisplayMetrics().density;
            float[] tri = {ax - 5 * d, 0, ax + 5 * d, 0, ax, 8 * d};
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(tri[0], tri[1]);
            p.lineTo(tri[2], tri[3]);
            p.lineTo(tri[4], tri[5]);
            p.close();
            canvas.drawPath(p, auditionPaint);
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

    private void ensureFrameVisible(int frame) {
        if (audio == null || getWidth() <= 0) return;
        double visibleEnd = visibleStart + getWidth() * framesPerPixel;
        if (frame < visibleStart) visibleStart = frame;
        else if (frame > visibleEnd) visibleStart = frame - getWidth() * framesPerPixel * 0.8;
        clampViewport();
    }

    private void clampViewport() {
        if (audio == null || getWidth() <= 0) return;
        double visibleFrames = getWidth() * framesPerPixel;
        double maxStart = Math.max(0.0, audio.frameCount() - visibleFrames);
        visibleStart = Math.max(0.0, Math.min(maxStart, visibleStart));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
