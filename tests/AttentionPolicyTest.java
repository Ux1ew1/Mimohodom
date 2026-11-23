package app.fastnotes;

/** Баллы внимания помощника: назначение, бонусы класса, потолки, гейты, выход из нуля. */
public final class AttentionPolicyTest {
  static int count;

  static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    count++;
  }

  static void eq(int actual, int expected, String name) {
    check(actual == expected, name + ": " + actual + " != " + expected);
  }

  public static void main(String[] args) {
    // Старт: база 5, с напоминанием 6.
    eq(AttentionPolicy.initial(false), 5, "initial plain");
    eq(AttentionPolicy.initial(true), 6, "initial with reminder");

    // Потолки.
    eq(AttentionPolicy.clamp(-3), 0, "clamp low");
    eq(AttentionPolicy.clamp(12), 8, "clamp high");
    eq(AttentionPolicy.clamp(5), 5, "clamp inside");

    // Бонус первой классификации.
    eq(AttentionPolicy.classBonus(-1, "event", false), -1, "bonus skips unset");
    eq(AttentionPolicy.classBonus(6, "event", false), 7, "event +1");
    eq(AttentionPolicy.classBonus(6, "complex", false), 7, "complex +1");
    eq(AttentionPolicy.classBonus(6, "simple", true), 7, "plan +1");
    eq(AttentionPolicy.classBonus(6, "event", true), 8, "event and plan +2");
    eq(AttentionPolicy.classBonus(6, "info", false), 6, "info +0");
    eq(AttentionPolicy.classBonus(8, "event", true), 8, "bonus clamps at max");
    eq(AttentionPolicy.classBonus(0, "complex", false), 1, "bonus revives nothing special");

    // Изменения: обычный шаг, потолки, выход из нуля.
    eq(AttentionPolicy.apply(5, -1), 4, "minus one");
    eq(AttentionPolicy.apply(5, -2), 3, "minus two");
    eq(AttentionPolicy.apply(1, -2), 0, "minus clamps at zero");
    eq(AttentionPolicy.apply(0, -1), 0, "zero stays zero");
    eq(AttentionPolicy.apply(7, 2), 8, "plus clamps at max");
    eq(AttentionPolicy.apply(3, 1), 4, "plus one");
    eq(AttentionPolicy.apply(0, 1), 2, "revive from zero by open");
    eq(AttentionPolicy.apply(0, 2), 2, "revive from zero by accept");

    // Гейт: только назначенные и не чаще GAP на знак.
    long now = 100_000_000L;
    check(!AttentionPolicy.due(-1, 0, now), "due skips unset");
    check(AttentionPolicy.due(3, 0, now), "first change allowed");
    check(AttentionPolicy.due(3, now - AttentionPolicy.GAP, now), "allowed exactly after gap");
    check(!AttentionPolicy.due(3, now - AttentionPolicy.GAP + 1, now), "blocked inside gap");
    check(!AttentionPolicy.due(3, now, now), "blocked right after change");
    check(AttentionPolicy.due(0, 0, now), "zero can change (revive)");

    System.out.println("AttentionPolicyTest PASS " + count);
  }
}
