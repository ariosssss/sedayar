package com.sedayar.app.data;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A single note: text content + optional drawing (PNG) + color + pin state.
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
}
