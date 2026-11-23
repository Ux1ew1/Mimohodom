package app.fastnotes;

import java.util.*;

/** Локальный поиск точных и близких дублей без удаления и без облачного запроса. */
public final class DuplicateFinder {
  private static final int MAX_INPUT = 300;

  private DuplicateFinder() {}

  public static final class Report {
    public final List<List<Note>> groups = new ArrayList<>();
    public boolean truncated;

    public int notes() {
      int out = 0;
      for (List<Note> group : groups) out += group.size();
      return out;
    }
  }

  public static Report find(List<Note> input, long now, int days) {
    List<Note> notes = new ArrayList<>();
    long from = now - Math.max(1, days) * 86_400_000L;
    for (Note note : input)
      if (note.trashed == 0 && note.created >= from && note.created <= now) notes.add(note);
    notes.sort((a, b) -> Long.compare(b.created, a.created));
    Report out = new Report();
    out.truncated = notes.size() > MAX_INPUT;
    if (out.truncated) notes = new ArrayList<>(notes.subList(0, MAX_INPUT));
    UnionFind union = new UnionFind(notes.size());
    Map<String, Integer> exact = new HashMap<>();
    String[] normalized = new String[notes.size()];
    List<Set<String>> words = new ArrayList<>();
    float[][] vectors = new float[notes.size()][];
    for (int i = 0; i < notes.size(); i++) {
      Note note = notes.get(i);
      normalized[i] = normalize(note.title + " " + note.body);
      words.add(tokens(normalized[i]));
      vectors[i] = SemanticEmbedding.embed(SemanticEmbedding.text(note));
      if (normalized[i].isEmpty()) continue;
      Integer same = exact.putIfAbsent(normalized[i], i);
      if (same != null) union.join(same, i);
    }
    for (int i = 0; i < notes.size(); i++)
      for (int j = i + 1; j < notes.size(); j++) {
        if (normalized[i].isEmpty() || normalized[j].isEmpty()) continue;
        double overlap = jaccard(words.get(i), words.get(j));
        if (overlap >= 0.84 || (overlap >= 0.62
            && SemanticEmbedding.similarity(vectors[i], vectors[j]) >= 0.94)) union.join(i, j);
      }
    Map<Integer, List<Note>> grouped = new LinkedHashMap<>();
    for (int i = 0; i < notes.size(); i++) {
      int root = union.root(i);
      grouped.computeIfAbsent(root, ignored -> new ArrayList<>()).add(notes.get(i));
    }
    for (List<Note> group : grouped.values())
      if (group.size() > 1) {
        group.sort((a, b) -> Long.compare(a.id, b.id));
        out.groups.add(group);
      }
    out.groups.sort((a, b) -> Long.compare(a.get(0).id, b.get(0).id));
    return out;
  }

  private static String normalize(String value) {
    return Retrieval.normalize(value).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
  }

  private static Set<String> tokens(String value) {
    Set<String> out = new HashSet<>();
    for (String word : value.split(" "))
      if (word.length() > 2) out.add(word.length() > 6 ? word.substring(0, word.length() - 2) : word);
    return out;
  }

  private static double jaccard(Set<String> a, Set<String> b) {
    if (a.isEmpty() || b.isEmpty()) return 0;
    int common = 0;
    for (String word : a) if (b.contains(word)) common++;
    return (double) common / (a.size() + b.size() - common);
  }

  private static final class UnionFind {
    final int[] parent;

    UnionFind(int size) {
      parent = new int[size];
      for (int i = 0; i < size; i++) parent[i] = i;
    }

    int root(int value) {
      if (parent[value] != value) parent[value] = root(parent[value]);
      return parent[value];
    }

    void join(int a, int b) {
      int ar = root(a), br = root(b);
      if (ar != br) parent[br] = ar;
    }
  }
}
