package app.fastnotes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic inputs to the same request builders used by the Android agent. */
public final class AgentPlanningCases {
  private static final long NOW = Instant.parse("2026-11-26T09:00:00Z").toEpochMilli();
  private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
  private static final String BASELINE = "qwen/qwen4-max";

  private AgentPlanningCases() {}

  public static void main(String[] args) throws Exception {
    String ready = "Лена уже заказала стол. После доставки собрать стол. "
        + "Игорю проверить крепления 2026-12-04 в 15:00.";
    String summary = "Экспонаты находятся в северном крыле. Вход со двора. "
        + "Съёмка разрешена без вспышки.";
    JSONArray cases = new JSONArray()
        .put(chat("ready", "plan", PlanChat.START, 1,
            List.of(note(1, "Стол", ready)), new JSONArray(),
            expect("стол", "собр", "креплен", "игор", "2026-12-04.*15:00")
                .put("done", "заказ.*стол|стол.*заказ")
                .put("order", new JSONArray(List.of("заказ", "собр", "креплен")))))
        .put(chat("goal", "plan", PlanChat.START, 1,
            List.of(note(1, "Выезд команды",
                "Организовать командный выезд для 15 участников без ночёвки.")), new JSONArray(),
            expect("предложени").put("minSteps", 3)))
        .put(chat("reference", "summary", "Кратко перескажи памятку", 1,
            List.of(note(1, "Музей", summary)), new JSONArray(),
            expect("северн", "со двора", "(?:без|отключ[а-яё]*) вспышк")))
        .put(chat("single", "plan", "Сделай чек-лист из этой записи", 1,
            List.of(note(1, "Библиотека", "Вернуть книги в библиотеку.")), new JSONArray(),
            expect("вернут", "книг").put("minSteps", 1).put("maxSteps", 1)))
        .put(chat("collection", "collection", "Собери обе заметки в одну сводку и чек-лист", 0,
            List.of(note(1, "Переезд", "Заказать машину. Окно загрузки 10:00–11:00."),
                note(2, "Коробки для переезда", "Подписать коробки по комнатам. "
                    + "Ключи передаст Оля.")), new JSONArray(),
            expect("машин", "короб", "10:00", "11:00", "оля|оле|оли")
                .put("planRequired", true).put("sources", new JSONArray(List.of(1, 2)))))
        .put(chat("blocked", "question",
            "Составь маршрут. Если не знаешь пункт назначения, сначала уточни его.", 1,
            List.of(note(1, "Маршрут", "Старт от вокзала, едем на машине.")), new JSONArray(),
            expect("куда|назначени|конечн|адрес")))
        .put(chat("followup", "plan", "Мастер уже выбран", 1,
            List.of(note(1, "Окно", "Вызвать мастера, после осмотра заказать уплотнитель.")),
            new JSONArray().put(turn("user", "Собери план замены уплотнителя"))
                .put(turn("assistant", "Мастер уже выбран?")),
            expect("осмотр", "уплотнител").put("order", new JSONArray(List.of("осмотр", "заказ")))))
        .put(review("review_mixed", "screen",
            List.of(note(1, "Стол", ready), note(2, "Музей", summary),
                note(3, "Счёт", "Оплатить счёт."),
                note(4, "Переезд", "Упаковать коробки, заказать машину."),
                note(5, "Выезд", "Организовать однодневный выезд команды.")),
            Set.of(4L), List.of(1, 5)))
        .put(chat("holdout_order", "plan", PlanChat.START, 1,
            List.of(note(1, "Репетиция", "Ася забронировала зал, готово. "
                + "Максу проверить звук 2026-12-03 в 18:30. Потом отправить запись Ире.")),
            new JSONArray(), expect("звук", "макс", "2026-12-03.*18:30", "ире", "запис")
                .put("done", "зал").put("order", new JSONArray(List.of("зал", "звук", "запис"))))
            .put("stage", "holdout"))
        .put(chat("holdout_reference", "summary", "Сделай краткую выжимку", 1,
            List.of(note(1, "Дежурный вход",
                "Утром открыт северный вход. После 19:00 вход через проходную Б. Парковка закрыта.")),
            new JSONArray(), expect("северн", "19:00", "проходн", "парковк"))
            .put("stage", "holdout"))
        .put(review("holdout_event", "holdout",
            List.of(note(1, "День рождения",
                    "Вечеринка 12 декабря. Купить торт, подтвердить число гостей и забрать свечи."),
                note(2, "Код двери", "Код 5170.")), Set.of(), List.of(1)))
        .put(review("holdout_done", "holdout",
            List.of(note(1, "Отчёт", "Подготовить таблицу, сверить цифры и отдать отчёт."),
                note(2, "Полка", "Заказали полку, забрали и собрали. Всё сделано."),
                note(3, "Проверка", "Проверить звук и отправить запись.")),
            Set.of(3L), List.of(1)));
    Files.writeString(Path.of(args[0]), cases.toString(2), StandardCharsets.UTF_8);
  }

  private static JSONObject expect(String... required) {
    return new JSONObject().put("required", new JSONArray(List.of(required)));
  }

  private static JSONObject turn(String role, String content) {
    return new JSONObject().put("role", role).put("content", content);
  }

  private static Note note(long id, String title, String body) {
    return new Note(id, title, body, NOW, NOW, 0);
  }

  private static JSONObject chat(String id, String kind, String query, long linked,
      List<Note> notes, JSONArray history, JSONObject expected) throws Exception {
    JSONObject payload = CommandAnalysis.payload(BASELINE, query, notes, List.of(), NOW, ZONE);
    JSONObject data = AgentChatPrompt.context(payload).put("linked_note", linked);
    JSONArray rows = data.getJSONArray("notes");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      if (row.getLong("id") == linked)
        row.put("text", Retrieval.excerpt(notes.get(i).body, query, 5000))
            .put("plan", PlanTree.parse("").forModel(ZONE));
    }
    AgentChatPrompt.payload(payload, query, history, data, 2000);
    return fixture(id, kind, notes, payload, expected).put("linked", linked);
  }

  private static JSONObject review(String id, String stage, List<Note> notes,
      Set<Long> planned, List<Integer> proposals) throws Exception {
    JSONObject payload = ReviewAnalysis.payload(notes, BASELINE, NOW, ZONE);
    ManagerAnalysis.withPlanPresence(payload, planned);
    ManagerAnalysis.withContext(payload, List.of(), Map.of());
    ManagerAnalysis.limit(payload, ManagerAnalysis.reviewTokens(0));
    return fixture(id, "review", notes, payload,
        new JSONObject().put("planning", new JSONArray(proposals))).put("stage", stage)
        .put("planned", new JSONArray(planned));
  }

  private static JSONObject fixture(String id, String kind, List<Note> notes, JSONObject payload,
      JSONObject expected) {
    JSONArray ids = new JSONArray();
    for (Note note : notes) ids.put(note.id);
    return new JSONObject().put("id", id).put("stage", "screen").put("kind", kind)
        .put("notes", ids).put("expected", expected).put("payload", payload);
  }
}
