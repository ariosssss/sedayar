package com.sedayar.app.data;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A single note: text content + optional drawing (PNG) + color + pin state.
 * Lecture notes additionally carry an audio recording (m4a) and a stroke-timing
 * JSON file that links every handwritten stroke to a moment in the audio.
 */
@Entity(tableName = "notes")
public class Note {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "title")
    public String title;

    @ColumnInfo(name = "content")
    public String content;

    /** Absolute path of the drawing PNG, or null. */
    @ColumnInfo(name = "drawing_path")
    public String drawingPath;

    /** Absolute path of the lecture audio recording (m4a), or null. */
    @ColumnInfo(name = "audio_path")
    public String audioPath;

    /** Absolute path of the stroke-timing JSON used for tap-to-seek playback, or null. */
    @ColumnInfo(name = "timing_path")
    public String timingPath;

    /** Index into NoteColors palette. */
    @ColumnInfo(name = "color_index")
    public int colorIndex;

    @ColumnInfo(name = "pinned")
    public boolean pinned;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    public Note() {
        this.title = "";
        this.content = "";
    }

    public boolean hasDrawing() {
        return drawingPath != null && !drawingPath.isEmpty();
    }

    public boolean hasAudio() {
        return audioPath != null && !audioPath.isEmpty();
    }

    public boolean hasTiming() {
        return timingPath != null && !timingPath.isEmpty();
    }

    /** A lecture note = drawing + recorded audio + stroke timings. */
    public boolean isLecture() {
        return hasAudio() && hasTiming();
    }
}
