package app.fastnotes;

import java.util.ArrayList;
import java.util.List;

/** Что клиенту обмена нужно от хранилища. На Android — SQLite, в тестах — память. */
public interface SyncStore {
  final class Peer {
    public String device = "", name = "", platform = "";
    public byte[] secret = new byte[0];
    public List<String> hosts = new ArrayList<>();
    public long sentSeq, gotSeq, lastSync;
  }

  final class Batch {
    public List<SyncRecord> records = new ArrayList<>();
    public boolean more;
    public long next;
  }

  final class Stats {
    public int applied, ignored, conflicts;
    /** Локальные номера заметок, удалённых по чужому надгробию (для отмены напоминаний). */
    public List<Long> removed = new ArrayList<>();

    public void add(Stats o) {
      applied += o.applied;
      ignored += o.ignored;
      conflicts += o.conflicts;
      removed.addAll(o.removed);
    }
  }

  String deviceId();

  String deviceName();

  Peer peer(String device);

  List<Peer> peers();

  void savePeer(Peer peer);

  void deletePeer(String device);

  void setHosts(String device, List<String> hosts);

  void progress(String device, long sent, long got, long when);

  /** Записи для соседа: {@code since < seq <= snapshot}, кроме принятых от него самого. */
  Batch collect(long since, String exclude, int limit, boolean allFolders);

  /** Принимает записи соседа. {@code sentSeq} — до какого локального seq ему уже отправлено. */
  Stats apply(String peer, long sentSeq, List<SyncRecord> records);
}
