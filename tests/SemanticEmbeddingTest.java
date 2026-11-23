package app.fastnotes;

import java.util.*;

public final class SemanticEmbeddingTest {
  private static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static Note note(long id, String title, String body) {
    return new Note(id, title, body, 1000, id * 1000, 0);
  }

  public static void main(String[] args) {
    float[] vacation = SemanticEmbedding.embed("что я записывал про отпуск");
    float[] sea = SemanticEmbedding.embed("Поездка\nК морю в июле");
    float[] repair = SemanticEmbedding.embed("Ремонт\nПлитка и сантехника");
    check(SemanticEmbedding.similarity(vacation, sea) > SemanticEmbedding.MIN_SCORE, "vacation finds a sea trip by meaning");
    check(SemanticEmbedding.similarity(vacation, sea)
        > SemanticEmbedding.similarity(vacation, repair), "unrelated topic ranks lower");
    float[] same = SemanticEmbedding.embed("Поездка к морю");
    check(Arrays.equals(same, SemanticEmbedding.embed("Поездка к морю")), "embedding deterministic");
    double norm = 0;
    for (float value : same) norm += value * value;
    check(Math.abs(norm - 1) < 0.0001, "embedding normalized");
    check(SemanticEmbedding.similarity(same, SemanticEmbedding.decode(SemanticEmbedding.encode(same))) > 0.999,
        "SQLite BLOB round trip");
    check(SemanticEmbedding.decode(new byte[3]) == null, "corrupt vector rejected");

    Note trip = note(1, "Поездка", "К морю в июле");
    Note unrelated = note(2, "Ремонт", "Плитка и сантехника");
    trip.semanticScore = SemanticEmbedding.similarity(vacation, sea);
    unrelated.semanticScore = SemanticEmbedding.similarity(vacation, repair);
    check(Retrieval.select(List.of(unrelated, trip), "что я записывал про отпуск", 0).get(0).id == 1,
        "hybrid retrieval keeps semantic-only match");
    Note exact = note(3, "Отпуск", "Даты");
    exact.semanticScore = trip.semanticScore;
    check(Retrieval.select(List.of(trip, exact), "отпуск", 0).get(0).id == 3,
        "exact title still wins hybrid ranking");
    String fingerprint = SemanticEmbedding.fingerprint(trip);
    check(fingerprint.equals(SemanticEmbedding.fingerprint(trip)), "unchanged note keeps fingerprint");
    trip.body += " Отель";
    check(!fingerprint.equals(SemanticEmbedding.fingerprint(trip)), "body edit invalidates vector");
    trip.body = "К морю в июле";
    trip.searchText = "отпуск";
    check(!fingerprint.equals(SemanticEmbedding.fingerprint(trip)), "metadata edit invalidates vector");

    Note original = note(10, "Билеты", "Купить билеты к морю на 12 июля");
    Note copy = note(11, "Билеты", "Купить билеты к морю на 12 июля");
    Note other = note(12, "Ремонт", "Вызвать сантехника");
    long now = 31L * 86_400_000L;
    original.created = copy.created = other.created = now - 1000;
    DuplicateFinder.Report report = DuplicateFinder.find(List.of(original, copy, other), now, 30);
    check(report.groups.size() == 1 && report.groups.get(0).size() == 2, "exact duplicate group found locally");
    check(report.notes() == 2, "unrelated note not reported as duplicate");
    original.created = 0;
    check(DuplicateFinder.find(List.of(original, copy), now, 30).groups.isEmpty(),
        "last-month window uses note creation date");
    System.out.println("PASS: " + count + " semantic checks");
  }
}
