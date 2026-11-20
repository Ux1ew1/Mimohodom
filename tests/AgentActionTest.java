package app.fastnotes;

import java.time.*;
import java.util.*;
import org.json.*;

/** Словарь действий агента, разбор ответа модели и правила журнала — без Android. */
public final class AgentActionTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
  static final long NOW =
      LocalDateTime.of(2026, 11, 20, 12, 0).atZone(ZONE).toInstant().toEpochMilli();

  static AgentAction.Scope scope() {
    // 1–4 пришли целиком, 5 — обрезанной (для merge не годится).
    return new AgentAction.Scope(NOW, ZONE)
        .note(1, true)
        .note(2, true)
        .note(3, true)
        .note(4, true)
        .note(5, false);
  }

  static AgentAction act(String json) {
    return AgentAction.parse(new JSONObject(json), scope());
  }

  static AgentAction old(String json) {
    return AgentAction.legacy(new JSONObject(json), scope());
  }

  public static void main(String[] args) throws Exception {
    kinds();
    name();
    create();
    group();
    trash();
    remind();
    ask();
    plan();
    checkIn();
    legacy();
    plans();
    journal();
    System.out.println("PASS: " + count + " agent action checks");
  }

  static void kinds() {
    check(AgentAction.Kind.values().length == 8, "eight kinds in the vocabulary");
    for (AgentAction.Kind k : AgentAction.Kind.values())
      check(AgentAction.Kind.of(k.wire) == k, "wire name round trip " + k);
    check(AgentAction.Kind.of("delete") == null, "no hard delete in the vocabulary");
    check(act("{\"action\":\"delete\",\"notes\":[1]}") == null, "unknown action dropped");
    check(act("{\"action\":\"name\",\"note\":1,\"title\":\"x\"}").automatic(), "name is automatic");
    for (String other :
        new String[] {
          "{\"action\":\"create\",\"title\":\"a\",\"body\":\"b\"}",
          "{\"action\":\"trash\",\"notes\":[1],\"reason\":\"empty\"}",
          "{\"action\":\"ask\",\"note\":1,\"question\":\"Когда?\"}"
        })
      check(!act(other).automatic(), "only name is automatic: " + other);
  }

  static void name() {
    AgentAction a = act("{\"action\":\"name\",\"note\":2,\"title\":\"  Купить хлеб  \"}");
    check(a.kind == AgentAction.Kind.NAME && a.note == 2, "name parsed");
    check(a.title.equals("Купить хлеб"), "title trimmed");
    check(a.sources().equals(List.of(2L)), "name source is its note");
    check(a.legacy.isEmpty(), "name is not a bell proposal");
    check(act("{\"action\":\"name\",\"note\":9,\"title\":\"x\"}") == null, "unknown note");
    check(act("{\"action\":\"name\",\"title\":\"x\"}") == null, "note required");
    check(act("{\"action\":\"name\",\"note\":1,\"title\":\" \"}") == null, "empty title");
    check(
        act("{\"action\":\"name\",\"note\":1,\"title\":\"" + "я".repeat(161) + "\"}") == null,
        "title too long");
    check(act("{\"type\":\"name\",\"note\":1,\"title\":\"x\"}") != null, "type accepted as alias");
  }

  static void create() {
    AgentAction a =
        act("{\"action\":\"create\",\"title\":\"Дело\",\"body\":\"Текст\",\"sources\":[3,1]}");
    check(a.kind == AgentAction.Kind.CREATE, "create parsed");
    check(a.notes.equals(List.of(1L, 3L)), "sources sorted");
    check(a.legacy.equals("task"), "create with sources shown as task");
    AgentAction plain = act("{\"action\":\"create\",\"title\":\"Дело\",\"body\":\"Текст\"}");
    check(plain != null && plain.notes.isEmpty(), "create without sources");
    check(plain.legacy.isEmpty(), "create without sources is not shown before stage 5");
    check(
        act("{\"action\":\"create\",\"title\":\"Дело\",\"body\":\"\"}") == null, "body required");
    check(
        act("{\"action\":\"create\",\"title\":\"Д\",\"body\":\"Т\",\"sources\":[1,1]}") == null,
        "duplicate source");
    check(
        act("{\"action\":\"create\",\"title\":\"Д\",\"body\":\"Т\",\"sources\":\"1\"}") == null,
        "sources must be an array");
    check(
        act("{\"action\":\"create\",\"title\":\"Д\",\"body\":\"Т\",\"folder\":\""
                + "п".repeat(61)
                + "\"}")
            == null,
        "folder too long");
    check(
        act("{\"action\":\"create\",\"title\":\"Д\",\"body\":\"Т\",\"folder\":\"Дом\"}")
            .folder
            .equals("Дом"),
        "folder kept");
  }

  static void group() {
    AgentAction a =
        act("{\"action\":\"group\",\"sources\":[2,1],\"title\":\"Отпуск\",\"body\":\"Сводка\"}");
    check(a.kind == AgentAction.Kind.GROUP && a.notes.equals(List.of(1L, 2L)), "group parsed");
    check(a.legacy.equals("merge"), "complete sources -> merge card");
    AgentAction partial =
        act("{\"action\":\"group\",\"sources\":[1,5],\"title\":\"Отпуск\",\"body\":\"Сводка\"}");
    check(partial.legacy.equals("link"), "cut source -> link card");
    check(
        act("{\"action\":\"group\",\"sources\":[1],\"title\":\"О\",\"body\":\"С\"}") == null,
        "group needs two");
    check(
        act("{\"action\":\"group\",\"sources\":[1,9],\"title\":\"О\",\"body\":\"С\"}") == null,
        "unknown source");
    check(
        act("{\"action\":\"group\",\"sources\":[1,2],\"title\":\"\",\"body\":\"С\"}") == null,
        "group needs a title");
    AgentAction.Scope many = new AgentAction.Scope(NOW, ZONE);
    for (long i = 1; i <= 9; i++) many.note(i, true);
    check(
        AgentAction.parse(
                new JSONObject(
                    "{\"action\":\"group\",\"sources\":[1,2,3,4,5,6,7,8,9],\"title\":\"О\",\"body\":\"С\"}"),
                many)
            == null,
        "at most eight sources");
  }

  static void trash() {
    AgentAction a = act("{\"action\":\"trash\",\"notes\":[4,3],\"reason\":\"duplicate\"}");
    check(a.kind == AgentAction.Kind.TRASH && a.notes.equals(List.of(3L, 4L)), "trash parsed");
    check(a.reason.equals("duplicate"), "trash reason kept");
    for (String why : AgentAction.TRASH_REASONS)
      check(
          act("{\"action\":\"trash\",\"notes\":[1],\"reason\":\"" + why + "\"}") != null,
          "allowed reason " + why);
    check(
        act("{\"action\":\"trash\",\"notes\":[1],\"reason\":\"old\"}") == null,
        "other reasons are not allowed");
    check(act("{\"action\":\"trash\",\"notes\":[],\"reason\":\"empty\"}") == null, "needs a note");
    check(act("{\"action\":\"trash\",\"reason\":\"empty\"}") == null, "notes required");
    check(a.legacy.isEmpty(), "trash is not shown before stage 3");
  }

  static void remind() {
    AgentAction a =
        act(
            "{\"action\":\"remind\",\"note\":1,\"times\":[\"2026-12-01 10:00\","
                + "\"2026-11-25T09:30:00+03:00\",\"2026-12-05\"],\"lead\":60}");
    check(a.kind == AgentAction.Kind.REMIND && a.times.size() == 3, "remind parsed");
    check(
        a.times.get(0)
            == LocalDateTime.of(2026, 11, 25, 9, 30).atZone(ZONE).toInstant().toEpochMilli(),
        "times sorted, offset understood");
    check(
        a.times.get(1)
            == LocalDateTime.of(2026, 12, 1, 10, 0).atZone(ZONE).toInstant().toEpochMilli(),
        "local time in the user zone");
    check(
        a.times.get(2)
            == LocalDateTime.of(2026, 12, 5, 9, 0).atZone(ZONE).toInstant().toEpochMilli(),
        "date only -> 09:00");
    check(a.lead == 60 && !a.separate, "lead kept, one note by default");
    check(
        act("{\"action\":\"remind\",\"note\":1,\"times\":[\"2026-12-01 10:00\","
                    + "\"2026-12-01 10:00\"]}")
                .times
                .size()
            == 1,
        "repeated time collapses");
    check(
        act("{\"action\":\"remind\",\"note\":1,\"times\":[\"2026-11-19 10:00\"]}") == null,
        "past time rejected");
    check(
        act("{\"action\":\"remind\",\"note\":1,\"times\":[\"завтра\"]}") == null,
        "unreadable time rejected");
    check(act("{\"action\":\"remind\",\"note\":1,\"times\":[]}") == null, "needs a time");
    check(
        act("{\"action\":\"remind\",\"note\":1,\"times\":[\"2026-12-01\"],\"lead\":7}") == null,
        "lead outside the list");
    check(
        act("{\"action\":\"remind\",\"note\":1,\"times\":[\"2026-12-01\",\"2026-12-02\"],"
                + "\"repeat\":1}")
            == null,
        "repeat only with one time");
    check(
        act("{\"action\":\"remind\",\"note\":1,\"times\":[\"2026-12-01\"],\"repeat\":2}").repeat
            == ReminderPolicy.REPEAT_WEEKLY,
        "weekly repeat");
    check(
        act("{\"action\":\"remind\",\"note\":1,\"times\":[\"2026-12-01\"],\"repeat\":9}") == null,
        "unknown repeat");
    check(
        act("{\"action\":\"remind\",\"note\":1,\"times\":[\"2026-12-01\",\"2026-12-02\"],"
                + "\"separate\":true}")
            .separate,
        "separate flag kept");
    JSONArray many = new JSONArray();
    for (int i = 1; i <= 21; i++) many.put("2027-01-" + String.format("%02d", i));
    check(
        AgentAction.parse(
                new JSONObject().put("action", "remind").put("note", 1).put("times", many),
                scope())
            == null,
        "at most twenty times");
    check(
        AgentAction.moment(NOW + 1000, ZONE) == NOW + 1000, "milliseconds accepted as they are");
  }

  static void ask() throws Exception {
    AgentAction a =
        act(
            "{\"action\":\"ask\",\"note\":3,\"question\":\"Когда выезд?\",\"kind\":\"when\","
                + "\"options\":[\"Утром\",\"утром\",\" Вечером \",\"\",\"Днём\",\"Ночью\",\"Завтра\"]}");
    check(a.kind == AgentAction.Kind.ASK && a.note == 3, "ask parsed");
    check(a.options.equals(List.of("Утром", "Вечером", "Днём", "Ночью")), "options deduped, ≤4");
    check(a.askKind.equals("when"), "question kind kept");
    check(a.legacy.equals("clarify"), "ask with options shown as clarify");
    JSONObject p = a.proposal();
    check(p.getString("type").equals("clarify"), "proposal type");
    check(p.getString("reason").equals("Когда выезд?"), "question shown as reason");
    check(p.getJSONArray("sources").getLong(0) == 3, "proposal source");
    AgentAction free = act("{\"action\":\"ask\",\"note\":3,\"question\":\"Кто едет?\"}");
    check(free != null && free.options.isEmpty(), "free answer question allowed");
    check(free.legacy.isEmpty(), "free answer is not a clarify card yet");
    check(free.proposal() == null, "no proposal without a card");
    check(act("{\"action\":\"ask\",\"note\":3,\"question\":\"\"}") == null, "question required");
    check(
        act("{\"action\":\"ask\",\"note\":3,\"question\":\"" + "в".repeat(301) + "\"}") == null,
        "question too long");
    check(act("{\"action\":\"ask\",\"question\":\"Когда?\"}") == null, "ask needs a note");
  }

  static void plan() {
    AgentAction a =
        act(
            "{\"action\":\"plan\",\"note\":1,\"tree\":[{\"kind\":\"step\",\"text\":\"Забронировать"
                + " базу\"},{\"kind\":\"time\",\"text\":\"Выезд\",\"at\":\"2026-12-12 10:00\"}]}");
    check(a != null && a.kind == AgentAction.Kind.PLAN, "plan as array");
    check(a.tree.getJSONArray("nodes").length() == 2, "plan nodes kept");
    check(
        act("{\"action\":\"plan\",\"note\":1,\"tree\":{\"nodes\":[{\"text\":\"Шаг\"}]}}") != null,
        "plan as object");
    check(act("{\"action\":\"plan\",\"note\":1,\"tree\":[]}") == null, "empty plan rejected");
    check(
        act("{\"action\":\"plan\",\"note\":1,\"tree\":[{\"text\":\" \"}]}") == null,
        "plan of blank items rejected");
    check(act("{\"action\":\"plan\",\"note\":1}") == null, "tree required");
  }

  static void checkIn() {
    AgentAction a = act("{\"action\":\"check_in\",\"question\":\"Как дела с ремонтом?\"}");
    check(a != null && a.note == 0, "check-in without a note");
    check(
        act("{\"action\":\"check_in\",\"note\":2,\"question\":\"Как?\"}").note == 2,
        "check-in about a note");
    check(
        act("{\"action\":\"check_in\",\"note\":0,\"question\":\"Как?\"}") != null,
        "note 0 means none");
    check(
        act("{\"action\":\"check_in\",\"note\":7,\"question\":\"Как?\"}") == null,
        "unknown note rejected");
    check(act("{\"action\":\"check_in\"}") == null, "question required");
  }

  static void legacy() {
    AgentAction merge =
        old(
            "{\"type\":\"merge\",\"sources\":[2,1],\"reason\":\"Одно дело\",\"title\":\"Т\","
                + "\"body\":\"Б\"}");
    check(merge.kind == AgentAction.Kind.GROUP && merge.legacy.equals("merge"), "merge -> group");
    check(
        old("{\"type\":\"merge\",\"sources\":[1,5],\"reason\":\"r\",\"title\":\"Т\",\"body\":\"Б\"}")
            == null,
        "merge needs complete sources");
    AgentAction link = old("{\"type\":\"link\",\"sources\":[1,5],\"reason\":\"Тема\"}");
    check(link != null && link.legacy.equals("link") && link.title.isEmpty(), "link without text");
    check(old("{\"type\":\"link\",\"sources\":[1],\"reason\":\"Тема\"}") == null, "link needs two");
    AgentAction task =
        old("{\"type\":\"task\",\"sources\":[4],\"reason\":\"r\",\"title\":\"Т\",\"body\":\"Б\"}");
    check(task.kind == AgentAction.Kind.CREATE && task.legacy.equals("task"), "task -> create");
    AgentAction question =
        old("{\"type\":\"question\",\"sources\":[4],\"reason\":\"r\",\"title\":\"Т\",\"body\":\"Б\"}");
    check(question.legacy.equals("question"), "question keeps its card");
    check(
        old("{\"type\":\"task\",\"sources\":[4],\"reason\":\"r\",\"title\":\"Т\"}") == null,
        "task needs a body");
    check(
        old("{\"type\":\"task\",\"sources\":[4],\"reason\":\"\",\"title\":\"Т\",\"body\":\"Б\"}")
            == null,
        "reason required");
    AgentAction clarify =
        old("{\"type\":\"clarify\",\"sources\":[3],\"reason\":\"Что это?\",\"options\":[\"А\",\"Б\"]}");
    check(clarify.kind == AgentAction.Kind.ASK && clarify.note == 3, "clarify -> ask");
    check(clarify.question.equals("Что это?"), "clarify question");
    check(
        old("{\"type\":\"clarify\",\"sources\":[3],\"reason\":\"Что?\",\"options\":[\"А\"]}")
            == null,
        "clarify needs two options");
    check(
        old("{\"type\":\"clarify\",\"sources\":[3,4],\"reason\":\"Что?\",\"options\":[\"А\",\"Б\"]}")
            == null,
        "clarify is about one note");
    check(old("{\"type\":\"rename\",\"sources\":[1],\"reason\":\"r\"}") == null, "unknown type");
    check(
        old("{\"type\":\"link\",\"sources\":[1,1],\"reason\":\"r\"}") == null, "duplicate source");
  }

  static void plans() throws Exception {
    String text =
        "```json\n{\"actions\":["
            + "{\"action\":\"name\",\"note\":1,\"title\":\"Первое\"},"
            + "{\"action\":\"name\",\"note\":1,\"title\":\"Второе\"},"
            + "{\"action\":\"bogus\"},"
            + "{\"action\":\"ask\",\"note\":2,\"question\":\"Когда?\",\"options\":[\"А\",\"Б\"]},"
            + "{\"action\":\"ask\",\"note\":2,\"question\":\"Где?\",\"options\":[\"А\",\"Б\"]},"
            + "{\"action\":\"trash\",\"notes\":[4],\"reason\":\"empty\"}"
            + "],\"suggestions\":["
            + "{\"type\":\"link\",\"sources\":[1,2],\"reason\":\"Тема\"},"
            + "{\"type\":\"task\",\"sources\":[3],\"reason\":\"r\",\"title\":\"Т\",\"body\":\"Б\"},"
            + "{\"type\":\"task\",\"sources\":[4],\"reason\":\"r\",\"title\":\"Т\",\"body\":\"Б\"}"
            + "]}\n```";
    List<AgentAction> all = AgentPlan.parse(text, scope());
    long names = all.stream().filter(a -> a.kind == AgentAction.Kind.NAME).count();
    check(names == 1, "one name per note");
    check(all.get(0).title.equals("Первое"), "first name wins");
    long asks = all.stream().filter(a -> a.kind == AgentAction.Kind.ASK).count();
    check(asks == 1, "one open question per note");
    check(all.size() == 1 + AgentPlan.MAX_PROPOSALS, "at most four actions with a button");
    List<AgentAction> shown = AgentPlan.proposals(all);
    check(shown.size() == 3, "bell shows ask, link and task; trash waits for stage 3");
    check(shown.stream().noneMatch(AgentAction::automatic), "name is never a card");
    check(AgentPlan.parse("{\"suggestions\":[]}", scope()).isEmpty(), "empty answer");
    check(AgentPlan.parse("{}", scope()).isEmpty(), "no sections");
    check(AgentPlan.unwrap("```\n{\"a\":1}\n```").equals("{\"a\":1}"), "fence removed");
    check(AgentPlan.unwrap(" {\"a\":1} ").equals("{\"a\":1}"), "plain text trimmed");
    boolean failed = false;
    try {
      AgentPlan.parse("не json", scope());
    } catch (JSONException e) {
      failed = true;
    }
    check(failed, "broken answer is an error, not silence");
  }

  static void journal() throws Exception {
    long day = AgentJournal.UNDO_WINDOW, t = NOW;
    check(AgentJournal.canUndo(AgentJournal.DONE, t, t), "undo right away");
    check(AgentJournal.canUndo(AgentJournal.DONE, t, t + day - 1), "undo just before a day");
    check(!AgentJournal.canUndo(AgentJournal.DONE, t, t + day), "no undo after a day");
    check(!AgentJournal.canUndo(AgentJournal.UNDONE, t, t), "no second undo");
    check(AgentJournal.canUndo(AgentJournal.DONE, t, t - 60_000), "clock moved back");
    check(!AgentJournal.expired(t, t + 30 * 86_400_000L), "kept 30 days");
    check(AgentJournal.expired(t, t + 30 * 86_400_000L + 1), "dropped after 30 days");
    check(AgentJournal.left(AgentJournal.DONE, t, t).equals("ещё 24 ч"), "full day left");
    check(AgentJournal.left(AgentJournal.DONE, t, t + day - 30 * 60_000).equals("ещё 30 мин"), "minutes");
    check(AgentJournal.left(AgentJournal.DONE, t, t + day - 1).equals("ещё 1 мин"), "at least a minute");
    check(AgentJournal.left(AgentJournal.DONE, t, t + day).isEmpty(), "nothing left");
    check(AgentJournal.left(AgentJournal.DONE, t, t - day).equals("ещё 24 ч"), "future capped");

    JSONObject name = AgentJournal.nameData(5, "Купить хлеб", "купить хлеб и молоко");
    check(
        AgentJournal.describe(AgentJournal.NAME, name)
            .equals("Название «Купить хлеб» вместо «купить хлеб и молоко»"),
        "name line");
    check(
        AgentJournal.describe(AgentJournal.NAME, AgentJournal.nameData(5, "А", ""))
            .equals("Название «А»"),
        "name without old title");
    check(
        AgentJournal.describe(AgentJournal.CREATE, AgentJournal.createData(7, "Дело", 3))
            .equals("Создана заметка «Дело»"),
        "create line");
    Map<Long, List<Long>> before = new LinkedHashMap<>();
    before.put(10L, List.of(11L, 12L));
    JSONObject group =
        AgentJournal.groupData(20, "Отпуск", List.of(1L, 10L, 11L, 12L), before, 4);
    check(
        AgentJournal.describe(AgentJournal.GROUP, group)
            .equals("Собрана стопка «Отпуск» из 4 заметок"),
        "group line");
    check(AgentJournal.before(group).equals(before), "old stacks round trip");
    check(AgentJournal.before(new JSONObject()).isEmpty(), "no old stacks");
    check(AgentJournal.notes(1).equals("заметки") && AgentJournal.notes(21).equals("заметки"), "из 1/21 заметки");
    check(AgentJournal.notes(11).equals("заметок") && AgentJournal.notes(5).equals("заметок"), "из 5/11 заметок");
    check(
        AgentJournal.describe(
                AgentJournal.ANSWER, AgentJournal.answerData(3, "Что это?", "Список покупок", 9))
            .equals("Ответ «Список покупок» на вопрос «Что это?»"),
        "answer line");
    for (String action :
        new String[] {
          AgentJournal.NAME, AgentJournal.CREATE, AgentJournal.GROUP, AgentJournal.ANSWER
        }) check(!AgentJournal.undoHint(action).isEmpty(), "undo hint for " + action);
  }
}
