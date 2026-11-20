package app.fastnotes;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;

/** Проверки чистых классов синхронизации: шифрование, код сопряжения, записи, чтение HTTP. */
public final class SyncTest {
  private static int count;

  private static void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
    count++;
  }

  public static void main(String[] args) throws Exception {
    // Ключ из секрета 00..0f обязан совпасть с Rust (desktop/core/src/sync/crypto.rs, key_vector).
    byte[] secret = new byte[16];
    for (int i = 0; i < 16; i++) secret[i] = (byte) i;
    byte[] key = SyncCrypto.deriveKey(secret);
    check(
        SyncCrypto.hex(key)
            .equals("b7815271cae38b78371dc3ddc532319727add8cbf4b89dde9691e10b7ec929e9"),
        "HKDF совпадает с Rust");
    byte[] aad = SyncCrypto.requestAad("dev");
    byte[] sealed = SyncCrypto.seal(key, aad, "привет".getBytes(StandardCharsets.UTF_8));
    check(
        new String(SyncCrypto.open(key, aad, sealed), StandardCharsets.UTF_8).equals("привет"),
        "round trip");
    check(SyncCrypto.open(key, SyncCrypto.requestAad("other"), sealed) == null, "AAD привязан к устройству");
    byte[] bad = sealed.clone();
    bad[bad.length - 1] ^= 1;
    check(SyncCrypto.open(key, aad, bad) == null, "порча распознаётся");
    check(SyncCrypto.open(SyncCrypto.deriveKey(new byte[16]), aad, sealed) == null, "чужой ключ");
    check(SyncCrypto.open(key, aad, new byte[5]) == null, "короткие данные");

    // Код сопряжения в том виде, как его строит Rust (percent-encode, base64url без =).
    String uri =
        "mimohodom://pair?v=1&id=0123456789abcdef0123456789abcdef&n=%D0%9C%D0%BE%D0%B9%20%D0%9F%D0%9A%20%28Windows%29&s=AAECAwQFBgcICQoLDA0ODw&h=192.168.1.5:47616,10.0.0.2:47616";
    SyncPairing.Code code = SyncPairing.parse("  " + uri + "\n");
    check(code.device.equals("0123456789abcdef0123456789abcdef"), "id");
    check(code.name.equals("Мой ПК (Windows)"), "имя: " + code.name);
    check(Arrays.equals(code.secret, secret), "секрет");
    check(code.hosts.size() == 2 && code.hosts.get(1).equals("10.0.0.2:47616"), "адреса");
    for (String broken :
        new String[] {
          "http://x", "mimohodom://pair?v=2&id=1&s=AAAA", "mimohodom://pair?v=1&id=1&s=!!",
          "mimohodom://pair?v=1&id=0123456789abcdef0123456789abcdef&s=AAAA"
        }) {
      boolean failed = false;
      try {
        SyncPairing.parse(broken);
      } catch (IllegalArgumentException e) {
        failed = true;
      }
      check(failed, "отклонён: " + broken);
    }

    // Запись: туда и обратно, надгробие содержит только четыре поля.
    SyncRecord note = new SyncRecord();
    note.uid = "abc";
    note.ts = 77;
    note.title = "Т";
    note.body = "Тело";
    note.created = 1;
    note.updated = 2;
    note.reminder = 3;
    note.repeat = 2;
    note.lead = 15;
    note.images.add("a.jpg");
    note.folder = "ff";
    note.titleAuto = true;
    note.aiChecked = true;
    note.metadata = "{\"x\":1}";
    SyncRecord back = SyncRecord.fromJson(new JSONObject(note.toJson().toString()));
    check(
        back.uid.equals("abc")
            && back.ts == 77
            && back.title.equals("Т")
            && back.lead == 15
            && back.images.equals(Arrays.asList("a.jpg"))
            && back.titleAuto
            && back.aiChecked
            && back.metadata.equals("{\"x\":1}")
            && back.folder.equals("ff"),
        "запись заметки");
    check(back.reminderTimes == null && !note.toJson().has("reminderTimes"), "без списка дат поля нет");
    note.reminderTimes = "[100,200]";
    check(
        "[100,200]".equals(SyncRecord.fromJson(new JSONObject(note.toJson().toString())).reminderTimes),
        "список дат в записи заметки");
    note.reminderTimes = null;
    JSONObject tomb = SyncRecord.tomb("note", "abc", 5).toJson();
    check(tomb.length() == 4 && tomb.getBoolean("deleted"), "надгробие: " + tomb);
    SyncRecord folder = new SyncRecord();
    folder.kind = "folder";
    folder.uid = "f";
    folder.name = "Дом";
    folder.key = "дом";
    check(SyncRecord.fromJson(folder.toJson()).name.equals("Дом"), "запись папки");
    check(SyncRecord.safeFileName("3f2a-1.jpg") && !SyncRecord.safeFileName("../x.jpg"), "имя файла");
    check(!SyncRecord.safeFileName("a/b.jpg") && !SyncRecord.safeFileName(""), "имя файла 2");

    // Чаты, сообщения и стопки.
    SyncRecord chat = new SyncRecord();
    chat.kind = "chat";
    chat.uid = "c1";
    chat.title = "Про борщ";
    chat.created = 5;
    SyncRecord chatBack = SyncRecord.fromJson(chat.toJson());
    check(chatBack.isChat() && chatBack.title.equals("Про борщ") && chatBack.created == 5, "запись чата");
    SyncRecord message = new SyncRecord();
    message.kind = "message";
    message.uid = "m1";
    message.chat = "c1";
    message.role = "assistant";
    message.body = "см. [[note:abc]]";
    message.draft = "{\"title\":\"x\"}";
    message.sources.put(new JSONObject().put("uid", "abc").put("title", "Борщ"));
    SyncRecord messageBack = SyncRecord.fromJson(message.toJson());
    check(
        messageBack.isMessage()
            && messageBack.chat.equals("c1")
            && messageBack.role.equals("assistant")
            && messageBack.draft.equals("{\"title\":\"x\"}")
            && messageBack.sources.getJSONObject(0).getString("uid").equals("abc"),
        "запись сообщения");
    SyncRecord stack = new SyncRecord();
    stack.kind = "stack";
    stack.uid = "top";
    stack.members.add("a");
    stack.members.add("b");
    check(SyncRecord.fromJson(stack.toJson()).members.equals(Arrays.asList("a", "b")), "запись стопки");
    SyncRecord stackGone = SyncRecord.tomb("stack", "top", 9);
    check(SyncRecord.fromJson(stackGone.toJson()).deleted && SyncRecord.fromJson(stackGone.toJson()).isStack(), "стопка удалена");
    check(!SyncRecord.fromJson(chat.toJson()).isNote() && SyncRecord.fromJson(folder.toJson()).isFolder(), "виды записей различаются");
    check(
        SyncRecord.mapLinks("см. [[note:12]] и [[note:5]], а [[note: и [[note:x", x -> "<" + x + ">")
            .equals("см. [[note:<12>]] и [[note:<5>]], а [[note: и [[note:x"),
        "ссылки на заметки заменяются как в Rust");
    check(SyncRecord.mapLinks("без ссылок", x -> "!").equals("без ссылок"), "текст без ссылок");

    // Чтение HTTP-ответа: с Content-Length и по частям (chunked).
    byte[] ok =
        "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nX: y\r\n\r\nhello".getBytes(StandardCharsets.ISO_8859_1);
    MiniHttp.Response r = MiniHttp.read(new java.io.ByteArrayInputStream(ok));
    check(r.status == 200 && new String(r.body).equals("hello"), "Content-Length");
    byte[] chunked =
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n"
            .getBytes(StandardCharsets.ISO_8859_1);
    r = MiniHttp.read(new java.io.ByteArrayInputStream(chunked));
    check(new String(r.body).equals("abcde"), "chunked");
    r = MiniHttp.read(new java.io.ByteArrayInputStream("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n".getBytes()));
    check(r.status == 401 && r.body.length == 0, "401");
    boolean cut = false;
    try {
      MiniHttp.read(new java.io.ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\nabc".getBytes()));
    } catch (java.io.IOException e) {
      cut = true;
    }
    check(cut, "обрыв распознаётся");

    System.out.println("SyncTest OK (" + count + " проверок)");
  }
}
