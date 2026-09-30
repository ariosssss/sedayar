package com.sedayar.app;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import com.sedayar.app.data.Note;
import com.sedayar.app.databinding.ActivityMainBinding;
import com.sedayar.app.databinding.SheetNoteOptionsBinding;
import com.sedayar.app.util.NoteColors;

import java.io.File;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private NotesAdapter adapter;
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        binding.recycler.setLayoutManager(
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL));
        binding.recycler.setItemAnimator(new DefaultItemAnimator());
        adapter = new NotesAdapter(this::openNote, this::showOptions);
        binding.recycler.setAdapter(adapter);

        binding.fab.setOnClickListener(v ->
                startActivity(new Intent(this, NoteEditorActivity.class)));

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        if (query.trim().isEmpty()) {
            SedayarApp.get().repository().getAll(this::render);
        } else {
            SedayarApp.get().repository().search(query.trim(), this::render);
        }
    }

    private void render(List<Note> notes) {
        adapter.submit(notes);
        boolean empty = notes == null || notes.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void openNote(Note note) {
        Intent intent = new Intent(this, NoteEditorActivity.class);
        intent.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, note.id);
        startActivity(intent);
    }

    // ------------------------------------------------------- note options

    private void showOptions(Note note) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        SheetNoteOptionsBinding sb = SheetNoteOptionsBinding.inflate(getLayoutInflater());

        sb.tvSheetPin.setText(note.pinned ? R.string.unpin : R.string.pin);
        sb.btnSheetPin.setOnClickListener(v -> {
            sheet.dismiss();
            SedayarApp.get().repository().setPinned(note.id, !note.pinned, this::load);
        });

        sb.btnSheetShare.setOnClickListener(v -> {
            sheet.dismiss();
            shareNote(note);
        });

        sb.btnSheetDelete.setOnClickListener(v -> {
            sheet.dismiss();
            confirmDelete(note);
        });

        View[] dots = {
                sb.sheetColor0, sb.sheetColor1, sb.sheetColor2,
                sb.sheetColor3, sb.sheetColor4, sb.sheetColor5
        };
        for (int i = 0; i < dots.length; i++) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(NoteColors.color(i));
            d.setStroke(dp(2), ContextCompat.getColor(this, R.color.stroke));
            dots[i].setBackground(d);
            final int idx = i;
            dots[i].setOnClickListener(v -> {
                sheet.dismiss();
                SedayarApp.get().repository().setColor(note.id, idx, this::load);
                Snackbar.make(binding.getRoot(), R.string.color_saved, Snackbar.LENGTH_SHORT).show();
            });
        }

        sheet.setContentView(sb.getRoot());
        sheet.show();
    }

    private void confirmDelete(Note note) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_msg)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (note.hasDrawing()) {
                        new File(note.drawingPath).delete();
                    }
                    SedayarApp.get().repository().delete(note, () -> {
                        Snackbar.make(binding.getRoot(), R.string.note_deleted,
                                Snackbar.LENGTH_SHORT).show();
                        load();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void shareNote(Note note) {
        String text = (note.title != null && !note.title.trim().isEmpty()
                ? note.title.trim() + "\n\n" : "")
                + (note.content == null ? "" : note.content.trim());

        boolean hasImage = note.hasDrawing() && new File(note.drawingPath).exists();
        if (text.trim().isEmpty() && !hasImage) {
            Toast.makeText(this, R.string.share_text_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        if (hasImage) {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".files", new File(note.drawingPath));
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_TEXT, text.trim());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text.trim());
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_via)));
    }

    // ------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView != null) {
            searchView.setQueryHint(getString(R.string.search_hint));
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String q) {
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    query = newText == null ? "" : newText;
                    load();
                    return true;
                }
            });
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_premium) {
            startActivity(new Intent(this, PremiumActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
