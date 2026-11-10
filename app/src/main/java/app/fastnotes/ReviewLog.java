package app.fastnotes;

import android.content.Context;
import org.json.*;

final class ReviewLog {
  static synchronized void record(Context context, String status) {
    var prefs = context.getSharedPreferences("settings", 0);
    long time = System.currentTimeMillis();
    JSONArray previous;
    try {
      previous = new JSONArray(prefs.getString("analysisHistory", "[]"));
    } catch (JSONException e) {
      previous = new JSONArray();
    }
    JSONArray history = new JSONArray();
    try {
      if (previous.length() == 0 && prefs.getLong("analysisTime", 0) > 0)
        previous.put(
            new JSONObject()
                .put("time", prefs.getLong("analysisTime", 0))
                .put("text", prefs.getString("analysisStatus", "")));
      history.put(new JSONObject().put("time", time).put("text", status));
      for (int i = 0; i < Math.min(29, previous.length()); i++) history.put(previous.get(i));
    } catch (JSONException ignored) {
    }
    prefs
        .edit()
        .putString("analysisHistory", history.toString())
        .putString("analysisStatus", status)
        .putLong("analysisTime", time)
        .apply();
  }
}
