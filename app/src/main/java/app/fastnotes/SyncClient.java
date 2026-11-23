package app.fastnotes;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Клиент обмена с компьютером по протоколу docs/SYNC.md. Без зависимости от Android. */
public final class SyncClient {
  public static final int DISCOVERY_PORT = 47615;
  private static final int BATCH = 200;

  /** Файлы картинок: что нужно скачать, что отдать, как записать. */
  public interface Files {
    List<String> missing();

    byte[] read(String name);

    void write(String name, byte[] bytes);
  }

  public static final class Report {
    public String peer = "", host = "";
    public int sent, received, conflicts, imagesDown, imagesUp;
    public SyncStore.Stats stats = new SyncStore.Stats();
  }

  /** Ошибка с текстом для человека. */
  public static final class SyncException extends Exception {
    public final boolean rejected;

    public SyncException(String message, boolean rejected) {
      super(message);
      this.rejected = rejected;
    }
  }

  private final SyncStore store;
  private final Files files;
  private final String platform;
  private int connectMs = 1500, readMs = 90_000;

  public SyncClient(SyncStore store, Files files, String platform) {
    this.store = store;
    this.files = files;
    this.platform = platform;
  }

  public SyncClient timeouts(int connectMs, int readMs) {
    this.connectMs = connectMs;
    this.readMs = readMs;
    return this;
  }

  private static final class Link {
    final String base, own;
    final byte[] key;
    final int connectMs, readMs;

    Link(String base, String own, byte[] key, int connectMs, int readMs) {
      this.base = base;
      this.own = own;
      this.key = key;
      this.connectMs = connectMs;
      this.readMs = readMs;
    }

    /** Возвращает расшифрованный ответ, пустой массив для 404 или бросает исключение. */
    byte[] post(String path, JSONObject body) throws IOException, SyncException {
      try {
        body.put("v", 1).put("ts", System.currentTimeMillis()).put("rid", SyncCrypto.randomHex(8));
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
      byte[] sealed =
          SyncCrypto.seal(
              key, SyncCrypto.requestAad(own), body.toString().getBytes(StandardCharsets.UTF_8));
      MiniHttp.Response r = MiniHttp.post(base, path, own, sealed, connectMs, readMs);
      if (r.status == 401) throw new SyncException("Устройство не принимает сопряжение.", true);
      if (r.status == 404) return new byte[0];
      if (r.status == 400)
        throw new SyncException(
            "Часы на устройствах расходятся больше чем на 10 минут или запрос повторён.", false);
      if (r.status != 200) throw new SyncException("Ответ сервера " + r.status, false);
      byte[] plain = SyncCrypto.open(key, SyncCrypto.responseAad(own), r.body);
      if (plain == null) throw new SyncException("Ответ не удалось расшифровать", false);
      return plain;
    }
  }

  /** Ищет компьютер в сети широковещательным UDP-запросом. */
  public static List<String> discover(String device, int waitMs) {
    List<String> out = new ArrayList<>();
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setBroadcast(true);
      socket.setSoTimeout(300);
      byte[] msg = ("MIMO?" + device).getBytes(StandardCharsets.US_ASCII);
      socket.send(
          new DatagramPacket(
              msg, msg.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));
      long end = System.currentTimeMillis() + waitMs;
      byte[] buffer = new byte[128];
      while (System.currentTimeMillis() < end) {
        try {
          DatagramPacket p = new DatagramPacket(buffer, buffer.length);
          socket.receive(p);
          String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
          if (!text.startsWith("MIMO!")) continue;
          String[] parts = text.substring(5).split(":");
          if (parts.length == 2 && parts[0].equals(device)) {
            String host = p.getAddress().getHostAddress() + ":" + parts[1].trim();
            if (!out.contains(host)) out.add(host);
          }
        } catch (java.net.SocketTimeoutException ignored) {
        }
      }
    } catch (IOException ignored) {
    }
    return out;
  }

