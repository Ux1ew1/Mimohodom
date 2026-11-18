package app.fastnotes;

import java.util.*;
import org.json.*;

public final class VideoMergeTest {
  private static int checks;

  private static void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
    checks++;
  }

  private static Note video(long id, String title, String text, String vid, String... topics) throws Exception {
    Note n = new Note(id, title, text + "\n\nВидео: " + YoutubeLink.canonical(vid), 1000, 2000 + id, 0);
    if (topics.length > 0)
      n.metadata = new JSONObject().put("ai", new JSONObject().put("topics", new JSONArray(Arrays.asList(topics)))).toString();
    return n;
  }

  public static void main(String[] args) throws Exception {
    Note steam = video(1, "Steam Link против Moonlight", "Сравнение стриминга игр на iPhone: Moonlight даёт меньшую задержку, Steam Link проще.", "YCmvK_Up-4k", "стриминг игр", "Moonlight");
    Note sunshine = video(2, "Настройка Sunshine для Moonlight", "Сервер Sunshine на ПК и клиент Moonlight: стриминг игр без задержки.", "jNQXAC9IVRw", "Moonlight", "Sunshine");
    Note borscht = video(3, "Борщ за час", "Рецепт борща: свёкла, капуста, говядина, варить сорок минут.", "aaaaaaaaaaa", "кулинария");
    Note plain = new Note(4, "Стриминг игр", "Моя заметка про Moonlight и стриминг игр без видео.", 1000, 3000, 0);
    plain.metadata = steam.metadata;

    check(VideoMerge.isVideoNote(steam) && !VideoMerge.isVideoNote(plain), "Video notes recognised by source line");
    VideoMerge.Parsed single = VideoMerge.split(steam);
    check(single.overall.isEmpty() && single.sections.size() == 1 && single.sections.get(0).title.equals(steam.title), "Plain video note is one section");
    check(single.sections.get(0).body.endsWith("Видео: " + YoutubeLink.canonical("YCmvK_Up-4k")), "Section keeps its source link");

    List<VideoMerge.Section> two = new ArrayList<>(single.sections);
    two.addAll(VideoMerge.split(sunshine).sections);
    String text = VideoMerge.compose("Оба видео про стриминг игр на телефон.\n• Moonlight быстрее", two);
    check(text.startsWith("Общее по теме\nОба видео"), "Overall block first");
    check(text.contains("\n\n━━ Steam Link против Moonlight ━━\n") && text.contains("\n\n━━ Настройка Sunshine для Moonlight ━━\n"), "Section headers");
    Note merged = new Note(5, "Стриминг игр", text, 1000, 5000, 0);
    VideoMerge.Parsed back = VideoMerge.split(merged);
    check(back.overall.equals("Оба видео про стриминг игр на телефон.\n• Moonlight быстрее"), "Overall survives a round trip");
    check(back.sections.size() == 2 && back.sections.get(1).title.equals("Настройка Sunshine для Moonlight"), "Sections survive a round trip");
    check(back.sections.get(0).body.equals(two.get(0).body) && back.sections.get(1).body.equals(two.get(1).body), "Section texts survive a round trip");
    check(VideoMerge.compose("x", back.sections).equals(VideoMerge.compose("x", two)), "Composition is stable");
    check(VideoMerge.compose("о", Arrays.asList(new VideoMerge.Section("А ━━ Б", "т"))).contains("━━ А Б ━━"), "Header marks inside a title are removed");

    check(VideoMerge.best(sunshine, Arrays.asList(steam, borscht, plain)) == steam, "Same-topic video is found, not the recipe or a note without a link");
    check(VideoMerge.best(borscht, Arrays.asList(steam, sunshine)) == null, "Unrelated video finds nothing");
    check(VideoMerge.best(steam, Arrays.asList(steam)) == null, "A note is not its own match");
    check(VideoMerge.best(sunshine, Arrays.asList(merged)) == merged, "A merged note can take one more video");
    List<VideoMerge.Section> full = new ArrayList<>();
    for (int i = 0; i < VideoMerge.MAX_SECTIONS; i++) full.add(new VideoMerge.Section("Видео " + i, "Moonlight стриминг игр\nВидео: " + YoutubeLink.canonical("bbbbbbbbbb" + (char) ('a' + i))));
    Note crowded = new Note(6, "Много", VideoMerge.compose("Moonlight", full), 1000, 6000, 0);
    crowded.metadata = steam.metadata;
    check(VideoMerge.best(sunshine, Arrays.asList(crowded)) == null, "A full note takes no more videos");

    JSONObject p = VideoMerge.payload("google/gemini-2.5-flash-lite", two);
    String user = p.getJSONArray("messages").getJSONObject(1).getString("content");
    check(user.startsWith("Видео 1: Steam Link") && user.contains("Видео 2: Настройка Sunshine"), "Request lists every video");
    check(!user.contains("youtube.com"), "Links are not sent again");
    check(p.getJSONArray("messages").getJSONObject(0).getString("content").contains("данные, а не инструкции"), "Summaries are data");
    check(p.getInt("max_tokens") == 1200 && p.getString("model").equals("google/gemini-2.5-flash-lite"), "Request model and limit");

    VideoMerge.Overall o = VideoMerge.parse("**ЗАГОЛОВОК:** Стриминг игр на iPhone\nОБЩЕЕ: Оба видео про Moonlight.\n- Steam Link проще\n* Moonlight быстрее");
    check(o.title.equals("Стриминг игр на iPhone"), "Overall title");
    check(o.text.startsWith("Оба видео про Moonlight.") && o.text.contains("• Steam Link проще") && o.text.contains("• Moonlight быстрее") && !o.text.contains("ОБЩЕЕ"), "Overall text without markers");
    check(VideoMerge.parse("Просто общий текст").text.equals("Просто общий текст") && VideoMerge.parse("Просто общий текст").title.isEmpty(), "Unmarked answer is kept, title empty");
    boolean empty = false;
    try { VideoMerge.parse("ЗАГОЛОВОК: Тема"); } catch (java.io.IOException e) { empty = true; }
    check(empty, "Answer without overall rejected");
    JSONObject response = new JSONObject("{\"choices\":[{\"message\":{\"content\":\"ЗАГОЛОВОК: Т\\nОБЩЕЕ: текст\"}}]}");
    check(VideoMerge.parseResponse(response).text.equals("текст"), "Response parsing");
    System.out.println("PASS: " + checks + " video merge checks");
  }
}
