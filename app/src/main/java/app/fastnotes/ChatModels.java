package app.fastnotes;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;

final class ChatModels {
  private ChatModels() {}

  static String active(SharedPreferences settings, long chat) {
    return settings.getString("chatModel." + chat, settings.getString("model", "")).trim();
  }

  static List<String> saved(SharedPreferences settings) {
    LinkedHashSet<String> models = new LinkedHashSet<>();
    models.add(settings.getString("model", "").trim());
    models.add(settings.getString("reserveModel", "").trim());
    try {
      JSONArray saved = new JSONArray(settings.getString("savedModels", "[]"));
      for (int i = 0; i < saved.length(); i++) models.add(saved.optString(i).trim());
    } catch (JSONException ignored) {
      // Current settings are still available if the history is damaged.
    }
    models.remove("");
    return new ArrayList<>(models);
  }

  static void select(SharedPreferences settings, String setting, String model) {
    model = model.trim();
    List<String> saved = saved(settings);
    if (!model.isEmpty() && !saved.contains(model)) saved.add(model);
    SharedPreferences.Editor edit = settings.edit().putString("savedModels", new JSONArray(saved).toString());
    if (model.isEmpty() && setting.startsWith("chatModel.")) edit.remove(setting);
    else edit.putString(setting, model);
    edit.apply();
  }
}