  /** Обмен с узлом. Блокирующий: вызывать не из главного потока. */
  public Report sync(String device) throws SyncException {
    SyncStore.Peer peer = store.peer(device);
    if (peer == null) throw new SyncException("Устройство не найдено", false);
    byte[] key = SyncCrypto.deriveKey(peer.secret);
    String own = store.deviceId(), ownName = store.deviceName();
    Report report = new Report();
    report.peer = peer.name;

    SyncStore.Batch first = store.collect(peer.sentSeq, peer.device, BATCH, true);
    Link link = null;
    byte[] firstReply = null;
    String lastError = "Устройство не найдено в сети";
    List<String> candidates = new ArrayList<>(peer.hosts);
    Collections.reverse(candidates); // последний удавшийся адрес хранится в конце
    boolean discovered = false;
    while (link == null) {
      for (String host : candidates) {
        Link l = new Link(host, own, key, connectMs, readMs);
        try {
          firstReply = l.post("/v1/sync", request(own, ownName, peer.gotSeq, true, first.records));
          link = l;
          report.host = host;
          break;
        } catch (SyncException e) {
          if (e.rejected)
            throw new SyncException(
                "«" + peer.name + "» не принимает это устройство: сопряжение удалено или код устарел."
                    + " Сопрягите заново.",
                true);
          lastError = host + ": " + e.getMessage();
        } catch (IOException e) {
          lastError = host + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
      }
      if (link != null || discovered) break;
      discovered = true;
      candidates = discover(peer.device, 1500);
    }
    if (link == null) throw new SyncException("Не удалось подключиться: " + lastError, false);
    {
      List<String> hosts = new ArrayList<>(peer.hosts);
      hosts.remove(report.host);
      hosts.add(report.host);
      store.setHosts(peer.device, hosts);
    }

    List<String> want = new ArrayList<>();
    long since = peer.gotSeq, sent = peer.sentSeq;
    SyncStore.Batch out = first;
    boolean firstPage = true;
    byte[] reply = firstReply;
    try {
      while (true) {
        if (reply == null)
          reply = link.post("/v1/sync", request(own, ownName, since, firstPage, out.records));
        JSONObject v = new JSONObject(new String(reply, StandardCharsets.UTF_8));
        reply = null;
        for (SyncRecord sentRecord : out.records) if (sentRecord.isNote()) report.sent++;
        sent = Math.max(sent, out.next);
        JSONArray array = v.optJSONArray("records");
        List<SyncRecord> incoming = new ArrayList<>();
        if (array != null)
          for (int i = 0; i < array.length(); i++)
            incoming.add(SyncRecord.fromJson(array.getJSONObject(i)));
        for (SyncRecord r : incoming) if (r.isNote() && !r.deleted) report.received++;
        report.stats.add(store.apply(peer.device, sent, incoming));
        store.progress(peer.device, sent, -1, -1);
        since = v.optLong("next", since);
        boolean moreIn = v.optBoolean("more", false);
        JSONArray w = v.optJSONArray("want");
        if (w != null)
          for (int i = 0; i < w.length(); i++) {
            String n = w.optString(i, "");
            if (!n.isEmpty() && !want.contains(n)) want.add(n);
          }
        boolean moreOut = out.more;
        firstPage = false;
        if (!moreIn && !moreOut) break;
        if (moreOut) out = store.collect(sent, peer.device, BATCH, false);
        else {
          out = new SyncStore.Batch();
          out.next = sent;
        }
      }
    } catch (IOException e) {
      throw new SyncException("Связь прервалась: " + e.getMessage(), false);
    } catch (JSONException e) {
      throw new SyncException("Непонятный ответ компьютера", false);
    }
    store.progress(peer.device, sent, since, System.currentTimeMillis());
    report.conflicts = report.stats.conflicts;

    // Картинки: скачиваем недостающие, отдаём запрошенные. Сбой одной не прерывает обмен.
    for (String name : files.missing()) {
      if (!SyncRecord.safeFileName(name)) continue;
      try {
        byte[] bytes = link.post("/v1/blob/get", new JSONObject().put("name", name));
        if (bytes.length > 0) {
          files.write(name, bytes);
          report.imagesDown++;
        }
      } catch (IOException | SyncException | JSONException ignored) {
      }
    }
    for (String name : want) {
      if (!SyncRecord.safeFileName(name)) continue;
      byte[] bytes = files.read(name);
      if (bytes == null || bytes.length == 0) continue;
      try {
        link.post(
            "/v1/blob/put",
            new JSONObject().put("name", name).put("data", Base64.getEncoder().encodeToString(bytes)));
        report.imagesUp++;
      } catch (IOException | SyncException | JSONException ignored) {
      }
    }
    return report;
  }

  private JSONObject request(
      String own, String ownName, long since, boolean first, List<SyncRecord> records) {
    try {
      JSONArray array = new JSONArray();
      for (SyncRecord r : records) array.put(r.toJson());
      return new JSONObject()
          .put("device", own)
          .put("name", ownName)
          .put("platform", platform)
          .put("hosts", new JSONArray())
          .put("since", since)
          .put("limit", 300)
          .put("first", first)
          .put("features", new JSONArray().put("chats"))
          .put("records", array);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }
}
