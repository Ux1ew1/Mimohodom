package app.fastnotes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Компактный локальный эмбеддинг для поиска: хешированные словесные признаки и тематические оси.
 * Ничего не отправляет в сеть и не требует скачивания модели. Это не замена большой нейросетевой
 * модели, а детерминированный индекс смысловых ассоциаций для личного архива.
 */
public final class SemanticEmbedding {
  public static final int VERSION = 1, DIMENSIONS = 256;
  public static final double MIN_SCORE = 0.16;
  private static final int CONCEPTS = 32;

  private static final String[] TOPIC_NAMES = {
    "Путешествия", "Ремонт", "Здоровье", "Покупки", "Рецепты", "Работа", "Финансы", "Семья",
    "Праздники", "Учёба", "Автомобиль", "Документы", "Технологии", "Спорт", "Питомцы", "Сад",
    "Жильё", "Домашние дела", "Культура", "Книги", "Переезд", "Право", "Сон", "Звонки",
    "Планы", "Фото и видео", "Одежда", "Банк", "Погода", "Транспорт", "Доставка", "Хобби"
  };

  private static final String[][] AXES = {
    {"отпуск", "поездк", "путешеств", "море", "морю", "моря", "морск", "билет", "отел", "гостиниц", "аэропорт", "рейс", "виза", "чемодан", "экскурс", "санатор", "каникул", "туризм", "пляж"},
    {"ремонт", "строй", "сверл", "плитк", "обои", "ламинат", "краск", "сантехн", "электрик", "шпакл", "потолок"},
    {"врач", "доктор", "больниц", "клиник", "лекарств", "таблет", "анализ", "здоров", "стоматолог", "терапевт"},
    {"купить", "покупк", "магазин", "заказ", "цен", "стоимост", "оплат", "чек", "продукт"},
    {"рецепт", "готов", "ингредиент", "мука", "сахар", "молоко", "духовк", "варить", "жарить", "салат", "суп", "ужин", "обед"},
    {"работ", "офис", "начальник", "коллег", "отчет", "совещан", "встреч", "проект", "дедлайн"},
    {"деньг", "финанс", "бюджет", "зарплат", "налог", "кредит", "ипотек", "счет", "карт", "перевод"},
    {"мам", "пап", "семь", "жена", "муж", "сын", "дочь", "бабушк", "дедушк", "родител"},
    {"рожден", "юбилей", "праздник", "годовщин", "свадьб", "подарок", "торт"},
    {"школ", "учеб", "урок", "экзамен", "университет", "курс", "лекц", "домашн"},
    {"машин", "авто", "бензин", "водител", "права", "страхов", "шин", "сервис", "парковк"},
    {"паспорт", "документ", "договор", "заявлен", "справк", "виза", "нотариус", "подпис"},
    {"телефон", "компьютер", "ноутбук", "програм", "приложен", "парол", "интернет", "почт", "сайт"},
    {"спорт", "трениров", "бег", "зал", "фитнес", "йог", "плаван", "велосипед"},
    {"кошк", "кот", "собак", "питом", "ветеринар", "корм", "выгул"},
    {"сад", "огород", "дач", "растен", "цвет", "полив", "урожай", "семен"},
    {"квартир", "дом", "домофон", "ключ", "подъезд", "сосед", "коммунал", "аренд"},
    {"уборк", "стирк", "помыть", "выброс", "разобрать", "порядок", "хозяйств"},
    {"кино", "фильм", "сериал", "театр", "концерт", "музей", "выставк", "билет"},
    {"книг", "читать", "чтен", "роман", "библиотек", "автор", "глав"},
    {"переезд", "коробк", "вещи", "грузчик", "новоселье", "транспорт"},
    {"юрист", "закон", "суд", "претенз", "жалоб", "штраф", "адвокат"},
    {"сон", "спать", "будильник", "утро", "режим", "отдых"},
    {"встреч", "созвон", "позвонить", "звонок", "собеседован", "переговор"},
    {"план", "задач", "дело", "срок", "список", "чеклист", "пункт", "шаг"},
    {"фото", "камер", "снимок", "видео", "альбом"},
    {"одежд", "обув", "куртк", "плать", "размер", "ателье", "стирк"},
    {"банк", "банкомат", "вклад", "валют", "рубл", "доллар", "евро"},
    {"погод", "дождь", "снег", "мороз", "жара", "ветер", "зонт"},
    {"поезд", "метро", "автобус", "маршрут", "остановк", "вокзал", "транспорт"},
    {"почт", "посылк", "доставк", "курьер", "отправлен", "трек"},
    {"хобби", "рисован", "музык", "игр", "рукодел", "увлечен"}
  };

