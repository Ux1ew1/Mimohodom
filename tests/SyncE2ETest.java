package app.fastnotes;

import java.util.*;

/**
 * Сквозная проверка: настоящий Java-клиент (SyncClient) против настоящего сервера на Rust
 * (desktop/core/examples/headless.rs). Запускает tests/sync-e2e.cjs. Хранилище здесь — память:
 * проверяются протокол, шифрование и цикл обмена; SQLite-хранилище Android проверяется отдельно.
 */
public final class SyncE2ETest {
  /** Память вместо SQLite: тот же контракт SyncStore, упрощённое слияние «больший ts побеждает». */
  static final class Memory implements SyncStore {
    static final class Entry {
      SyncRecord rec;
      long seq;
      String from = "";
    }

    final String device;
    long hlc = 1, seq;
    final Map<String, Entry> entries = new LinkedHashMap<>();
    final Map<String, Peer> peers = new LinkedHashMap<>();

    Memory(String device) {
      this.device = device;
    }

    @Override public String deviceId() { return device; }
    @Override public String deviceName() { return "Тестовый телефон"; }
    @Override public Peer peer(String d) { return peers.get(d); }
    @Override public List<Peer> peers() { return new ArrayList<>(peers.values()); }
    @Override public void savePeer(Peer p) { peers.put(p.device, p); }
    @Override public void deletePeer(String d) { peers.remove(d); }
    @Override public void setHosts(String d, List<String> hosts) { peers.get(d).hosts = new ArrayList<>(hosts); }

    @Override
    public void progress(String d, long sent, long got, long when) {
      Peer p = peers.get(d);
      if (sent >= 0) p.sentSeq = sent;
      if (got >= 0) p.gotSeq = got;
      if (when >= 0) p.lastSync = when;
    }

    private long stamp() {
      hlc = Math.max(hlc + 1, System.currentTimeMillis());
      return hlc;
    }

    void put(SyncRecord r) {
      Entry e = new Entry();
      r.ts = stamp();
      e.rec = r;
      e.seq = ++seq;
      entries.put(r.kind + ":" + r.uid, e);
    }

    String localNote(String title, String body, String... images) {
      SyncRecord r = new SyncRecord();
      r.uid = SyncCrypto.randomHex(16);
      r.title = title;
      r.body = body;
      r.created = r.updated = System.currentTimeMillis();
      r.images.addAll(Arrays.asList(images));
      put(r);
      return r.uid;
    }

    void localEdit(String title, String body) {
      for (Entry e : new ArrayList<>(entries.values()))
        if (!e.rec.deleted && !e.rec.isFolder() && e.rec.title.equals(title)) {
          SyncRecord copy = SyncRecord.fromJson(e.rec.toJson());
          copy.body = body;
          copy.updated = System.currentTimeMillis();
          put(copy);
        }
    }

    void localDelete(String title) {
      for (Entry e : new ArrayList<>(entries.values()))
        if (!e.rec.deleted && !e.rec.isFolder() && e.rec.title.equals(title))
          put(SyncRecord.tomb("note", e.rec.uid, 0));
    }

    @Override
    public Batch collect(long since, String exclude, int limit, boolean allFolders) {
      long snapshot = seq;
      List<Entry> notes = new ArrayList<>(), folders = new ArrayList<>();
      for (Entry e : entries.values()) {
        boolean inRange = e.seq > since && e.seq <= snapshot && !e.from.equals(exclude);
        if (e.rec.isFolder()) {
          if (allFolders || inRange) folders.add(e);
        } else if (inRange) notes.add(e);
      }
      notes.sort(Comparator.comparingLong(x -> x.seq));
      Batch b = new Batch();
      b.more = notes.size() > limit;
      if (b.more) notes = notes.subList(0, limit);
      b.next = b.more ? notes.get(notes.size() - 1).seq : snapshot;
      for (Entry e : folders) b.records.add(e.rec);
      for (Entry e : notes) b.records.add(e.rec);
      return b;
    }

    @Override
    public Stats apply(String peerDevice, long sentSeq, List<SyncRecord> records) {
      Stats s = new Stats();
      for (SyncRecord r : records) {
        Entry old = entries.get(r.kind + ":" + r.uid);
        if (old != null && r.ts <= old.rec.ts) {
          s.ignored++;
          continue;
        }
        hlc = Math.max(hlc, r.ts);
        Entry e = new Entry();
        e.rec = r;
        e.seq = ++seq;
        e.from = peerDevice;
        entries.put(r.kind + ":" + r.uid, e);
        s.applied++;
      }
      return s;
    }

    List<SyncRecord> live(boolean folders) {
      List<SyncRecord> out = new ArrayList<>();
      for (Entry e : entries.values())
        if (!e.rec.deleted && e.rec.isFolder() == folders) out.add(e.rec);
      return out;
    }
  }

