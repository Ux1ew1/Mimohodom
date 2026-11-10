package app.fastnotes;

import android.content.Context;
import java.time.LocalDate;

final class ReviewBudget {
  static synchronized boolean reserve(Context context) {
    var prefs = context.getSharedPreferences("settings", 0);
    String day = LocalDate.now().toString();
    int count =
        day.equals(prefs.getString("analysisDay", "")) ? prefs.getInt("analysisCount", 0) : 0;
    if (!ReviewPolicy.allowed(count, prefs.getBoolean("unlimitedAi", false))) return false;
    return prefs.edit().putString("analysisDay", day).putInt("analysisCount", count + 1).commit();
  }
}
