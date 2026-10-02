package com.sedayar.app.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simple async repository: all Room calls run on a background thread
 * and results are delivered on the main thread.
 */
public class NotesRepository {

    public interface Callback<T> {
        void onResult(T result);
    }

    private final NoteDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public NotesRepository(Context context) {
        dao = NotesDb.get(context).noteDao();
    }

    public void getAll(Callback<List<Note>> cb) {
        executor.execute(() -> post(cb, dao.getAll()));
    }

    public void search(String query, Callback<List<Note>> cb) {
        executor.execute(() -> post(cb, dao.search(query)));
    }

    public void getById(long id, Callback<Note> cb) {
        executor.execute(() -> post(cb, dao.getById(id)));
    }

    public void save(Note note, Callback<Long> cb) {
        note.updatedAt = System.currentTimeMillis();
        executor.execute(() -> {
            long id;
            if (note.id == 0) {
                id = dao.insert(note);
            } else {
                dao.update(note);
                id = note.id;
            }
            post(cb, id);
        });
    }

    public void delete(Note note, Runnable done) {
        executor.execute(() -> {
            dao.delete(note);
            if (done != null) main.post(done);
        });
    }

    public void setPinned(long id, boolean pinned, Runnable done) {
        executor.execute(() -> {
            dao.setPinned(id, pinned, System.currentTimeMillis());
            if (done != null) main.post(done);
        });
    }

    public void setColor(long id, int colorIndex, Runnable done) {
        executor.execute(() -> {
            dao.setColor(id, colorIndex, System.currentTimeMillis());
            if (done != null) main.post(done);
        });
    }

    private <T> void post(Callback<T> cb, T value) {
        main.post(() -> cb.onResult(value));
    }
}
