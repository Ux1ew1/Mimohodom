package app.fastnotes;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Contract tests: intercept HTTPS in this JVM, never send real traffic or a paid request. */
public final class PolzaTest {
  static int count, requests, status = 200;
  static String reply = "", path = "";
  static ByteArrayOutputStream sent;
  static Map<String, String> headers;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static final class Memory implements Polza.Memory {
    final List<Note> notes = new ArrayList<>();
    final Map<String, String> cache = new HashMap<>();

    public List<Note> notes() {
      return notes;
    }

    public String cached(String key) {
      return cache.get(key);
    }

    public void cache(String key, String body) {
      cache.put(key, body);
    }
  }

  static String response(String answer) {
    return new JSONObject()
        .put(
            "choices",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("message", new JSONObject().put("content", answer))
                        .put("finish_reason", "stop")))
        .put("usage", new JSONObject().put("total_tokens", 123).put("cost_rub", 0.01))
        .toString();
  }

  public static void main(String[] args) throws Exception {
    URL.setURLStreamHandlerFactory(
        protocol ->
            protocol.equals("https")
                ? new URLStreamHandler() {
                  protected URLConnection openConnection(URL url) {
                    requests++;
                    path = url.toString();
                    sent = new ByteArrayOutputStream();
                    headers = new HashMap<>();
                    return new HttpURLConnection(url) {
                      public void disconnect() {}

                      public boolean usingProxy() {
                        return false;
                      }

                      public void connect() {}

                      public void setRequestProperty(String name, String value) {
                        headers.put(name, value);
                      }

                      public OutputStream getOutputStream() {
                        return sent;
                      }

                      public int getResponseCode() {
                        return status;
                      }

                      public InputStream getInputStream() {
                        return new ByteArrayInputStream(reply.getBytes(StandardCharsets.UTF_8));
                      }
                    };
                  }
                }
                : null);
    Memory memory = new Memory();
    for (int i = 1; i <= 20; i++)
      memory.notes.add(
          new Note(i, "Рецепт " + i, "мука " + "текст ".repeat(10000), 1000, 2000 + i, 0));
    reply = response("Рецепт [[note:20]]. Неверная ссылка [[note:999]].");
    Polza.Answer answer =
        Polza.ask(memory, "test-key", "test/model", "Какие есть рецепты?", new JSONArray(), 600);
    check(path.equals("https://polza.ai/api/v1/chat/completions"), "Documented endpoint");
    check(headers.get("Authorization").equals("Bearer test-key"), "Bearer header");
    JSONObject payload = new JSONObject(sent.toString("UTF-8"));
    check(
        payload.getInt("max_tokens") == 600 && payload.getString("model").equals("test/model"),
        "Model and output cap");
    String system = payload.getJSONArray("messages").getJSONObject(0).getString("content");
    JSONArray context = new JSONArray(system.substring(system.indexOf("ДАННЫЕ: ") + 8));
    check(context.length() == 8, "At most eight sources");
    int chars = 0;
    for (int i = 0; i < context.length(); i++)
      chars += context.getJSONObject(i).getString("text").length();
    check(chars <= 12000, "Bounded source text");
    check(
        answer.sources.length() == 1 && answer.sources.getJSONObject(0).getLong("id") == 20,
        "Reject invented source ID");
    check(answer.usage.contains("0.01"), "Provider cost display");
    int before = requests;
    Polza.Answer cached =
        Polza.ask(memory, "test-key", "test/model", "Какие есть рецепты?", new JSONArray(), 600);
    check(requests == before && cached.cached, "Identical query uses cache");
    memory.notes.get(0).updated++;
    Polza.ask(memory, "test-key", "test/model", "Какие есть рецепты?", new JSONArray(), 600);
    check(requests == before + 1, "Note update invalidates cache");
    JSONArray history = new JSONArray();
    for (int i = 0; i < 12; i++)
      history.put(
          new JSONObject()
              .put("role", i % 2 == 0 ? "user" : "assistant")
              .put("content", "сообщение " + i));
    Polza.ask(memory, "test-key", "test/model", "Что ещё?", history, 300);
    check(
        new JSONObject(sent.toString("UTF-8")).getJSONArray("messages").length() == 8,
        "History limited to six messages plus system and question");
    status = 402;
    before = requests;
    try {
      Polza.ask(memory, "test-key", "other/model", "Ошибка", new JSONArray(), 600);
      throw new AssertionError("Expected balance failure");
    } catch (IOException expected) {
      check(expected.getMessage().contains("балансе"), "Useful balance error");
    }
    check(requests == before + 1, "No automatic retry on paid request");
    status = 200;
    reply = response("");
    int cacheSize = memory.cache.size();
    try {
      Polza.ask(memory, "test-key", "other/model", "Пустой", new JSONArray(), 600);
      throw new AssertionError("Expected empty failure");
    } catch (IOException expected) {
      check(memory.cache.size() == cacheSize, "Empty answer not cached");
    }
    reply = "{\"data\":[{\"id\":\"z/model\"},{\"id\":\"a/model\"}]}";
    check(
        Polza.models("test-key").get(0).equals("a/model") && path.endsWith("models?type=chat"),
        "Model catalog contract");
    reply = "{\"amount\":\"1234.56000000\",\"available\":\"1200.06000001\"}";
    java.math.BigDecimal[] balance = Polza.balance("balance-key");
    check(path.equals("https://polza.ai/api/v2/balance"), "Balance uses documented v2 endpoint");
    check(
        headers.get("Authorization").equals("Bearer balance-key"), "Balance bearer authorization");
    check(
        balance[0].toPlainString().equals("1234.56000000")
            && balance[1].toPlainString().equals("1200.06000001"),
        "Wallet and available amounts retain decimal precision");
    reply = "{\"amount\":\"0\"}";
    try {
      Polza.balance("test-key");
      throw new AssertionError("Missing available must fail");
    } catch (JSONException expected) {
      check(true, "Missing balance is not displayed as zero");
    }
    check(Polza.wantsNote("Создай заметку из нашего разговора"), "Explicit note request");
    check(Polza.wantsNote("Сохрани это в заметку"), "Explicit save request");
    check(!Polza.wantsNote("Не создавай заметку"), "Negation does not create note");
    check(!Polza.wantsNote("Как создать заметку?"), "Instruction question is not authorization");
    check(!Polza.wantsNote("Привет!"), "Greeting does not create note");
    String draft = "[[draft]]{\"title\":\"План\",\"body\":\"Купить молоко\"}[[/draft]]";
    check(Polza.noteDraft(draft).getString("title").equals("План"), "Structured draft parsed");
    check(
        Polza.noteDraft("[[draft]]{\"title\":\"План\",\"body\":\"\"}[[/draft]]") == null,
        "Empty draft rejected");
    check(Polza.noteDraft("[[draft]]broken") == null, "Truncated draft rejected");
    reply = response("Полезный ответ. " + draft);
    Polza.Answer proposal =
        Polza.ask(
            new Memory(), "test-key", "chat/model", "Помоги составить план", new JSONArray(), 600);
    check(
        proposal.draft != null && !proposal.text.contains("[[draft]]"),
        "Draft separated from visible answer");
    check(path.endsWith("chat/completions"), "Conversation works with empty archive");
    final JSONObject[] localPayload = {null};
    Polza.Memory localMemory =
        new Polza.Memory() {
          public List<Note> notes() {
            return memory.notes;
          }

          public String cached(String key) {
            return null;
          }

          public void cache(String key, String value) {}

          public boolean localMode() {
            return true;
          }

          public JSONObject complete(String key, JSONObject payload) {
            localPayload[0] = payload;
            return new JSONObject(response("Локальный ответ"));
          }
        };
    int beforeLocal = requests;
    Polza.Answer localAnswer =
        Polza.ask(localMemory, "", "local-gguf:model", "Какие есть рецепты?", history, 300);
    check(requests == beforeLocal, "Local completion never invokes HTTP");
    check(
        localAnswer.text.equals("Локальный ответ") && localAnswer.usage.contains("без облачного"),
        "Local response tagged correctly");
    JSONArray localMessages = localPayload[0].getJSONArray("messages");
    check(localMessages.length() <= 4, "Local history bounded to two turns");
    String localSystem = localMessages.getJSONObject(0).getString("content");
    JSONArray localNotes =
        new JSONArray(localSystem.substring(localSystem.indexOf("ДАННЫЕ: ") + 8));
    check(localNotes.length() <= 2, "Local note context bounded");
    final JSONObject[] nanoPayload = {null};
    Memory nanoNotes = new Memory();
    nanoNotes.notes.add(new Note(41, "Рецепт блинов", "Мука, молоко и яйца", 1000, 2000, 0));
    Polza.Memory nanoMemory =
        new Polza.Memory() {
          public List<Note> notes() {
            return nanoNotes.notes;
          }

          public String cached(String key) {
            return null;
          }

          public void cache(String key, String value) {}

          public boolean localMode() {
            return true;
          }

          public boolean aicoreMode() {
            return true;
          }

          public JSONObject complete(String key, JSONObject payload) {
            nanoPayload[0] = payload;
            return new JSONObject(response("Нашёл: Рецепт блинов — нужны мука и молоко."));
          }
        };
    Polza.Answer nanoAnswer =
        Polza.ask(nanoMemory, "", "aicore:gemini-nano", "рецепт блинов", new JSONArray(), 256);
    String nanoSystem = nanoPayload[0].getJSONArray("messages").getJSONObject(0).getString("content");
    check(nanoSystem.contains("[[note:12]]") && nanoSystem.contains("[[draft]]"), "Nano prompt teaches links and drafts");
    check(nanoSystem.indexOf("ДАННЫЕ: ") > nanoSystem.indexOf("ПРАВИЛА"), "Nano data after rules");
    check(
        nanoAnswer.text.contains("Рецепт блинов [[note:41]]")
            && nanoAnswer.sources.length() == 1
            && nanoAnswer.sources.getJSONObject(0).getLong("id") == 41,
        "Mentioned note title gets a link when the model forgets the format");
    List<Note> linkNotes = List.of(new Note(7, "Блины", "x", 1, 2, 0), new Note(8, "Ёжик", "x", 1, 2, 0));
    check(
        Polza.linkMentionedNotes("Это блины и ёжик", linkNotes).equals("Это блины [[note:7]] и ёжик [[note:8]]"),
        "Case and yo insensitive title linking");
    check(
        Polza.linkMentionedNotes("Это блины [[note:7]]", linkNotes).equals("Это блины [[note:7]]"),
        "Existing link not duplicated");
    check(Polza.linkMentionedNotes("абв", List.of(new Note(9, "аб", "x", 1, 2, 0))).equals("абв"), "Short titles ignored");
    Memory indexedMemory = new Memory();
    indexedMemory.notes.add(new Note(101, "Блины", "Мука и молоко", 1000, 2000, 0));
    reply =
        response(
            "Рецепт найден [[note:101]]."
                + " [[index]]{\"notes\":[{\"id\":101,\"metadata\":{\"aliases\":[\"панкейки\"],\"created_at\":999}},{\"id\":999,\"metadata\":{\"aliases\":[\"чужая\"]}}]}[[/index]]");
    Polza.Answer enriched =
        Polza.ask(indexedMemory, "key", "test/model", "рецепт", new JSONArray(), 600);
    check(enriched.metadataUpdates.length() == 1, "Only retrieved note can receive AI metadata");
    check(
        enriched.metadataUpdates.getJSONObject(0).getLong("revision") == 2000,
        "Metadata update binds source revision");
    check(
        !enriched.metadataUpdates.getJSONObject(0).getJSONObject("metadata").has("created_at"),
        "Chat cannot overwrite creation timestamp");
    check(
        !enriched.text.contains("[[index]]") && enriched.sources.length() == 1,
        "Metadata stays hidden while source links remain");
    System.out.println("PASS: " + count + " Polza contract checks (no network)");
  }
}