  private SemanticEmbedding() {}

  public static String text(Note note) {
    return note.title + "\n" + note.title + "\n" + note.body + "\n" + note.searchText;
  }

  /** Меняется при правке текста или поисковых метаданных, даже если updated сохранён. */
  public static String fingerprint(Note note) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(Integer.toString(VERSION).getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(text(note).getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b));
      return out.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static float[] embed(String raw) {
    float[] vector = new float[DIMENSIONS];
    String text = Retrieval.normalize(raw == null ? "" : raw);
    List<String> tokens = new ArrayList<>();
    for (String token : text.split("[^\\p{L}\\p{N}]+")) {
      if (token.length() < 2) continue;
      tokens.add(token);
      String stem = token.length() > 5 ? token.substring(0, token.length() - 2) : token;
      add(vector, CONCEPTS + Math.floorMod(stableHash("w:" + stem), DIMENSIONS - CONCEPTS), 1f);
    }
    for (int axis = 0; axis < AXES.length; axis++) {
      int hits = 0;
      for (String keyword : AXES[axis]) {
        boolean found = false;
        for (String token : tokens)
          if (token.startsWith(keyword)) {
            found = true;
            break;
          }
        if (found) hits++;
      }
      // Несколько слов одной темы усиливают ось, но не дают длинной заметке безграничный вес.
      if (hits > 0) vector[axis] = 2.5f + Math.min(3, hits - 1) * 0.5f;
    }
    double norm = 0;
    for (float value : vector) norm += value * value;
    if (norm > 0) {
      float scale = (float) (1 / Math.sqrt(norm));
      for (int i = 0; i < vector.length; i++) vector[i] *= scale;
    }
    return vector;
  }

  public static double similarity(float[] a, float[] b) {
    if (a == null || b == null || a.length != DIMENSIONS || b.length != DIMENSIONS) return 0;
    double out = 0;
    for (int i = 0; i < DIMENSIONS; i++) out += a[i] * b[i];
    return Math.max(0, out);
  }

  static String primaryTopic(float[] vector) {
    int best = -1;
    float weight = 0.15f;
    for (int i = 0; i < CONCEPTS; i++) {
      if (vector[i] <= weight) continue;
      best = i;
      weight = vector[i];
    }
    return best < 0 ? "" : TOPIC_NAMES[best];
  }

  public static byte[] encode(float[] vector) {
    ByteBuffer out = ByteBuffer.allocate(DIMENSIONS * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (float value : vector) out.putFloat(value);
    return out.array();
  }

  public static float[] decode(byte[] stored) {
    if (stored == null || stored.length != DIMENSIONS * 4) return null;
    float[] out = new float[DIMENSIONS];
    ByteBuffer.wrap(stored).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
    return out;
  }

  private static void add(float[] vector, int index, float value) {
    vector[index] += value;
  }

  private static int stableHash(String value) {
    int hash = 0x811c9dc5;
    for (int i = 0; i < value.length(); i++) {
      hash ^= value.charAt(i);
      hash *= 0x01000193;
    }
    return hash & 0x7fffffff;
  }
}