  static final class MemFiles implements SyncClient.Files {
    final Map<String, byte[]> files = new TreeMap<>();
    final Set<String> referenced = new LinkedHashSet<>();

    @Override public List<String> missing() {
      List<String> out = new ArrayList<>();
      for (String n : referenced) if (!files.containsKey(n)) out.add(n);
      return out;
    }
    @Override public byte[] read(String name) { return files.get(name); }
    @Override public void write(String name, byte[] bytes) { files.put(name, bytes); }
  }

  private static void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
    System.out.println("  ok: " + name);
  }

  private static void refresh(Memory phone, MemFiles files) {
    files.referenced.clear();
    for (SyncRecord r : phone.live(false)) files.referenced.addAll(r.images);
  }

  public static void main(String[] args) throws Exception {
    SyncPairing.Code code = SyncPairing.parse(args[0]);
    Memory phone = new Memory(SyncCrypto.randomHex(16));
    MemFiles files = new MemFiles();
    SyncStore.Peer peer = new SyncStore.Peer();
    peer.device = code.device;
    peer.name = code.name;
    peer.secret = code.secret;
    peer.hosts = code.hosts;
    phone.savePeer(peer);
    SyncClient client = new SyncClient(phone, files, "android").timeouts(2000, 20000);

    // Первый обмен: телефон получает заметки, папку и картинку компьютера.
    SyncClient.Report r1 = client.sync(code.device);
    refresh(phone, files);
    for (int i = 0; i < 1; i++) {
      // картинки скачиваются после слияния: второй проход, как в приложении (missing() вычисляется заново)
      client = new SyncClient(phone, files, "android").timeouts(2000, 20000);
    }
    check(r1.received == 2, "получено заметок: " + r1.received);
    check(phone.live(true).size() == 1 && phone.live(true).get(0).name.equals("Работа"), "папка «Работа»");
    check(phone.live(false).size() == 2, "две заметки на телефоне");
    SyncRecord report = null;
    for (SyncRecord n : phone.live(false)) if (n.title.equals("Отчёт")) report = n;
    check(report != null && report.reminder == 1_900_000_000_000L && report.repeat == 2 && report.lead == 15, "поля напоминания");
    check(report != null && !report.folder.isEmpty(), "заметка в папке");
    SyncClient.Report r1b = client.sync(code.device);
    check(files.files.containsKey("img-one.jpg") && files.files.get("img-one.jpg").length == 9, "картинка скачана: " + r1b.imagesDown);
    check((files.files.get("img-one.jpg")[0] & 0xff) == 0xFF && (files.files.get("img-one.jpg")[1] & 0xff) == 0xD8, "байты картинки совпали");
    System.out.println("PHASE1");
    System.out.flush();
    new Scanner(System.in).nextLine(); // ждём, пока компьютер добавит заметку

    // Локальные изменения телефона: новая заметка с картинкой, правка, удаление.
    files.files.put("phone-img.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, 9, 9, (byte) 0xFF, (byte) 0xD9});
    phone.localNote("С телефона", "Записал на ходу", "phone-img.jpg");
    phone.localEdit("Покупки", "не нужен");
    phone.localEdit("Купить молоко и хлеб", "Купить молоко, хлеб и сыр");
    phone.localDelete("Отчёт");
    SyncClient.Report r2 = client.sync(code.device);
    refresh(phone, files);
    check(r2.received == 1, "получена добавленная на компьютере заметка: " + r2.received);
    check(r2.sent == 3 || r2.sent >= 3, "отправлено изменений: " + r2.sent);
    boolean hasPcNote = false;
    for (SyncRecord n : phone.live(false)) if (n.title.equals("Новая с ПК")) hasPcNote = true;
    check(hasPcNote, "заметка с компьютера пришла");
    System.out.println("PHASE2 sent=" + r2.sent + " received=" + r2.received);
    // Повторный обмен без изменений ничего не передаёт.
    SyncClient.Report r3 = client.sync(code.device);
    check(r3.sent == 0 && r3.received == 0, "повтор без изменений: sent=" + r3.sent + " received=" + r3.received);
    // Сопряжение с неверным секретом отвергается.
    Memory stranger = new Memory(SyncCrypto.randomHex(16));
    SyncStore.Peer bad = new SyncStore.Peer();
    bad.device = code.device;
    bad.name = "x";
    bad.secret = new byte[16];
    bad.hosts = code.hosts;
    stranger.savePeer(bad);
    boolean rejected = false;
    try {
      new SyncClient(stranger, new MemFiles(), "android").timeouts(2000, 5000).sync(code.device);
    } catch (SyncClient.SyncException e) {
      rejected = true;
    }
    check(rejected, "чужой секрет отвергнут");
    System.out.println("E2E OK");
  }
}
