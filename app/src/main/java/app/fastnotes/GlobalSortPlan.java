package app.fastnotes;

import java.util.*;

/** Plans decks and folders using the on-device semantic index features. */
final class GlobalSortPlan {
  private static final int MAX_DECK_SIZE = 8;
  private static final double DECK_SIMILARITY = 0.52;

  static final class Placement {
    final List<Note> notes;
    final String folder;

    Placement(List<Note> notes, String folder) {
      this.notes = notes;
      this.folder = folder;
    }
  }

  private static final class Entry {
    final Note note;
    final float[] vector;
    final String theme;
    final Set<String> topics = new LinkedHashSet<>();
    final Set<String> words;

    Entry(Note note, Map<String, String> topicNames) {
      this.note = note;
      vector = SemanticEmbedding.embed(SemanticEmbedding.text(note));
      String label = SemanticEmbedding.primaryTopic(vector);
      theme = FolderPolicy.key(label);
      if (!theme.isEmpty()) topicNames.putIfAbsent(theme, label);
      for (String topic : FolderSuggestion.topics(note)) {
        String key = FolderPolicy.key(topic);
        topics.add(key);
        topicNames.putIfAbsent(key, FolderSuggestion.display(topic));
      }
      words = TopicSelection.words(note);
    }
  }

  private GlobalSortPlan() {}

  static List<Placement> build(List<Note> notes, Collection<String> folders,
      Set<String> excludedFolders, boolean sortFolders) {
    Map<String, String> names = new LinkedHashMap<>();
    Map<String, float[]> existing = new LinkedHashMap<>();
    if (sortFolders) {
      for (String folder : folders) {
        String key = FolderPolicy.key(folder);
        names.put(key, folder);
        existing.put(key, SemanticEmbedding.embed(folder));
      }
    }
    List<Entry> entries = new ArrayList<>();
    Map<String, List<Entry>> byFeature = new HashMap<>();
    Map<String, Integer> topicCounts = new HashMap<>();
    for (Note note : notes) {
      Entry entry = new Entry(note, names);
      entries.add(entry);
      Set<String> categories = new LinkedHashSet<>(entry.topics);
      if (!entry.theme.isEmpty()) categories.add(entry.theme);
      for (String category : categories) {
        topicCounts.merge(category, 1, Integer::sum);
        byFeature.computeIfAbsent("t:" + category, k -> new ArrayList<>()).add(entry);
      }
      for (String word : entry.words)
        byFeature.computeIfAbsent("w:" + word, k -> new ArrayList<>()).add(entry);
    }

    List<Placement> result = new ArrayList<>();
    Set<Entry> placed = new HashSet<>();
    for (Entry seed : entries) {
      if (!placed.add(seed)) continue;
      List<Entry> group = new ArrayList<>();
      group.add(seed);
      Set<Entry> nearby = new LinkedHashSet<>();
      for (String topic : seed.topics) nearby.addAll(byFeature.get("t:" + topic));
      if (!seed.theme.isEmpty()) nearby.addAll(byFeature.get("t:" + seed.theme));
      for (String word : seed.words) nearby.addAll(byFeature.get("w:" + word));
      Map<Entry, Double> scores = new HashMap<>();
      for (Entry entry : nearby) {
        if (placed.contains(entry) || (!sortFolders && entry.note.folder != seed.note.folder)) continue;
        double score = similarity(seed, entry);
        if (score >= DECK_SIMILARITY) scores.put(entry, score);
      }
      List<Entry> candidates = new ArrayList<>(scores.keySet());
      candidates.sort(Comparator.<Entry>comparingDouble(scores::get).reversed()
          .thenComparingLong(entry -> entry.note.id));
      for (Entry candidate : candidates) {
        if (group.size() == MAX_DECK_SIZE) break;
        // A note has to fit the deck as a whole. Shared words with only the first note can be incidental.
        if (group.stream().anyMatch(entry -> entry != seed && similarity(entry, candidate) < DECK_SIMILARITY))
          continue;
        group.add(candidate);
        placed.add(candidate);
      }
      List<Note> members = new ArrayList<>();
      for (Entry entry : group) members.add(entry.note);
      String folder = sortFolders ? chooseFolder(group, existing, topicCounts, excludedFolders) : "";
      result.add(new Placement(members, names.getOrDefault(folder, "")));
    }
    return result;
  }

  private static double similarity(Entry a, Entry b) {
    double score = SemanticEmbedding.similarity(a.vector, b.vector);
    if (!Collections.disjoint(a.topics, b.topics)) score += 0.30;
    if (!Collections.disjoint(a.words, b.words)) score += 0.10;
    return score;
  }

  private static String chooseFolder(List<Entry> group, Map<String, float[]> existing,
      Map<String, Integer> counts, Set<String> excludedFolders) {
    String best = "";
    double high = 0;
    Map<String, Double> votes = new LinkedHashMap<>();
    for (Entry entry : group) {
      Map<String, Double> matches = new LinkedHashMap<>();
      for (String key : entry.topics)
        if (existing.containsKey(key) || counts.get(key) >= 2) matches.put(key, 1.0);
      if (!entry.theme.isEmpty()
          && (existing.containsKey(entry.theme) || counts.get(entry.theme) >= 2))
        matches.putIfAbsent(entry.theme, 0.65);
      for (Map.Entry<String, float[]> folder : existing.entrySet()) {
        double score = SemanticEmbedding.similarity(entry.vector, folder.getValue());
        if (score >= 0.48) matches.merge(folder.getKey(), score, Math::max);
      }
      matches.forEach((key, weight) -> votes.merge(key, weight, Double::sum));
    }
    for (Map.Entry<String, Double> vote : votes.entrySet()) {
      if (excludedFolders.contains(vote.getKey())) continue;
      double weight = vote.getValue();
      if (weight < 0.45 * group.size()) continue;
      if (weight > high || (weight == high && existing.containsKey(vote.getKey()))) {
        high = weight;
        best = vote.getKey();
      }
    }
    return best;
  }
}
