package com.sedayar.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.sedayar.app.audio.Transcript;
import com.sedayar.app.data.Note;
import com.sedayar.app.databinding.ActivityLecturePlaybackBinding;
import com.sedayar.app.util.InsetsUtil;
import com.sedayar.app.view.ReplayView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecture playback: replays the handwritten page in sync with the class
 * audio — every stroke appears at the exact moment it was written, so a
 * note jotted at minute 30 shows up at minute 30. Tapping any stroke seeks
 * the audio to the moment it was written (tap-to-seek).
 */
public class LecturePlaybackActivity extends AppCompatActivity {

    private ActivityLecturePlaybackBinding binding;
    private MediaPlayer player;
    private final Handler tick = new Handler(Looper.getMainLooper());

    private long durationMs = 0;
    private boolean prepared = false;
    private boolean dragging = false;
    private int shownPage = -1;

    private final Runnable uiTick = new Runnable() {
        @Override
        public void run() {
            if (player != null && prepared && !dragging) {
                int pos = player.getCurrentPosition();
                binding.seek.setProgress(pos);
                binding.tvTime.setText(Transcript.shortTime(pos));
                binding.replayView.setPlayheadMs(pos);
                refreshPageBar();
            }
            tick.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLecturePlaybackBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.apply(binding.getRoot(), null);

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

        // ---- page image (only needed when there is no stroke timing data)
        boolean hasStrokes = loadTimings(note);
        if (!hasStrokes && note.hasDrawing() && new File(note.drawingPath).exists()) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleFor(note.drawingPath, 1600);
            pageBitmap = BitmapFactory.decodeFile(note.drawingPath, opts);
        }
        if (hasStrokes) {
            binding.replayView.setPages(parsedPages);
        } else {
            binding.replayView.setContent(pageBitmap, new ArrayList<>(),
                    1080, 1527);
        }
        parsedPages.clear(); // ownership transferred to the view
        shownPage = -1;
        refreshPageBar();

        binding.replayView.setOnStrokeTapListener(timeMs -> {
            if (!prepared) {
                return;
            }
            player.seekTo((int) timeMs);
            if (!player.isPlaying()) {
                player.start();
                binding.btnPlay.setIconResource(R.drawable.ic_pause);
                binding.btnPlay.setContentDescription(getString(R.string.cd_pause));
            }
            binding.seek.setProgress((int) timeMs);
            binding.tvTime.setText(Transcript.shortTime(timeMs));
            binding.replayView.setPlayheadMs(timeMs);
            refreshPageBar();
        });

        // page flip buttons: jump to the moment the page was born
        binding.btnPagePrev.setOnClickListener(v -> jumpToPage(
                binding.replayView.getActivePageIndex() - 1));
        binding.btnPageNext.setOnClickListener(v -> jumpToPage(
                binding.replayView.getActivePageIndex() + 1));

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
                binding.replayView.setPlayheadMs(0);
                refreshPageBar();
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
                    binding.replayView.setPlayheadMs(bar.getProgress());
                    refreshPageBar();
                }
            }
        });
    }

    // -------------------------------------------------------- stroke timing

    /** Parsed pages/strokes handed to the ReplayView in bind(). */
    private final List<ReplayView.RPage> parsedPages = new ArrayList<>();
    private Bitmap pageBitmap = null;

    private void jumpToPage(int index) {
        if (!prepared || index < 0 || index >= binding.replayView.getPageCount()) {
            return;
        }
        long t = binding.replayView.getPageBornMs(index);
        player.seekTo((int) t);
        if (!player.isPlaying()) {
            player.start();
            binding.btnPlay.setIconResource(R.drawable.ic_pause);
        }
        binding.seek.setProgress((int) t);
        binding.tvTime.setText(Transcript.shortTime(t));
        binding.replayView.setPlayheadMs(t);
        refreshPageBar();
    }

    /** Updates the page indicator + typed-text panel from the replay view. */
    private void refreshPageBar() {
        int active = binding.replayView.getActivePageIndex();
        if (active == shownPage) {
            return;
        }
        shownPage = active;
        int count = binding.replayView.getPageCount();
        boolean multi = count > 1;
        binding.pageBar.setVisibility(multi ? View.VISIBLE : View.GONE);
        if (multi) {
            binding.tvPage.setText(String.format(java.util.Locale.US,
                    getString(R.string.page_x_of_y), active + 1, count));
        }
        String text = binding.replayView.getActivePageText();
        if (text.trim().isEmpty()) {
            binding.cardPageText.setVisibility(View.GONE);
        } else {
            binding.tvPageText.setText(text);
            binding.cardPageText.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Parses the timing JSON into pages (v2 "pages[]") or a single v1 page.
     * Returns true when stroke data was found.
     */
    private boolean loadTimings(Note note) {
        if (!note.hasTiming()) {
            return false;
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

            if (root.optInt("version", 1) >= 2 && root.has("pages")) {
                return parsePages(root);
            }
            // ---- legacy v1: flat stroke list
            JSONArray strokes = root.optJSONArray("strokes");
            if (strokes == null) {
                return false;
            }
            int cw = root.optInt("canvasW", 0);
            int ch = root.optInt("canvasH", 0);
            List<ReplayView.RStroke> list = parseStrokes(strokes);
            if (list.isEmpty()) {
                return false;
            }
            ReplayView.RPage p = new ReplayView.RPage();
            p.canvasW = cw > 0 ? cw : 1080;
            p.canvasH = ch > 0 ? ch : 1920;
            p.strokes.addAll(list);
            parsedPages.add(p);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean parsePages(JSONObject root) {
        JSONArray pages = root.optJSONArray("pages");
        if (pages == null) {
            return false;
        }
        int defW = root.optInt("canvasW", 0);
        int defH = root.optInt("canvasH", 0);
        for (int i = 0; i < pages.length(); i++) {
            JSONObject pj = pages.optJSONObject(i);
            if (pj == null) {
                continue;
            }
            ReplayView.RPage p = new ReplayView.RPage();
            p.bornMs = Math.max(0, pj.optLong("bornMs", 0));
            p.text = pj.optString("text", "");
            p.canvasW = pj.optInt("canvasW", defW) > 0
                    ? pj.optInt("canvasW", defW) : (defW > 0 ? defW : 1080);
            p.canvasH = pj.optInt("canvasH", defH) > 0
                    ? pj.optInt("canvasH", defH) : (defH > 0 ? defH : 1527);
            JSONArray st = pj.optJSONArray("strokes");
            if (st != null) {
                p.strokes.addAll(parseStrokes(st));
            }
            parsedPages.add(p);
        }
        if (parsedPages.isEmpty()) {
            return false;
        }
        return parsedPages.get(0).strokes.isEmpty()
                ? parsedPages.size() > 1 : true;
    }

    private List<ReplayView.RStroke> parseStrokes(JSONArray strokes) {
        List<ReplayView.RStroke> out = new ArrayList<>();
        for (int i = 0; i < strokes.length(); i++) {
            JSONObject s = strokes.optJSONObject(i);
            if (s == null) {
                continue;
            }
            long t = s.optLong("t", -1);
            JSONArray pts = s.optJSONArray("p");
            if (pts == null || pts.length() < 2) {
                continue;
            }
            int count = pts.length() / 2;
            float[] xs = new float[count];
            float[] ys = new float[count];
            for (int p = 0; p < count; p++) {
                xs[p] = pts.optInt(p * 2);
                ys[p] = pts.optInt(p * 2 + 1);
            }
            out.add(ReplayView.buildStroke(
                    s.optInt("c", 0xFF1F2937),
                    (float) s.optDouble("w", 7f),
                    s.optInt("e", 0) == 1,
                    t, xs, ys));
        }
        return out;
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
}
