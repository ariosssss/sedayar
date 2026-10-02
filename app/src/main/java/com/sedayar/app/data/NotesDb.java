package com.sedayar.app.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Note.class}, version = 2, exportSchema = false)
public abstract class NotesDb extends RoomDatabase {

    private static volatile NotesDb INSTANCE;

    /** v1 -> v2: adds the lecture-mode columns (audio + stroke timings). */
    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE notes ADD COLUMN audio_path TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE notes ADD COLUMN timing_path TEXT DEFAULT NULL");
        }
    };

    public abstract NoteDao noteDao();

    public static NotesDb get(Context context) {
        if (INSTANCE == null) {
            synchronized (NotesDb.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    NotesDb.class,
                                    "sedayar.db")
                            .addMigrations(MIGRATION_1_2)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
