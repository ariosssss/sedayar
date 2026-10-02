package com.sedayar.app.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface NoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Note note);

    @Update
    void update(Note note);

    @Delete
    void delete(Note note);

    @Query("SELECT * FROM notes ORDER BY pinned DESC, updated_at DESC")
    List<Note> getAll();

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    Note getById(long id);

    @Query("SELECT * FROM notes WHERE title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' "
            + "ORDER BY pinned DESC, updated_at DESC")
    List<Note> search(String q);

    @Query("UPDATE notes SET pinned = :pinned, updated_at = :updatedAt WHERE id = :id")
    void setPinned(long id, boolean pinned, long updatedAt);

    @Query("UPDATE notes SET color_index = :colorIndex, updated_at = :updatedAt WHERE id = :id")
    void setColor(long id, int colorIndex, long updatedAt);
}
