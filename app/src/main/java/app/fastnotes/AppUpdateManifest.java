package app.fastnotes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

record AppUpdateManifest(int versionCode, String versionName, long size, String sha256) {
  static final String ASSET = "mimohodom-update.json";
  static final String TAG_PREFIX = "android-v";
  private static final String VERSION = "[0-9]+\\.[0-9]+\\.[0-9]+";
  private static final long MAX_APK_SIZE = 256 * 1024 * 1024L;

  static boolean androidTag(String tag) {
    return tag.matches(TAG_PREFIX + VERSION);
  }

  static String latestAndroidTag(String listing) throws JSONException {
    JSONArray releases = new JSONArray(listing);
    for (int i = 0; i < releases.length(); i++) {
      JSONObject release = releases.getJSONObject(i);
      String tag = release.optString("tag_name");
      if (androidTag(tag) && !release.optBoolean("draft") && !release.optBoolean("prerelease"))
        return tag;
    }
    return "";
  }

  static AppUpdateManifest parse(String tag, String data) throws JSONException {
    JSONObject json = new JSONObject(data);
    long code = positiveInteger(json, "versionCode");
    String version = json.getString("versionName");
    long size = positiveInteger(json, "size");
    String hash = json.getString("sha256");
    if (!androidTag(tag) || !tag.equals(TAG_PREFIX + version)
        || !"app.fastnotes".equals(json.getString("packageName"))
        || code > Integer.MAX_VALUE || size > MAX_APK_SIZE || !hash.matches("[0-9a-f]{64}"))
      throw new JSONException("Invalid Android update manifest");
    return new AppUpdateManifest((int) code, version, size, hash);
  }

  private static long positiveInteger(JSONObject json, String key) throws JSONException {
    Object value = json.get(key);
    if ((value instanceof Integer || value instanceof Long) && ((Number) value).longValue() > 0)
      return ((Number) value).longValue();
    throw new JSONException("Invalid " + key);
  }

  String apkName() {
    return "mimohodom-" + versionName + "-debug.apk";
  }

  String tag() {
    return TAG_PREFIX + versionName;
  }
}
