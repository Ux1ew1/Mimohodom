package app.fastnotes;

import android.content.Context;
import java.util.*;
import org.json.*;

final class ManagerStorageTest {
  static void run(Context context) throws Exception {
    String name = "manager-test-" + System.nanoTime() + ".db";
    try (Store store = new Store(context, name)) {
      ManagerStore m = new ManagerStore(store);
      long a = store.save(0, "Рецепт", "Мука", 12345), b = store.save(0, "Рецепт", "Молоко", 0);
      store.image(a, "fixture.jpg");
      List<Note> notes = Arrays.asList(store.note(a), store.note(b));
      long run = m.claim(notes);
      require(m.batch().isEmpty(), "Ambiguous requests not automatically retried");
      require(
          m.batch(true).size() == 2,
          "Manual review includes failed notes without resetting automatic retry protection");
      require(m.batch().isEmpty(), "Selecting a manual batch does not unblock automatic retries");
      m.retryFailed();
      require(m.batch().size() == 2, "Manual retry releases failed snapshots");
      run = m.claim(notes);
      List<JSONObject> proposals = proposal(notes);
      require(m.finish(run, notes, proposals) == 1, "Persist proposal");
      require(m.finish(run, notes, proposals) == 0, "Duplicate suppressed");
      long id = m.pending().get(0).getLong("id");
      long top = m.accept(id, "Общий рецепт", "Мука и молоко");
      require(m.accept(id, "Другой", "Другой") == top, "Acceptance idempotent");
      require(m.members(top).size() == 2 && m.top(a) == top, "Flat group created");
      require(
          m.finish(m.claim(notes), notes, proposal(notes)) == 0,
          "Already grouped sources are not suggested again");
      require(
          store.note(a).image.equals("fixture.jpg") && store.note(a).reminder == 12345,
          "Original attachment and reminder retained");
      m.toggle(top);
      require(m.expanded(top), "Expand persisted");
      m.toggle(top);
      require(!m.expanded(top), "Collapse persisted");
      long third = store.save(0, "Соль", "Соль", 0);
      List<Note> next = Arrays.asList(store.note(top), store.note(third));
      m.finish(m.claim(next), next, proposal(next));
      long nextTop =
          m.accept(m.pending().get(0).getLong("id"), "Полный рецепт", "Мука, молоко, соль");
      require(
          m.members(nextTop).size() == 4 && m.members(top).isEmpty(),
          "Groups flattened, previous summary retained");
      List<Note> grouped = Arrays.asList(store.note(nextTop), store.note(third));
      JSONObject snapshot = proposal(grouped).get(0).put("groups", m.groupSnapshot(grouped));
      store.save(a, "Рецепт", "Мука уточнённая", 12345);
      require(!m.current(snapshot), "Hidden member edit invalidates group snapshot");
      store.delete(b);
      require(m.members(nextTop).size() == 3, "Delete only one member");
      store.delete(nextTop);
      require(m.top(a) == 0 && store.note(top) != null, "Delete summary releases all originals");
      List<Note> stale = Arrays.asList(store.note(a), store.note(third));
      m.finish(m.claim(stale), stale, proposal(stale));
      long staleId = m.pending().get(0).getLong("id");
      store.save(a, "Изменено", "Новое содержание", 0);
      boolean rejected = false;
      try {
        m.accept(staleId, "Итог", "Старый текст");
      } catch (IllegalStateException e) {
        rejected = true;
      }
      require(rejected && m.pending().isEmpty(), "Stale proposal cannot mutate notes");
      List<Note> fresh = Arrays.asList(store.note(a), store.note(third));
      List<JSONObject> freshP = proposal(fresh);
      m.finish(m.claim(fresh), fresh, freshP);
      long dismissed = m.pending().get(0).getLong("id");
      m.state(dismissed, "dismissed");
      require(
          m.finish(m.claim(fresh), fresh, freshP) == 0 && m.pending().isEmpty(),
          "Dismissal survives repeat review");
      require(m.export("manager_proposals").length() > 0, "Proposals exported");
      // At least one seed outside the previous batch advances on the next pass.
      for (int i = 0; i < 12; i++) store.save(0, "Отдельная запись " + i, "Текст " + i, 0);
      List<Note> first = m.batch();
      m.finish(m.claim(first), first, Collections.emptyList());
      Set<Long> ids = new HashSet<>();
      for (Note n : first) ids.add(n.id);
      require(m.batch().stream().anyMatch(n -> !ids.contains(n.id)), "Review queue advances");
      long auto = store.save(0, "", "Мука и молоко", 0),
          extra = store.save(0, "Дополнение", "Соль", 0);
      List<Note> combined = Arrays.asList(store.note(auto), store.note(extra));
      List<JSONObject> combinedProposals = proposal(combined);
      for (JSONObject p : combinedProposals)
        ManagerStore.attachGroups(p, m.groupSnapshot(combined));
      Analysis.Result rename = new Analysis.Result();
      rename.title = "Блины";
      rename.reminder = System.currentTimeMillis() + 3600000;
      rename.reason = "Явная дата";
      Map<Long, Analysis.Result> results = new HashMap<>();
      results.put(auto, rename);
      results.put(extra, new Analysis.Result());
      ManagerStore.ReviewOutcome outcome =
          m.finishReview(m.claim(combined), combined, combinedProposals, results);
      require(
          outcome.renamed == 1 && outcome.reminders == 1 && outcome.added == 1,
          "All features applied from one response");
      require(
          store.note(auto).reminder == 0 && store.note(auto).suggestion > 0,
          "Reminder remains unconfirmed");
      JSONObject pending =
          m.pending().stream()
              .filter(
                  p ->
                      p.optString("type").equals("merge")
                          && p.optJSONArray("sources").optJSONObject(0).optLong("id") == auto)
              .findFirst()
              .orElseThrow();
      require(m.current(pending), "Same-response rename does not invalidate merge");
      require(
          m.accept(pending.getLong("id"), "Итог", "Мука, молоко, соль") > 0,
          "Merge remains acceptable after rename");
      long linkA = store.save(0, "Авто", "Папка в бардачке", 12345),
          linkB = store.save(0, "Масло", "Пробег 186612", 0);
      List<Note> linked = Arrays.asList(store.note(linkA), store.note(linkB));
      List<JSONObject> linkProposals = proposal(linked);
      JSONObject link = linkProposals.get(0);
      link.put("type", "link").put("title", "").put("body", "");
      ManagerStore.attachGroups(link, m.groupSnapshot(linked));
      m.finish(m.claim(linked), linked, linkProposals);
      JSONObject savedLink =
          m.pending().stream()
              .filter(p -> p.optString("type").equals("link"))
              .findFirst()
              .orElseThrow();
      long linkId = savedLink.getLong("id");
      m.state(linkId, "reviewed");
      require(
          m.pending().stream().anyMatch(p -> p.optLong("id") == linkId),
          "Previously viewed link is available for grouping");
      JSONObject localDraft =
          new JSONObject().put("title", "Общий рецепт").put("body", "Мука и молоко");
      long linkedTop =
          m.accept(linkId, localDraft.getString("title"), localDraft.getString("body"));
      require(m.members(linkedTop).size() == 2, "Link acceptance creates a stack");
      require(
          store.note(linkA).reminder == 12345 && store.note(linkA).body.equals("Папка в бардачке"),
          "Link grouping preserves original text and reminder");
      require(m.accept(linkId, "Повтор", "Повтор") == linkedTop, "Link acceptance is idempotent");
      require(
          m.pending().stream().noneMatch(p -> p.optLong("id") == linkId),
          "Accepted link leaves pending list");
      List<Note> eight = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        eight.add(store.note(store.save(0, "Архив авто " + i, "Машина: факт " + i, 0)));
      List<JSONObject> largeProposal = proposal(eight);
      ManagerStore.attachGroups(largeProposal.get(0), m.groupSnapshot(eight));
      m.finish(m.claim(eight), eight, largeProposal);
      JSONObject large =
          m.pending().stream()
              .filter(
                  p -> p.optString("identity").equals(largeProposal.get(0).optString("identity")))
              .findFirst()
              .orElseThrow();
      m.saveSummary(large, new JSONObject().put("cursor", 1).put("body", "Промежуточная выжимка"));
      require(
          m.summaryProgress(large.getLong("id")).getInt("cursor") == 1,
          "Summary progress survives reads");
      long eightTop = m.accept(large.getLong("id"), "Авто", "Выжимка восьми записей");
      List<Note> extension =
          Arrays.asList(
              store.note(eightTop),
              store.note(store.save(0, "Авто девять", "Машина: факт 9", 0)),
              store.note(store.save(0, "Авто десять", "Машина: факт 10", 0)));
      List<JSONObject> extensionProposal = proposal(extension);
      ManagerStore.attachGroups(extensionProposal.get(0), m.groupSnapshot(extension));
      m.finish(m.claim(extension), extension, extensionProposal);
      JSONObject more =
          m.pending().stream()
              .filter(
                  p ->
                      p.optString("identity")
                          .equals(extensionProposal.get(0).optString("identity")))
              .findFirst()
              .orElseThrow();
      require(
          m.summarySources(more).size() == 11,
          "Summary sees every hidden original and previous top");
      long allTop = m.accept(more.getLong("id"), "Все авто", "Выжимка всех записей");
      m.toggle(allTop);
      require(
          m.expanded(allTop) && m.members(allTop).size() == 11,
          "Expansion returns all ten originals and previous summary");
      for (Note original : eight)
        require(
            m.members(allTop).contains(original.id), "No original lost after repeated grouping");
      require(
          m.batch(true).stream().noneMatch(n -> m.top(n.id) != 0),
          "Both stack summary and members excluded from manual review");
      require(
          m.batch().stream().noneMatch(n -> m.top(n.id) != 0),
          "Stacks excluded from automatic review");
      require(
          store.pendingAnalysis().stream().noneMatch(n -> m.top(n.id) != 0),
          "Stacks excluded from legacy analysis");
      require(
          !store.claimAnalysis(store.note(allTop)), "Stack summary cannot be claimed for analysis");
      store.save(eight.get(0).id, "Исправленный исходник", "Исправление в стопке", 0);
      require(
          m.batch(true).stream().noneMatch(n -> m.top(n.id) != 0),
          "Editing a grouped source does not re-enable review");
      require(
          store.pendingAnalysis().stream().noneMatch(n -> m.top(n.id) != 0),
          "Edited grouped source excluded from legacy analysis");
      // Recreate a real v5 database by removing only the v6 additions, then reopen it.
      for (String table :
          new String[] {
            "note_members", "note_groups", "manager_review", "manager_proposals", "manager_runs"
          }) store.getWritableDatabase().execSQL("DROP TABLE " + table);
      store.getWritableDatabase().setVersion(5);
      store.close();
      try (Store upgraded = new Store(context, name)) {
        require(upgraded.note(a).body.equals("Новое содержание"), "v5 to v6 preserves content");
        require(new ManagerStore(upgraded).pending().isEmpty(), "v5 to v6 adds manager tables");
      }
    } finally {
      context.deleteDatabase(name);
    }
  }

  private static List<JSONObject> proposal(List<Note> notes) throws Exception {
    JSONArray ids = new JSONArray();
    for (Note n : notes) ids.put(n.id);
    JSONObject row =
        new JSONObject()
            .put("type", "merge")
            .put("sources", ids)
            .put("reason", "Общая тема")
            .put("title", "Итог")
            .put("body", "Общий текст");
    return ManagerAnalysis.parse(
        new JSONObject().put("suggestions", new JSONArray().put(row)).toString(), notes);
  }

  private static void require(boolean ok, String label) {
    if (!ok) throw new AssertionError(label);
  }
}
