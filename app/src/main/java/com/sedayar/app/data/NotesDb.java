package com.sedayar.app.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {Note.class}, version = 1, exportSchema = false)
public abstract class NotesDb extends RoomDatabase {

    private static volatile NotesDb INSTANCE;

    public abstract NoteDao noteDao();

    public static NotesDb get(Context context) {
        if (INSTANCE == null) {
            synchronized (NotesDb.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    NotesDb.class,
                                    "sedayar.db")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
