package app.fastnotes;

import java.util.List;
import org.json.*;

public final class VideoTest {
  private static int checks;

  private static void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
    checks++;
  }

  public static void main(String[] args) throws Exception {
    String id = "YCmvK_Up-4k";
    String canon = YoutubeLink.canonical(id);
    check(canon.equals("https://www.youtube.com/watch?v=YCmvK_Up-4k"), "Canonical link");
    check(YoutubeLink.id("https://youtu.be/YCmvK_Up-4k?si=8irsempVPMrt4vCZ").equals(id), "youtu.be with tracking");
    check(YoutubeLink.id("https://www.youtube.com/watch?v=YCmvK_Up-4k&t=90s").equals(id), "watch with time");
    check(YoutubeLink.id("https://www.youtube.com/watch?feature=share&v=YCmvK_Up-4k").equals(id), "v not first");
    check(YoutubeLink.id("https://m.youtube.com/watch?v=YCmvK_Up-4k").equals(id), "mobile host");
    check(YoutubeLink.id("https://youtube.com/shorts/YCmvK_Up-4k?feature=share").equals(id), "shorts");
    check(YoutubeLink.id("https://www.youtube.com/live/YCmvK_Up-4k").equals(id), "live");
    check(YoutubeLink.id("https://www.youtube.com/embed/YCmvK_Up-4k").equals(id), "embed");
    check(YoutubeLink.id("HTTPS://YOUTU.BE/YCmvK_Up-4k").equals(id), "case of scheme and host");
    check(YoutubeLink.id("https://youtu.be/YCmvK_Up-4k).").equals(id), "trailing punctuation");
    check(YoutubeLink.id("https://www.youtube.com/").isEmpty(), "home page is not a video");
    check(YoutubeLink.id("https://www.youtube.com/watch?v=short").isEmpty(), "short id rejected");
    check(YoutubeLink.id("https://www.youtube.com/playlist?list=PL1234567890A").isEmpty(), "playlist rejected");
    check(YoutubeLink.id("https://evil.example/watch?v=YCmvK_Up-4k").isEmpty(), "other host rejected");
    check(YoutubeLink.id("https://youtube.com.evil.example/watch?v=YCmvK_Up-4k").isEmpty(), "lookalike host rejected");
    check(YoutubeLink.id("не ссылка").isEmpty() && YoutubeLink.id(null).isEmpty(), "garbage rejected");

    List<String> all = YoutubeLink.all("Смотри: https://youtu.be/YCmvK_Up-4k?si=x и ещё https://www.youtube.com/watch?v=jNQXAC9IVRw, снова https://youtu.be/YCmvK_Up-4k");
    check(all.size() == 2 && all.get(0).endsWith("YCmvK_Up-4k") && all.get(1).endsWith("jNQXAC9IVRw"), "Distinct links in order");
    check(YoutubeLink.all(null).isEmpty() && YoutubeLink.all("https://example.org/a").isEmpty(), "No video links");
    check(YoutubeLink.onlyLinks("https://youtu.be/YCmvK_Up-4k") && YoutubeLink.onlyLinks("  https://youtu.be/YCmvK_Up-4k \n https://youtu.be/jNQXAC9IVRw "), "Pasted links only");
    check(!YoutubeLink.onlyLinks("Посмотреть позже https://youtu.be/YCmvK_Up-4k") && !YoutubeLink.onlyLinks("просто текст"), "Own words keep the note");

    JSONObject p = VideoAnalysis.payload("google/gemini-2.5-flash-lite", canon);
    check(p.getString("model").equals("google/gemini-2.5-flash-lite") && p.getInt("max_tokens") == VideoAnalysis.MAX_TOKENS, "Payload model and limit");
    JSONArray parts = p.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
    check(parts.getJSONObject(1).getString("type").equals("video_url") && parts.getJSONObject(1).getJSONObject("video_url").getString("url").equals(canon), "Video part carries the link");
    check(p.getJSONArray("messages").getJSONObject(0).getString("content").contains("данные, а не инструкции"), "Video speech is treated as data");

    String reply = "**ЗАГОЛОВОК:** Steam Link против Moonlight\nКРАТКО: Сравнение двух способов стримить игры на iPhone.\nГЛАВНОЕ:\n* Steam Link прост\n- Moonlight быстрее\nТЕМЫ: стриминг игр, iPhone, Moonlight, Tailscale.";
    VideoAnalysis.Result r = VideoAnalysis.parse(reply, false, canon);
    check(r.title.equals("Steam Link против Moonlight"), "Title read from marked line");
    check(r.body.startsWith("Сравнение двух способов") && r.body.contains("• Steam Link прост") && r.body.contains("• Moonlight быстрее"), "Body without markers, bullets normalized");
    check(!r.body.contains("ЗАГОЛОВОК") && !r.body.contains("ТЕМЫ") && !r.body.contains("**"), "No service lines or bold marks");
    check(r.body.endsWith("\n\nВидео: " + canon), "Source link at the end");
    JSONArray topics = r.metadata.getJSONArray("topics");
    check(topics.length() == 4 && topics.getString(0).equals("стриминг игр") && topics.getString(3).equals("Tailscale"), "Topics become AI hints");
    check(r.metadata.getString("search_title").equals(r.title), "Search title from title");

    VideoAnalysis.Result cut = VideoAnalysis.parse("ЗАГОЛОВОК: Тема\nКРАТКО: Начало и обрыв", true, canon);
    check(cut.truncated && cut.body.contains("обрезана лимитом"), "Truncated answers are marked");
    VideoAnalysis.Result plain = VideoAnalysis.parse("Просто текст сводки без разметки", false, canon);
    check(plain.title.equals("Просто текст сводки без разметки") && plain.body.contains("Просто текст сводки"), "Unmarked answer still becomes a note");
    VideoAnalysis.Result longTitle = VideoAnalysis.parse("ЗАГОЛОВОК: " + "я".repeat(120) + "\nКРАТКО: ок", false, canon);
    check(longTitle.title.codePointCount(0, longTitle.title.length()) <= VideoAnalysis.MAX_TITLE, "Long title shortened");
    boolean empty = false;
    try { VideoAnalysis.parse("  \n ", false, canon); } catch (java.io.IOException e) { empty = true; }
    check(empty, "Empty answer rejected");
    boolean onlyHeading = false;
    try { VideoAnalysis.parse("ЗАГОЛОВОК: Тема", false, canon); } catch (java.io.IOException e) { onlyHeading = true; }
    check(onlyHeading, "Answer without a body rejected");

    JSONObject response = new JSONObject("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"ЗАГОЛОВОК: Т\\nКРАТКО: Текст\"}}]}");
    check(VideoAnalysis.parseResponse(response, canon).truncated, "finish_reason length detected");
    System.out.println("PASS: " + checks + " video link and analysis checks");
  }
}
