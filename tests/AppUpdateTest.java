package app.fastnotes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class AppUpdateTest {
  private static final String TAG = "android-v0.11.8";

  public static void main(String[] args) throws Exception {
    JSONObject json = new JSONObject().put("packageName", "app.fastnotes").put("versionName", "0.11.8")
        .put("versionCode", 83).put("size", 14_000_000).put("sha256", "a".repeat(64));
    AppUpdateManifest update = AppUpdateManifest.parse(TAG, json.toString());
    check(update.versionCode() == 83 && update.size() == 14_000_000, "Numeric metadata");
    check(update.apkName().equals("mimohodom-0.11.8-debug.apk") && update.tag().equals(TAG),
        "APK name and tag use the release version");
    reject(json, "packageName", "app.fastnotes.separate");
    reject(json, "versionName", "0.11.9");
    reject(json, "versionName", "../../update");
    reject(json, "versionCode", Integer.MAX_VALUE + 1L);
    reject(json, "versionCode", 83.5);
    reject(json, "versionCode", 0);
    reject(json, "size", 1_000_000_000);
    reject(json, "size", -1);
    reject(json, "sha256", "not-a-checksum");

    JSONArray releases = new JSONArray()
        .put(new JSONObject().put("tag_name", "desktop-v0.11.8"))
        .put(new JSONObject().put("tag_name", "android-v0.11.10").put("prerelease", true))
        .put(new JSONObject().put("tag_name", "android-v0.11.9").put("draft", true));
    check(AppUpdateManifest.latestAndroidTag(releases.toString()).isEmpty(), "No stable Android release");
    releases.put(new JSONObject().put("tag_name", TAG));
    check(AppUpdateManifest.latestAndroidTag(releases.toString()).equals(TAG), "Select stable Android release");
    System.out.println("PASS: 13 Android update manifest checks");
  }

  private static void reject(JSONObject original, String field, Object value) throws Exception {
    JSONObject changed = new JSONObject(original.toString()).put(field, value);
    try {
      AppUpdateManifest.parse(TAG, changed.toString());
    } catch (JSONException expected) {
      return;
    }
    throw new AssertionError("Accepted invalid " + field);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
