package com.sedayar.app.fragment;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import com.sedayar.app.LecturePlaybackActivity;
import com.sedayar.app.NoteEditorActivity;
import com.sedayar.app.NotesAdapter;
import com.sedayar.app.R;
import com.sedayar.app.SedayarApp;
import com.sedayar.app.data.Note;
import com.sedayar.app.databinding.FragmentNotesBinding;
import com.sedayar.app.databinding.SheetNoteOptionsBinding;
import com.sedayar.app.util.NoteColors;

import java.io.File;
import java.util.List;

/**
 * Notes tab: staggered grid of all notes with search, pin, share, color.
 */
public class NotesFragment extends Fragment {

    private FragmentNotesBinding binding;
    private NotesAdapter adapter;
    private String query = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentNotesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.recycler.setLayoutManager(
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL));
        binding.recycler.setItemAnimator(new DefaultItemAnimator());
        adapter = new NotesAdapter(this::openNote, this::showOptions);
        binding.recycler.setAdapter(adapter);

        binding.fab.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), NoteEditorActivity.class)));

        load();
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    public void setSearchQuery(String q) {
        query = q == null ? "" : q;
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
        if (!isAdded() || binding == null) {
            return;
        }
        adapter.submit(notes);
        boolean empty = notes == null || notes.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void openNote(Note note) {
        Intent intent;
        if (note.isLecture()) {
            intent = new Intent(requireContext(), LecturePlaybackActivity.class);
        } else {
            intent = new Intent(requireContext(), NoteEditorActivity.class);
        }
        intent.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, note.id);
        startActivity(intent);
    }

    // ------------------------------------------------------- note options

    private void showOptions(Note note) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
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
            d.setStroke(dp(2), ContextCompat.getColor(requireContext(), R.color.stroke));
            dots[i].setBackground(d);
            final int idx = i;
            dots[i].setOnClickListener(v -> {
                sheet.dismiss();
                SedayarApp.get().repository().setColor(note.id, idx, this::load);
                Snackbar.make(binding.getRoot(), R.string.color_saved,
                        Snackbar.LENGTH_SHORT).show();
            });
        }

        sheet.setContentView(sb.getRoot());
        sheet.show();
    }

    private void confirmDelete(Note note) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_msg)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (note.hasDrawing()) {
                        new File(note.drawingPath).delete();
                    }
                    if (note.hasAudio()) {
                        new File(note.audioPath).delete();
                    }
                    if (note.hasTiming()) {
                        new File(note.timingPath).delete();
                    }
                    SedayarApp.get().repository().delete(note, () -> {
                        if (isAdded()) {
                            Snackbar.make(binding.getRoot(), R.string.note_deleted,
                                    Snackbar.LENGTH_SHORT).show();
                            load();
                        }
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
            Toast.makeText(requireContext(), R.string.share_text_empty,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        if (hasImage) {
            Uri uri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".files",
                    new File(note.drawingPath));
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
