package app.fastnotes;

/** Shared daily cap and concise UI labels; detailed diagnostics stay in the log. */
public final class ReviewPolicy {
  public static boolean allowed(int count, boolean unlimited) {
    return unlimited || count < 4;
  }

  public static String summary(String status) {
    if (status.startsWith("Готовим выжимку")) return "ИИ готовит выжимку…";
    if (status.startsWith("Выжимка готова")) return "Выжимка готова к просмотру";
    if (status.startsWith("Не удалось подготовить выжимку"))
      return "Выжимка не готова — подробности в истории";
    if (status.startsWith("Просмотрено") || status.startsWith("Проверено"))
      return "Проверка завершена";
    if (status.startsWith("Проверяем")) return "Проверяем заметки…";
    if (status.contains("поставлена в очередь")) return "Ожидаем запуска проверки";
    if (status.startsWith("Проверка не завершена")) return "Не удалось завершить проверку";
    if (status.startsWith("Дневной лимит")) return "Дневной лимит исчерпан";
    if (status.startsWith("Нет доступных")) return "Нет новых заметок вне стопок";
    if (status.startsWith("Все доступные")) return "Нужен ручной повтор проверки";
    if (status.startsWith("Нет заметок")) return "Добавьте заметки для проверки";
    if (status.startsWith("Для фоновой")) return "Подключите модель в настройках";
    if (status.startsWith("Локальный")) return "Обзор доступен через Polza.AI";
    return "Результаты проверки — в истории";
  }
}
