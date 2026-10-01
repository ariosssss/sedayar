package com.sedayar.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.sedayar.app.audio.Transcript;
import com.sedayar.app.data.Note;
import com.sedayar.app.databinding.ActivityLecturePlaybackBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lecture playback: shows the handwritten page while the class audio plays.
 * Tapping any stroke seeks the audio to the exact moment it was written —
 * so tapping a formula plays the professor explaining that formula.
 */
public class LecturePlaybackActivity extends AppCompatActivity {

    private ActivityLecturePlaybackBinding binding;
    private MediaPlayer player;
    private final Handler tick = new Handler(Looper.getMainLooper());

    private static class StrokePoint {
        float x, y;
        long t;
    }

    private final List<StrokePoint> points = new ArrayList<>();
    private long durationMs = 0;
    private boolean prepared = false;
    private boolean dragging = false;

    private final Runnable uiTick = new Runnable() {
        @Override
        public void run() {
            if (player != null && prepared && !dragging) {
                int pos = player.getCurrentPosition();
                binding.seek.setProgress(pos);
                binding.tvTime.setText(Transcript.shortTime(pos));
            }
            tick.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLecturePlaybackBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        long noteId = getIntent().getLongExtra(NoteEditorActivity.EXTRA_NOTE_ID, -1);
        if (noteId <= 0) {
            finish();
            return;
        }

        SedayarApp.get().repository().getById(noteId, note -> {
            if (note == null || isFinishing() || isDestroyed()) {
                if (note == null) {
                    finish();
                }
                return;
            }
            bind(note);
        });
    }

    private void bind(Note note) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(note.title);
        }

        // ---- page image
        Bitmap page = null;
        if (note.hasDrawing() && new File(note.drawingPath).exists()) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleFor(note.drawingPath, 1600);
            page = BitmapFactory.decodeFile(note.drawingPath, opts);
        }
        if (page != null) {
            binding.ivPage.setImageBitmap(page);
        }

        // ---- stroke timing data
        loadTimings(note);

        // ---- audio
        if (note.hasAudio() && new File(note.audioPath).exists()) {
            setupPlayer(note);
        } else {
            binding.btnPlay.setEnabled(false);
            binding.seek.setEnabled(false);
            Toast.makeText(this, R.string.nothing_recorded, Toast.LENGTH_SHORT).show();
        }
    }

    private void setupPlayer(Note note) {
        try {
            player = new MediaPlayer();
            player.setDataSource(note.audioPath);
            player.setOnPreparedListener(mp -> {
                prepared = true;
                durationMs = mp.getDuration();
                binding.seek.setMax((int) durationMs);
                binding.tvTotal.setText(Transcript.shortTime(durationMs));
                tick.post(uiTick);
            });
            player.setOnCompletionListener(mp -> {
                binding.btnPlay.setIconResource(R.drawable.ic_play);
                binding.btnPlay.setContentDescription(getString(R.string.cd_play));
                binding.seek.setProgress(0);
                binding.tvTime.setText(Transcript.shortTime(0));
            });
            player.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, R.string.transcription_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        binding.btnPlay.setOnClickListener(v -> {
            if (!prepared) {
                return;
            }
            if (player.isPlaying()) {
                player.pause();
                binding.btnPlay.setIconResource(R.drawable.ic_play);
                binding.btnPlay.setContentDescription(getString(R.string.cd_play));
            } else {
                player.start();
                binding.btnPlay.setIconResource(R.drawable.ic_pause);
                binding.btnPlay.setContentDescription(getString(R.string.cd_pause));
            }
        });

        binding.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) {
                    binding.tvTime.setText(Transcript.shortTime(value));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (prepared) {
                    player.seekTo(bar.getProgress());
                }
            }
        });
    }

    // -------------------------------------------------------- tap-to-seek

    private void loadTimings(Note note) {
        if (!note.hasTiming()) {
            return;
        }
        try {
            byte[] raw = new byte[(int) new File(note.timingPath).length()];
            java.io.FileInputStream fin = new java.io.FileInputStream(note.timingPath);
            int off = 0;
            while (off < raw.length) {
                int n = fin.read(raw, off, raw.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            fin.close();
            JSONObject root = new JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            durationMs = root.optLong("durationMs", 0);
            JSONArray strokes = root.optJSONArray("strokes");
            if (strokes == null) {
                return;
            }
            for (int i = 0; i < strokes.length(); i++) {
                JSONObject s = strokes.getJSONObject(i);
                long t = s.optLong("t", -1);
                JSONArray pts = s.optJSONArray("p");
                if (t < 0 || pts == null) {
                    continue;
                }
                for (int p = 0; p + 1 < pts.length(); p += 2) {
                    StrokePoint sp = new StrokePoint();
                    sp.x = pts.getInt(p);
                    sp.y = pts.getInt(p + 1);
                    sp.t = t;
                    points.add(sp);
                }
            }
        } catch (Exception ignored) {
        }

        binding.ivPage.setOnClickListener(v -> {
            if (points.isEmpty() || !prepared) {
                return;
            }
            ImageView iv = binding.ivPage;
            // Use the last touch location captured by OnTouchListener below
            float[] bmp = lastTouchToBitmap(iv, lastTouch[0], lastTouch[1]);
            if (bmp == null) {
                return;
            }
            StrokePoint best = nearest(bmp[0], bmp[1], dp(30));
            if (best != null) {
                player.seekTo((int) best.t);
                if (!player.isPlaying()) {
                    player.start();
                    binding.btnPlay.setIconResource(R.drawable.ic_pause);
                    binding.btnPlay.setContentDescription(getString(R.string.cd_pause));
                }
                binding.seek.setProgress((int) best.t);
                binding.tvTime.setText(Transcript.shortTime(best.t));
                binding.tvHint.setText(Transcript.shortTime(best.t));
            }
        });

        binding.ivPage.setOnTouchListener((v, event) -> {
            lastTouch[0] = event.getX();
            lastTouch[1] = event.getY();
            return false; // let the click listener fire after we record the point
        });
    }

    private final float[] lastTouch = new float[2];

    /** Converts a touch point in view coords to bitmap coords (fitCenter). */
    private float[] lastTouchToBitmap(ImageView iv, float vx, float vy) {
        Bitmap bmp = iv.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                ? ((android.graphics.drawable.BitmapDrawable) iv.getDrawable()).getBitmap()
                : null;
        if (bmp == null) {
            return null;
        }
        float vw = iv.getWidth();
        float vh = iv.getHeight();
        float scale = Math.min(vw / bmp.getWidth(), vh / bmp.getHeight());
        float dx = (vw - bmp.getWidth() * scale) / 2f;
        float dy = (vh - bmp.getHeight() * scale) / 2f;
        float bx = (vx - dx) / scale;
        float by = (vy - dy) / scale;
        if (bx < 0 || by < 0 || bx > bmp.getWidth() || by > bmp.getHeight()) {
            return null;
        }
        return new float[]{bx, by};
    }

    private StrokePoint nearest(float x, float y, float maxDistPx) {
        StrokePoint best = null;
        double bestD = maxDistPx * maxDistPx;
        for (StrokePoint p : points) {
            double d = (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y);
            if (d <= bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onDestroy() {
        super.onDestroy();
        tick.removeCallbacks(uiTick);
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static int sampleFor(String path, int target) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= target) {
            sample *= 2;
        }
        return sample;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
