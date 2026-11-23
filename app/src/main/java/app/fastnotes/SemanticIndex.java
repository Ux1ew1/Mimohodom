package app.fastnotes;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** Локальный SQLite-индекс: пересчитывает только новые и изменённые заметки. */
final class SemanticIndex {
  private SemanticIndex() {}

  static final class Ranked {
    final List<Note> matches = new ArrayList<>();
    final Map<Long, Double> scores = new HashMap<>();
  }

  /** Вызывается из фонового запроса чата, а не из отрисовки списка заметок. */
  static synchronized Ranked rank(Store store, String query, List<Note> notes) {
    Ranked out = new Ranked();
    if (notes.isEmpty() || query.trim().isEmpty()) return out;
    SQLiteDatabase db = store.getWritableDatabase();
    Map<Long, Stored> stored = new HashMap<>();
    try (Cursor c = db.rawQuery("SELECT note_id,fingerprint,vector FROM note_embeddings", null)) {
      while (c.moveToNext())
        stored.put(c.getLong(0), new Stored(c.getString(1), c.getBlob(2)));
    }
    float[] question = SemanticEmbedding.embed(query);
    List<ContentValues> changed = new ArrayList<>();
    Map<Long, float[]> vectors = new HashMap<>();
    for (Note note : notes) {
      String fingerprint = SemanticEmbedding.fingerprint(note);
      Stored old = stored.get(note.id);
      float[] vector = old == null ? null : SemanticEmbedding.decode(old.vector);
      if (old == null || !old.fingerprint.equals(fingerprint) || vector == null) {
        vector = SemanticEmbedding.embed(SemanticEmbedding.text(note));
        ContentValues values = new ContentValues();
        values.put("note_id", note.id);
        values.put("fingerprint", fingerprint);
        values.put("vector", SemanticEmbedding.encode(vector));
        changed.add(values);
      }
      vectors.put(note.id, vector);
      double score = SemanticEmbedding.similarity(question, vector);
      note.semanticScore = score;
      out.scores.put(note.id, score);
      if (score >= SemanticEmbedding.MIN_SCORE) out.matches.add(note);
    }
    if (!changed.isEmpty()) {
      db.beginTransaction();
      try {
        for (ContentValues values : changed)
          db.insertWithOnConflict("note_embeddings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
    }
    out.matches.sort((a, b) -> {
      int cmp = Double.compare(b.semanticScore, a.semanticScore);
      return cmp != 0 ? cmp : Long.compare(b.updated, a.updated);
    });
    return out;
  }

  private static final class Stored {
    final String fingerprint;
    final byte[] vector;

    Stored(String fingerprint, byte[] vector) {
      this.fingerprint = fingerprint;
      this.vector = vector;
    }
  }
}
