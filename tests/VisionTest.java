package app.fastnotes;

import java.time.*;
import org.json.*;

public final class VisionTest {
  static int count;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    count++;
  }

  static JSONObject answer(String date) throws Exception {
    return new JSONObject()
        .put("title", "Встреча")
        .put("summary", "Встреча 7 ноября в 18:00.")
        .put("reminder_at", date == null ? JSONObject.NULL : date)
        .put("reason", "7 ноября в 18:00");
  }

  public static void main(String[] args) throws Exception {
    long now = Instant.parse("2026-11-06T10:00:00Z").toEpochMilli();
    JSONObject payload =
        Vision.payload(
            "test/vision", "aGVsbG8=", "Что на картинке?", now, ZoneId.of("Europe/Moscow"));
    check(
        payload.getString("model").equals("test/vision") && payload.getInt("max_tokens") == 1400,
        "Model and cost cap");
    JSONArray parts = payload.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
    check(
        parts.length() == 2 && parts.getJSONObject(0).getString("text").equals("Что на картинке?"),
        "Question preserved");
    check(
        parts.getJSONObject(1).getString("type").equals("image_url")
            && parts
                .getJSONObject(1)
                .getJSONObject("image_url")
                .getString("url")
                .equals("data:image/jpeg;base64,aGVsbG8="),
        "Documented multimodal data URL");
    check(
        payload
            .getJSONArray("messages")
            .getJSONObject(0)
            .getString("content")
            .contains("Europe/Moscow"),
        "Timezone supplied");
    Vision.Result result = Vision.parse(answer("2026-11-07T18:00:00+03:00").toString(), now);
    check(result.title.equals("Встреча") && result.summary.contains("18:00"), "Note generated");
    check(
        result.reminder == Instant.parse("2026-11-07T15:00:00Z").toEpochMilli(),
        "Future proposal accepted");
    check(
        Vision.parse(answer("2026-11-01T18:00:00+03:00").toString(), now).reminder == 0,
        "Past proposal rejected");
    check(
        Vision.parse(answer("2026-11-07T18:00:00").toString(), now).reminder == 0,
        "Offset required");
    check(Vision.parse(answer(null).toString(), now).reminder == 0, "No reminder allowed");
    check(
        Vision.parse("```json\n" + answer(null) + "\n```", now).title.equals("Встреча"),
        "Fenced response supported");
    check(
        Vision.parse(answer("2026-11-07T18:00:00+03:00").put("reason", "").toString(), now).reminder
            == 0,
        "Reason required");
    for (JSONObject invalid :
        new JSONObject[] {
          answer(null).put("summary", ""),
          answer(null).put("summary", "x".repeat(4001)),
          answer(null).put("title", "x".repeat(81))
        }) {
      try {
        Vision.parse(invalid.toString(), now);
        throw new AssertionError("Invalid output accepted");
      } catch (java.io.IOException expected) {
        check(true, "Invalid output rejected");
      }
    }
    System.out.println("PASS: " + count + " image analysis checks (no paid requests)");
  }
}
