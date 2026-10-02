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
 * Replays a handwritten notebook in sync with the lecture audio: every stroke
 * appears at the exact moment it was written (its recorded timestamp), and the
 * view follows the A4 pages — when the playhead crosses a page's birth moment
 * the sheet flips to that page. Tapping near a stroke reports the moment it
 * was written (tap-to-seek).
 *
 * Pages come from the JSON saved by the notebook ({@code pages[]}, v2) or a
 * flat stroke list (v1); an optional base PNG is shown for notes without
 * stroke data.
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

    /** One notebook page: born moment, typed text and its strokes. */
    public static final class RPage {
        public long bornMs = 0L;
        public String text = "";
        public final List<RStroke> strokes = new ArrayList<>();
        public int canvasW = 1080;
        public int canvasH = 1527;
    }

    private final List<RPage> pages = new ArrayList<>();
    private int activePage = 0;
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

    /** Multi-page content (JSON v2). Pages must be sorted by bornMs. */
    public void setPages(List<RPage> pages) {
        this.page = null;
        this.pages.clear();
        if (pages != null) {
            this.pages.addAll(pages);
        }
        activePage = 0;
        playheadMs = 0L;
        layoutAndRecompose();
    }

    /**
     * Single-page content (v1 JSON or a static PNG). Used for notes recorded
     * before v1.3 and for notes without stroke data.
     */
    public void setContent(@Nullable Bitmap page, List<RStroke> strokes,
                           int srcW, int srcH) {
        this.page = page;
        RPage p = new RPage();
        p.canvasW = Math.max(1, srcW);
        p.canvasH = Math.max(1, srcH);
        if (strokes != null) {
            p.strokes.addAll(strokes);
        }
        this.pages.clear();
        this.pages.add(p);
        activePage = 0;
        playheadMs = 0L;
        layoutAndRecompose();
    }

    public void setOnStrokeTapListener(@Nullable OnStrokeTapListener l) {
        tapListener = l;
    }

    /** Audio position in ms; strokes written up to this moment are shown. */
    public void setPlayheadMs(long ms) {
        ms = Math.max(0, ms);
        // page flip: the active page is the last one born at/before the playhead
        int pageAt = 0;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).bornMs <= ms) {
                pageAt = i;
            }
        }
        boolean flipped = pageAt != activePage;
        activePage = pageAt;
        if (ms != playheadMs || flipped) {
            playheadMs = ms;
            if (flipped) {
                layoutAndRecompose(); // canvas dims may differ per page
            } else {
                recompose();
            }
        }
    }

    public long getPlayheadMs() {
        return playheadMs;
    }

    public int getPageCount() {
        return pages.size();
    }

    public int getActivePageIndex() {
        return activePage;
    }

    /** Typed text of the page currently shown (may be empty). */
    public String getActivePageText() {
        if (activePage < 0 || activePage >= pages.size()) {
            return "";
        }
        return pages.get(activePage).text == null
                ? "" : pages.get(activePage).text;
    }

    /** Audio moment the active page appeared (for page jump buttons). */
    public long getPageBornMs(int index) {
        if (index < 0 || index >= pages.size()) {
            return 0L;
        }
        return pages.get(index).bornMs;
    }

    public boolean hasStrokes() {
        for (RPage p : pages) {
            if (!p.strokes.isEmpty()) {
                return true;
            }
        }
        return false;
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
        if (vw <= 0 || vh <= 0 || pages.isEmpty()) {
            return;
        }
        RPage p = pages.get(Math.max(0, Math.min(activePage, pages.size() - 1)));
        canvasW = p.canvasW;
        canvasH = p.canvasH;
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
        if (bufferCanvas == null || pages.isEmpty()) {
            return;
        }
        RPage pg = pages.get(Math.max(0, Math.min(activePage, pages.size() - 1)));
        List<RStroke> strokes = pg.strokes;

        bufferCanvas.drawColor(0, PorterDuff.Mode.CLEAR);

        // paper background
        bufferCanvas.drawColor(0xFFFFFFFF);

        Matrix m = new Matrix();
        m.postTranslate(dx, dy);
        m.postScale(scale, scale, dx, dy);

        if (strokes.isEmpty() && page != null && !page.isRecycled()) {
            bufferCanvas.drawBitmap(page, m, null);
            invalidate();
            return;
        }

        for (RStroke s : strokes) {
            long t = s.t < 0 ? Math.max(0, pg.bornMs) : s.t;
            if (t > playheadMs) {
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
                        && tapListener != null
                        && activePage >= 0 && activePage < pages.size()
                        && !pages.get(activePage).strokes.isEmpty()) {
                    RStroke hit = nearest(event.getX(), event.getY(), dp(30));
                    if (hit != null) {
                        tapListener.onStrokeTap(hit.t < 0
                                ? pages.get(activePage).bornMs : hit.t);
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
        if (activePage < 0 || activePage >= pages.size()) {
            return null;
        }
        List<RStroke> strokes = pages.get(activePage).strokes;
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
