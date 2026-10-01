package com.sedayar.app.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Replays a handwritten page in sync with the lecture audio: every stroke
 * appears at the exact moment it was written (its recorded timestamp), so a
 * note written at minute 30 appears at minute 30 — not at the beginning.
 * Tapping near a stroke reports the moment it was written (tap-to-seek).
 *
 * Strokes come from the JSON saved by {@link DrawingView} (canvas coordinates);
 * an optional base PNG is shown for notes without stroke data.
 */
public class ReplayView extends View {

    /** Reports the recorded audio time of the tapped stroke. */
    public interface OnStrokeTapListener {
        void onStrokeTap(long timeMs);
    }

    /** One replayable stroke (built from the recorded timing JSON). */
    public static final class RStroke {
        Path path;
        Paint paint;
        long t;
        float baseWidth;
        float[] xs, ys;
    }

    private final List<RStroke> strokes = new ArrayList<>();
    private Bitmap page;                 // static page (notes without timing data)
    private Bitmap buffer;
    private Canvas bufferCanvas;

    private int canvasW, canvasH;        // coordinate space of the stroke data
    private float scale = 1f, dx = 0f, dy = 0f;
    private long playheadMs = 0L;

    private OnStrokeTapListener tapListener;

    public ReplayView(Context context) {
        super(context);
    }

    public ReplayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ReplayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    // ------------------------------------------------------------------ setup

    /**
     * @param page     full page PNG (used only when strokes are empty)
     * @param strokeJsonParsed strokes; each with canvas-space points
     * @param srcW     width of the coordinate space the points are in
     * @param srcH     height of the coordinate space
     */
    public void setContent(@Nullable Bitmap page, List<RStroke> strokes,
                           int srcW, int srcH) {
        this.page = page;
        this.strokes.clear();
        if (strokes != null) {
            this.strokes.addAll(strokes);
        }
        this.canvasW = Math.max(1, srcW);
        this.canvasH = Math.max(1, srcH);
        playheadMs = 0L;
        layoutAndRecompose();
    }

    public void setOnStrokeTapListener(@Nullable OnStrokeTapListener l) {
        tapListener = l;
    }

    /** Audio position in ms; strokes written up to this moment are shown. */
    public void setPlayheadMs(long ms) {
        ms = Math.max(0, ms);
        if (ms != playheadMs) {
            playheadMs = ms;
            recompose();
        }
    }

    public long getPlayheadMs() {
        return playheadMs;
    }

    public boolean hasStrokes() {
        return !strokes.isEmpty();
    }

    // -------------------------------------------------------------- rendering

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            layoutAndRecompose();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (buffer != null) {
            canvas.drawBitmap(buffer, 0, 0, null);
        }
    }

    private void layoutAndRecompose() {
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        // fitCenter of the source canvas space into the view
        scale = Math.min(vw / (float) canvasW, vh / (float) canvasH);
        dx = (vw - canvasW * scale) / 2f;
        dy = (vh - canvasH * scale) / 2f;
        buffer = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
        bufferCanvas = new Canvas(buffer);
        recompose();
    }

    /** Rebuilds the buffer: visible strokes up to the playhead. */
    private void recompose() {
        if (bufferCanvas == null) {
            return;
        }
        bufferCanvas.drawColor(0, PorterDuff.Mode.CLEAR);

        // paper background
        bufferCanvas.drawColor(0xFFFFFFFF);

        Matrix m = new Matrix();
        m.postTranslate(dx, dy);
        m.postScale(scale, scale, dx, dy);

        if (strokes.isEmpty()) {
            if (page != null && !page.isRecycled()) {
                bufferCanvas.drawBitmap(page, m, null);
            }
            invalidate();
            return;
        }

        for (RStroke s : strokes) {
            if (s.t > playheadMs) {
                continue; // written later — not yet visible
            }
            s.paint.setStrokeWidth(Math.max(1f, s.baseWidth * scale));
            Path viewPath = new Path();
            viewPath.addPath(s.path, m);
            bufferCanvas.drawPath(viewPath, s.paint);
        }
        invalidate();
    }

    // ------------------------------------------------------------- tap-seek

    private float touchDownX, touchDownY;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (Math.abs(event.getX() - touchDownX) < 24
                        && Math.abs(event.getY() - touchDownY) < 24
                        && tapListener != null && !strokes.isEmpty()) {
                    RStroke hit = nearest(event.getX(), event.getY(), dp(30));
                    if (hit != null) {
                        tapListener.onStrokeTap(hit.t);
                        return true;
                    }
                }
                return false;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** Converts view coords to canvas coords and finds the nearest stroke. */
    private RStroke nearest(float viewX, float viewY, float maxDistPx) {
        float cx = (viewX - dx) / scale;
        float cy = (viewY - dy) / scale;
        float maxC = maxDistPx / scale;
        RStroke best = null;
        double bestD = (double) maxC * maxC;
        for (RStroke s : strokes) {
            float[] xs = s.xs, ys = s.ys;
            for (int i = 0; i < xs.length; i++) {
                double d = (xs[i] - cx) * (xs[i] - cx) + (ys[i] - cy) * (ys[i] - cy);
                if (d <= bestD) {
                    bestD = d;
                    best = s;
                }
            }
        }
        return best;
    }

    // ----------------------------------------------------------------- build

    /** Builds one replay stroke from JSON values (called by the activity). */
    public static RStroke buildStroke(int color, float width, boolean eraser,
                                      long timeMs, float[] xs, float[] ys) {
        RStroke s = new RStroke();
        s.t = timeMs;
        s.xs = xs;
        s.ys = ys;
        s.baseWidth = Math.max(1f, width);
        s.paint = new Paint();
        s.paint.setAntiAlias(true);
        s.paint.setStyle(Paint.Style.STROKE);
        s.paint.setStrokeJoin(Paint.Join.ROUND);
        s.paint.setStrokeCap(Paint.Cap.ROUND);
        s.paint.setStrokeWidth(Math.max(1f, width));
        if (eraser) {
            s.paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        } else {
            s.paint.setColor(color);
        }
        Path p = new Path();
        if (xs.length > 0) {
            p.moveTo(xs[0], ys[0]);
            for (int i = 1; i < xs.length; i++) {
                p.lineTo(xs[i], ys[i]);
            }
        }
        s.path = p;
        return s;
    }

    private float dp(int v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
