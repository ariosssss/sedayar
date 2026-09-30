package com.sedayar.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.sedayar.app.data.Note;
import com.sedayar.app.databinding.ItemNoteBinding;
import com.sedayar.app.util.DateUtils;
import com.sedayar.app.util.NoteColors;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for the notes grid (staggered, like Google Keep).
 */
public class NotesAdapter extends RecyclerView.Adapter<NotesAdapter.NoteVH> {

    public interface OnNoteClick {
        void onClick(Note note);
    }

    public interface OnNoteLongClick {
        void onLongClick(Note note);
    }

    private final List<Note> items = new ArrayList<>();
    private final OnNoteClick click;
    private final OnNoteLongClick longClick;

    public NotesAdapter(OnNoteClick click, OnNoteLongClick longClick) {
        this.click = click;
        this.longClick = longClick;
    }

    public void submit(List<Note> notes) {
        items.clear();
        if (notes != null) {
            items.addAll(notes);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public NoteVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemNoteBinding binding = ItemNoteBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new NoteVH(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull NoteVH holder, int position) {
        Note note = items.get(position);

        String title = (note.title == null || note.title.trim().isEmpty())
                ? holder.b.getRoot().getResources().getString(R.string.untitled)
                : note.title.trim();
        holder.b.tvTitle.setText(title);

        String content = note.content == null ? "" : note.content.replace('\n', ' ').trim();
        if (content.isEmpty()) {
            holder.b.tvSnippet.setVisibility(View.GONE);
        } else {
            holder.b.tvSnippet.setVisibility(View.VISIBLE);
            holder.b.tvSnippet.setText(content);
        }

        holder.b.tvDate.setText(DateUtils.format(note.updatedAt));
        holder.b.ivPin.setVisibility(note.pinned ? View.VISIBLE : View.GONE);
        holder.b.card.setCardBackgroundColor(NoteColors.color(note.colorIndex));

        if (note.hasDrawing()) {
            Bitmap thumb = decodeThumbnail(note.drawingPath, 512);
            if (thumb != null) {
                holder.b.ivThumb.setVisibility(View.VISIBLE);
                holder.b.ivThumb.setImageBitmap(thumb);
            } else {
                holder.b.ivThumb.setVisibility(View.GONE);
            }
        } else {
            holder.b.ivThumb.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> click.onClick(note));
        holder.itemView.setOnLongClickListener(v -> {
            longClick.onLongClick(note);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class NoteVH extends RecyclerView.ViewHolder {
        final ItemNoteBinding b;

        NoteVH(ItemNoteBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }

    private static Bitmap decodeThumbnail(String path, int target) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, opts);
    }
}
