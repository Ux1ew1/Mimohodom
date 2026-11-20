package app.fastnotes;

import java.util.*;
import org.json.*;

/**
 * Разбор ответа модели в список проверенных действий агента ({@link AgentAction}). Понимает и
 * новый словарь {@code {"actions":[...]}}, и прежний ответ проверки {@code {"suggestions":[...]}};
 * неверные действия отбрасываются по одному, остальные остаются. Пределы: не больше {@link
 * #MAX_PROPOSALS} действий с кнопкой за ответ, одно название и один открытый вопрос на заметку.
 * Чистая Java без Android.
 */
public final class AgentPlan {
  public static final int MAX_PROPOSALS = 4;

  private AgentPlan() {}

  /** Снимает обёртку {@code ```json ... ```}, если модель её добавила. */
  public static String unwrap(String text) {
    String clean = text == null ? "" : text.trim();
    if (clean.startsWith("```")) {
      int start = clean.indexOf('\n'), end = clean.lastIndexOf("```");
      if (start >= 0 && end > start) clean = clean.substring(start + 1, end);
    }
    return clean.trim();
  }

  public static List<AgentAction> parse(String text, AgentAction.Scope scope) throws JSONException {
    return parse(new JSONObject(unwrap(text)), scope);
  }

  public static List<AgentAction> parse(JSONObject root, AgentAction.Scope scope) {
    List<AgentAction> out = new ArrayList<>();
    Set<Long> named = new HashSet<>(), asked = new HashSet<>();
    int[] proposals = {0};
    JSONArray actions = root.optJSONArray("actions");
    for (int i = 0; actions != null && i < actions.length(); i++)
      keep(AgentAction.parse(actions.optJSONObject(i), scope), out, named, asked, proposals);
    JSONArray suggestions = root.optJSONArray("suggestions");
    for (int i = 0; suggestions != null && i < suggestions.length(); i++)
      keep(AgentAction.legacy(suggestions.optJSONObject(i), scope), out, named, asked, proposals);
    return out;
  }

  private static void keep(
      AgentAction a, List<AgentAction> out, Set<Long> named, Set<Long> asked, int[] proposals) {
    if (a == null) return;
    if (a.kind == AgentAction.Kind.NAME) {
      if (named.add(a.note)) out.add(a);
      return;
    }
    if (proposals[0] >= MAX_PROPOSALS) return;
    if (a.kind == AgentAction.Kind.ASK && !asked.add(a.note)) return;
    proposals[0]++;
    out.add(a);
  }

  /** Только действия, которые колокольчик умеет показать как прежнее предложение. */
  public static List<AgentAction> proposals(List<AgentAction> actions) {
    List<AgentAction> out = new ArrayList<>();
    for (AgentAction a : actions) if (!a.automatic() && !a.legacy.isEmpty()) out.add(a);
    return out;
  }
}
