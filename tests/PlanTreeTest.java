package app.fastnotes;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Дерево плана и чат по заметке без Android. */
public final class PlanTreeTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static long at(ZoneId zone, int y, int m, int d, int h, int min) {
    return ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli();
  }

  public static void main(String[] args) throws Exception {
    ZoneId zone = ZoneId.of("Europe/Moscow");

    // Ответ модели с планом: виды, время, вложенность, мусор
    String answer =
        "```json\n{\"say\":\"Вот план выезда.\",\"options\":[\"лишнее\"],\"plan\":{\"nodes\":["
            + "{\"kind\":\"time\",\"text\":\"Выезд\",\"at\":\"2026-06-12 10:00\"},"
            + "{\"kind\":\"time\",\"text\":\"Срок оплаты\",\"at\":\"2026-06-01\"},"
            + "{\"kind\":\"step\",\"text\":\"Забронировать базу\",\"who\":\"Аня\","
            + "\"at\":\"2026-06-02 18:00\",\"children\":["
            + "  {\"kind\":\"step\",\"text\":\"Сравнить три базы\",\"children\":["
            + "    {\"kind\":\"step\",\"text\":\"Позвонить\",\"children\":["
            + "      {\"kind\":\"step\",\"text\":\"Слишком глубоко\"}]}]}]},"
            + "{\"kind\":\"item\",\"text\":\"Палатки\",\"done\":true},"
            + "{\"kind\":\"person\",\"text\":\"Транспорт\",\"who\":\"Олег\"},"
            + "{\"kind\":\"wizard\",\"text\":\"Неизвестный вид\"},"
            + "{\"kind\":\"step\",\"text\":\"   \"},"
            + "{\"kind\":\"place\",\"text\":\"База «Сосны»\"}]}}\n```";
    PlanChat.Reply r = PlanChat.parse(answer, zone);
    check(r.say.equals("Вот план выезда."), "say");
    check(r.options.isEmpty(), "options dropped with plan");
    check(r.plan != null, "plan parsed");
    PlanTree t = r.plan;
    check(t.nodes.size() == 7, "empty node dropped: " + t.nodes.size());
    check(t.nodes.get(0).at == at(zone, 2026, 6, 12, 10, 0), "time parsed");
    check(t.nodes.get(1).at == at(zone, 2026, 6, 1, 9, 0), "date-only is 09:00");
    PlanTree.Node book = t.nodes.get(2);
    check(book.who.equals("Аня"), "who");
    long due = at(zone, 2026, 6, 2, 18, 0);
    check(book.at == due, "checkable step retains its own deadline");
    check(book.children.size() == 1, "child kept");
    PlanTree.Node compare = book.children.get(0);
    check(compare.children.size() == 2, "too deep flattened to level 3: " + compare.children.size());
    check(compare.children.get(1).text.equals("Слишком глубоко"), "flattened text");
    check(t.nodes.get(3).done, "item done");
    check(t.nodes.get(5).kind.equals(PlanTree.STEP), "unknown kind becomes step");
    check(t.badge().equals("План 1/6"), "badge: " + t.badge());

    // Номера уникальны, хранение и разбор туда-обратно
    List<PlanTree.Node> all = t.all();
    check(all.stream().map(n -> n.id).distinct().count() == all.size(), "ids unique");
    String stored = t.toJson();
    PlanTree back = PlanTree.parse(stored);
    check(back.toJson().equals(stored), "round trip");
    check(back.scheduledSteps(due, due + 1).equals(List.of(back.find(book.id))),
        "Stored deadline lists the step itself without scheduling its children");
    check(back.all().size() == all.size(), "same size");
    check(PlanTree.parse("не json").isEmpty(), "broken stored is empty");
    check(PlanTree.parse("").toJson().isEmpty(), "empty stays empty");

    // Правки
    String stepId = book.id;
    check(back.toggle(stepId), "toggle step");
    check(back.find(stepId).done, "toggled");
    String timeId = back.nodes.get(0).id;
    check(!back.toggle(timeId), "time not checkable");
    PlanTree.Node added = back.add(stepId, PlanTree.ITEM, "  Взять   договор ");
    check(added != null && added.text.equals("Взять договор"), "add clean");
    check(!added.id.isEmpty() && back.find(added.id) == added, "added has id");
    String deep = back.find(stepId).children.get(0).children.get(0).id;
    check(back.add(deep, PlanTree.STEP, "четвёртый уровень") == null, "depth limit on add");
    check(back.edit(added.id, "Договор аренды"), "edit");
    check(!back.edit(added.id, "   "), "empty edit refused");
    check(back.remove(added.id) && back.find(added.id) == null, "remove");

    // Отметки переносятся в новый план от модели
    PlanTree next =
        PlanTree.fromModel(
            new JSONObject(
                "{\"nodes\":[{\"kind\":\"step\",\"text\":\"забронировать  БАЗУ\"},"
                    + "{\"kind\":\"item\",\"text\":\"Палатки\"},{\"kind\":\"step\",\"text\":\"Новое\"}]}"),
            zone);
    next.keepDone(back);
    check(next.nodes.get(0).done, "done kept by text");
    check(next.nodes.get(1).done, "item done kept");
    check(!next.nodes.get(2).done, "new not done");

    // Модель видит план: время текстом, отметки
    JSONObject seen = back.forModel(zone);
    check(seen.toString().contains("2026-06-12 10:00"), "time for model");
    check(seen.getJSONArray("nodes").getJSONObject(2).getBoolean("done"), "done for model");

    // Вопрос с вариантами
    PlanChat.Reply q =
        PlanChat.parse(
            "{\"say\":\"С кем едете?\",\"options\":[\"Одна\",\"С командой\",\"одна\",\"a|b]]\","
                + "\"\",\"5\",\"6\"],\"plan\":null}",
            zone);
    check(q.plan == null, "no plan");
    check(q.options.size() == 4, "options limited and unique: " + q.options);
    check(q.options.get(2).equals("a/b"), "option cleaned: " + q.options.get(2));
    String body = PlanChat.store(q);
    PlanChat.Split s = PlanChat.split(body);
    check(s.text.equals("С кем едете?"), "split text");
    check(s.options.equals(q.options), "split options");
    check(PlanChat.forModel(body).contains("Варианты ответа: Одна / С командой"), "options for model");
    check(PlanChat.split("Просто текст [[ask:a|b]] и дальше").options.isEmpty(), "ask only at end");

    // Не JSON — обычный текст
    PlanChat.Reply plain = PlanChat.parse("Расскажите подробнее, пожалуйста.", zone);
    check(plain.say.equals("Расскажите подробнее, пожалуйста.") && plain.plan == null, "plain text");
    check(PlanChat.parse("{\"plan\":{\"nodes\":[{\"text\":\"Шаг\"}]}}", zone).say.startsWith("Вот план"), "default say");

    // Запрос: свежая заметка, текущий план, история без разметки, новое сообщение в конце
    JSONArray history =
        new JSONArray()
            .put(new JSONObject().put("role", "user").put("content", PlanChat.START))
            .put(new JSONObject().put("role", "assistant").put("content", body));
    JSONObject payload =
        PlanChat.payload(
            "m", "Выезд", "Едем на базу", back, history, "С командой", at(zone, 2026, 5, 20, 12, 0), zone);
    JSONArray messages = payload.getJSONArray("messages");
    check(messages.length() == 5, "messages count");
    check(messages.getJSONObject(1).getString("content").contains("Едем на базу"), "note text");
    check(messages.getJSONObject(1).getString("content").contains("Забронировать базу"), "plan in context");
    check(!messages.getJSONObject(3).getString("content").contains("[[ask:"), "no markup to model");
    check(messages.getJSONObject(4).getString("content").equals("С командой"), "query last");
    check(messages.getJSONObject(0).getString("content").contains("20 мая 2026"), "today in prompt");

    // Строка для показа
    check(PlanTree.line(back.nodes.get(0), zone).startsWith("12 июн"), "line time: " + PlanTree.line(back.nodes.get(0), zone));
    check(back.outline(zone).get(2).startsWith("☑ 2 июн")
        && back.outline(zone).get(2).endsWith("Забронировать базу (Аня)"),
        "outline: " + back.outline(zone).get(2));

    // «Выполнено»: все шаги и «что взять» отмечены, остальные виды не трогаются
    PlanTree finished = PlanTree.parse(t.toJson());
    int open = finished.checkable() - finished.done();
    check(finished.completeAll() == open && open > 0, "complete all marks open items");
    check(finished.done() == finished.checkable(), "everything done");
    check(finished.completeAll() == 0, "second time nothing to do");
    check(finished.badge().equals("План 6/6"), "badge after complete: " + finished.badge());

    PlanTree dated = new PlanTree();
    PlanTree.Node period = dated.add(null, PlanTree.TIME, "Сегодня");
    period.at = 1000;
    PlanTree.Node inherited = dated.add(period.id, PlanTree.ITEM, "Взять билеты");
    PlanTree.Node later = dated.add(period.id, PlanTree.STEP, "Проверить бронь");
    dated.add(later.id, PlanTree.TIME, "Срок").at = 2000;
    PlanTree.Node explicit = dated.add(null, PlanTree.STEP, "Купить билеты");
    dated.add(explicit.id, PlanTree.TIME, "Срок").at = 1999;
    dated.add(null, PlanTree.STEP, "Без срока");
    check(dated.scheduledSteps(1000, 2000).equals(List.of(inherited, explicit)), "Only steps due in day");
    check(dated.scheduledSteps(2000, 3000).equals(List.of(later)), "Child deadline overrides parent day");
    check(PlanTree.parse(dated.toJson()).scheduledSteps(1000, 2000).size() == 2, "Dates survive storage");

    System.out.println("PlanTree tests passed: " + count);
  }
}
