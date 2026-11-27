package app.fastnotes;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AppUpdates {
  private static final String REPO = "Ux1ew1/Mimohodom";
  private static final String RELEASES = "https://api.github.com/repos/" + REPO + "/releases";
  private static final List<String> HOSTS = Arrays.asList("api.github.com", "github.com",
      "release-assets.githubusercontent.com", "objects.githubusercontent.com",
      "github-releases.githubusercontent.com");

  private AppUpdates() {}

  static AppUpdateManifest check() throws Exception {
    // Desktop and preview releases have their own tags and do not affect this channel.
    String tag = AppUpdateManifest.latestAndroidTag(read(RELEASES + "?per_page=20", 512 * 1024));
    return tag.isEmpty() ? null
        : AppUpdateManifest.parse(tag, read(asset(tag, AppUpdateManifest.ASSET), 4096));
  }

  private static String asset(String tag, String name) {
    return "https://github.com/" + REPO + "/releases/download/" + tag + "/" + name;
  }

  private static HttpURLConnection open(String address) throws IOException {
    URL url = new URL(address);
    for (int redirects = 0; redirects <= 5; redirects++) {
      if (!"https".equals(url.getProtocol()) || !HOSTS.contains(url.getHost())
          || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443))
        throw new IOException("Недопустимый адрес обновления");
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(12_000);
      connection.setReadTimeout(20_000);
      connection.setRequestProperty("User-Agent", "Mimohodom-Android");
      try {
        int status = connection.getResponseCode();
        if (status == 200) return connection;
        String redirect = connection.getHeaderField("Location");
        if (status < 300 || status >= 400 || redirect == null)
          throw new IOException("GitHub ответил кодом " + status);
        url = new URL(url, redirect);
      } catch (IOException | RuntimeException e) {
        connection.disconnect();
        throw e;
      }
      connection.disconnect();
    }
    throw new IOException("Слишком много перенаправлений");
  }

  private static String read(String url, int limit) throws IOException {
    HttpURLConnection connection = open(url);
    try (InputStream input = connection.getInputStream()) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      for (int count; (count = input.read(buffer)) != -1; ) {
        if (bytes.size() + count > limit) throw new IOException("Ответ GitHub слишком большой");
        bytes.write(buffer, 0, count);
      }
      return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    } finally {
      connection.disconnect();
    }
  }

  static File download(Context context, AppUpdateManifest update) throws Exception {
    File directory = new File(context.getCacheDir(), "updates");
    if (!directory.mkdirs() && !directory.isDirectory())
      throw new IOException("Не удалось подготовить папку для обновления");
    // Android expects the .apk extension when reading the archive. Only the final path is shared.
    File partial = File.createTempFile("update-", ".apk", context.getCacheDir());
    try {
      HttpURLConnection connection = open(asset(update.tag(), update.apkName()));
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long size = 0;
      try (InputStream input = connection.getInputStream();
          FileOutputStream output = new FileOutputStream(partial)) {
        byte[] buffer = new byte[32 * 1024];
        for (int count; (count = input.read(buffer)) != -1; ) {
          size += count;
          if (size > update.size()) throw new IOException("Размер APK не совпадает с выпуском");
          digest.update(buffer, 0, count);
          output.write(buffer, 0, count);
        }
      } finally {
        connection.disconnect();
      }
      if (size != update.size() || !hex(digest.digest()).equals(update.sha256()))
        throw new IOException("Контрольная сумма APK не совпадает с выпуском");
      verify(context, partial, update);
      File apk = new File(directory, update.apkName());
      Files.move(partial.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING);
      return apk;
    } finally {
      Files.deleteIfExists(partial.toPath());
    }
  }

  @SuppressWarnings("deprecation")
  static long versionCode(PackageInfo info) {
    return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
  }

  @SuppressWarnings("deprecation")
  static void verify(Context context, File apk, AppUpdateManifest update) throws Exception {
    PackageManager packages = context.getPackageManager();
    int flags = Build.VERSION.SDK_INT >= 28
        ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    PackageInfo installed = packages.getPackageInfo(context.getPackageName(), flags);
    PackageInfo archive = packages.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
    if (archive == null || !installed.packageName.equals(archive.packageName)
        || versionCode(archive) != update.versionCode() || versionCode(archive) <= versionCode(installed)
        || !update.versionName().equals(archive.versionName))
      throw new IOException("APK не подходит для этой версии Мимоходом");
    Set<Signature> signer = signatures(installed);
    if (signer.isEmpty() || !signer.equals(signatures(archive)))
      throw new IOException("Подпись APK отличается от подписи установленного приложения");
  }

  @SuppressWarnings("deprecation")
  private static Set<Signature> signatures(PackageInfo info) {
    Signature[] values = Build.VERSION.SDK_INT >= 28
        ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()
        : info.signatures;
    return values == null ? Collections.emptySet() : new HashSet<>(Arrays.asList(values));
  }

  private static String hex(byte[] bytes) {
    StringBuilder hex = new StringBuilder(bytes.length * 2);
    for (byte b : bytes)
      hex.append(Character.forDigit((b & 0xff) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
    return hex.toString();
  }
}
