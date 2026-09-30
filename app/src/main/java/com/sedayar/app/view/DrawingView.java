package com.sedayar.app.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Custom drawing / handwriting canvas.
 * - Smooth quadratic strokes (great for handwriting with finger or stylus)
 * - Pen colors + stroke widths + eraser
 * - Undo / redo / clear
 * - Can load an existing PNG as the base layer when reopening a note
 */
public class DrawingView extends View {

    /** One recorded stroke; kept so undo/redo can re-render history. */
    private static class Stroke {
        final Path path = new Path();
        final Paint paint = new Paint();
    }

    public interface StrokesChangedListener {
        void onStrokesChanged(boolean canUndo, boolean canRedo);
    }

    private Bitmap baseBitmap;      // loaded PNG (existing drawing), or null
    private Bitmap bufferBitmap;    // working buffer = base + committed strokes
    private Canvas bufferCanvas;

    private final List<Stroke> strokes = new ArrayList<>();
    private final Deque<Stroke> redoStack = new ArrayDeque<>();

    private int currentColor = 0xFF1F2937;
    private float currentWidth = 12f;
    private boolean eraseMode = false;

    private Stroke active;
    private float lastX, lastY, prevMidX, prevMidY;
    private int pointerId = -1;

    private StrokesChangedListener listener;

    public DrawingView(Context context) {
        this(context, null);
    }

    public DrawingView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DrawingView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    // ---------------------------------------------------------------- config

    public void setStrokesChangedListener(StrokesChangedListener l) {
        listener = l;
        if (l != null) l.onStrokesChanged(canUndo(), canRedo());
    }

    public void setPenColor(int color) {
        currentColor = color;
        eraseMode = false;
    }

    public void setStrokeWidth(float widthPx) {
        currentWidth = Math.max(1f, widthPx);
    }

    public boolean isEraseMode() {
        return eraseMode;
    }

    public void setEraseMode(boolean erase) {
        eraseMode = erase;
    }

    public boolean canUndo() {
        return !strokes.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public boolean isEmpty() {
        return baseBitmap == null && strokes.isEmpty();
    }

    // -------------------------------------------------------------- painting

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            rebuild(w, h);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bufferBitmap != null) {
            canvas.drawBitmap(bufferBitmap, 0, 0, null);
        }
    }

    private void rebuild(int w, int h) {
        bufferBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bufferCanvas = new Canvas(bufferBitmap);
        drawBase();
        for (Stroke s : strokes) {
            bufferCanvas.drawPath(s.path, s.paint);
        }
        invalidate();
        notifyChanged();
    }

    private void drawBase() {
        if (baseBitmap == null || bufferCanvas == null) {
            return;
        }
        Bitmap scaled = Bitmap.createScaledBitmap(
                baseBitmap, bufferCanvas.getWidth(), bufferCanvas.getHeight(), true);
        bufferCanvas.drawBitmap(scaled, 0, 0, null);
        if (scaled != baseBitmap) {
            scaled.recycle();
        }
    }

    private void recommitAll() {
        if (bufferCanvas == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        bufferCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
        drawBase();
        for (Stroke s : strokes) {
            bufferCanvas.drawPath(s.path, s.paint);
        }
        invalidate();
        notifyChanged();
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onStrokesChanged(canUndo(), canRedo());
        }
    }

    // ------------------------------------------------------------------ api

    /** Loads an existing drawing PNG as the base layer (null clears it). */
    public void loadBase(String path) {
        baseBitmap = null;
        if (path != null) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 900 && bounds.outHeight / (sample * 2) >= 900) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            baseBitmap = BitmapFactory.decodeFile(path, opts);
        }
        strokes.clear();
        redoStack.clear();
        if (getWidth() > 0 && getHeight() > 0) {
            rebuild(getWidth(), getHeight());
        } else {
            invalidate();
        }
    }

    public void undo() {
        if (strokes.isEmpty()) {
            return;
        }
        redoStack.push(strokes.remove(strokes.size() - 1));
        recommitAll();
    }

    public void redo() {
        Stroke s = redoStack.poll();
        if (s == null) {
            return;
        }
        strokes.add(s);
        recommitAll();
    }

    public void clearAll() {
        strokes.clear();
        redoStack.clear();
        baseBitmap = null;
        recommitAll();
    }

    /** Renders the current canvas (base + strokes) into a PNG file. */
    public boolean saveTo(File file) {
        if (bufferBitmap == null) {
            return false;
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            bufferBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- touch

    private Stroke newStroke() {
        Stroke s = new Stroke();
        s.paint.setAntiAlias(true);
        s.paint.setStyle(Paint.Style.STROKE);
        s.paint.setStrokeJoin(Paint.Join.ROUND);
        s.paint.setStrokeCap(Paint.Cap.ROUND);
        s.paint.setStrokeWidth(eraseMode ? currentWidth * 3f : currentWidth);
        if (eraseMode) {
            s.paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        } else {
            s.paint.setColor(currentColor);
            s.paint.setXfermode(null);
        }
        return s;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int idx = event.getActionIndex();
                pointerId = event.getPointerId(idx);
                beginStroke(event.getX(idx), event.getY(idx));
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int idx = event.findPointerIndex(pointerId);
                if (idx < 0) {
                    return true;
                }
                int history = event.getHistorySize();
                for (int i = 0; i < history; i++) {
                    extendStroke(event.getHistoricalX(idx, i), event.getHistoricalY(idx, i));
                }
                extendStroke(event.getX(idx), event.getY(idx));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                endStroke();
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                if (event.getActionIndex() == event.findPointerIndex(pointerId)) {
                    endStroke();
                }
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    private void beginStroke(float x, float y) {
        active = newStroke();
        active.path.moveTo(x, y);
        active.path.lineTo(x + 0.01f, y + 0.01f); // visible dot on tap
        if (bufferCanvas != null) {
            bufferCanvas.drawPath(active.path, active.paint);
        }
        lastX = x;
        lastY = y;
        prevMidX = x;
        prevMidY = y;
        redoStack.clear();
        invalidate();
        notifyChanged();
    }

    private void extendStroke(float x, float y) {
        if (active == null) {
            return;
        }
        float midX = (lastX + x) / 2f;
        float midY = (lastY + y) / 2f;

        // Draw only the new segment directly onto the working buffer
        Path seg = new Path();
        seg.moveTo(prevMidX, prevMidY);
        seg.quadTo(lastX, lastY, midX, midY);
        if (bufferCanvas != null) {
            bufferCanvas.drawPath(seg, active.paint);
        }

        // ...and remember it in the stroke path for undo/redo re-rendering
        active.path.quadTo(lastX, lastY, midX, midY);

        prevMidX = midX;
        prevMidY = midY;
        lastX = x;
        lastY = y;
        invalidate();
    }

    private void endStroke() {
        if (active == null) {
            return;
        }
        strokes.add(active);
        active = null;
        invalidate();
        notifyChanged();
    }
}
