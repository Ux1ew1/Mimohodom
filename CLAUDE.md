# Мимоходом — памятка для нового чата

Подробные правила и история версий — в [AGENTS.md](AGENTS.md) (читай его, он подгружается как инструкции проекта), сценарии — в [README.md](README.md), результаты проверок — в [VERIFICATION.md](VERIFICATION.md). Ниже только то, что экономит время.

## Что это

Нативное Android-приложение на Java, заметки с локальным SQLite и ИИ (Polza.AI, Gemini Nano, GGUF). Пакет `app.fastnotes`, исходники в `app/src/main/java/app/fastnotes/`. Версия **0.9.1** (`versionCode 35`), установлена на телефон автора. Ветка работы: `update-0.9-video-folders`, основная — `main`. Интерфейс и тексты — на русском; весь интерфейс собирается кодом в `MainActivity` (~5500 строк, XML-вёрстки нет).

## Карта кода

- `MainActivity` — экраны, жесты, настройки (хаб `settings` → `settings.ai/reminders/assistant/app`), ИИ-чат (`chatPage`, `chats`, `send`).
- `Store` (SQLite v8), `Note`, `FolderStore`, `ManagerStore` — данные. Новые столбцы `notes` — только в конец.
- `Polza` (запросы, `ask(..., guidance)`), `ChatRunner` (ответ чата в фоне), `Retrieval`/`SearchQuery`/`Metadata` — поиск и метаданные.
- `Reminders`, `ReminderPolicy`, `ReminderReceiver`, `BootReceiver` — напоминания и сводка. `BackgroundAnalysis`, `ManagerJob` — фоновые проверки.
- `ImageFiles`/`ImageRunner`/`Vision`, `VideoRunner`/`VideoAnalysis`/`YoutubeLink` — картинки и видео.
- **Вопросы о планах (0.9.1):** `CheckIn` (правила, без Android), `CheckInJob` (будильник → задача → один запрос → чат + уведомление), `ChatMemo` (серое «✓ Запомнил», итог). В чатах помощника (`checkInChat.<id>`) заметка по `draft` записывается сама, это единственное исключение из правила «ИИ предлагает — пользователь подтверждает».
- `app/src/agent`, `.claude/agents/app-tester.md` — отдельная сборка `agent` и агент-тестировщик (создано в другой сессии, см. AGENTS.md).

Правила без Android лежат отдельными классами и проверяются `scripts/test-*.ps1` — новые правила делай так же.

## Команды (Windows, PowerShell)

```powershell
.\scripts\build-local.ps1 -Tasks @('assembleDebug','lintDebug')   # сборка + lint
.\scripts\test-checkin.ps1; .\scripts\test-polza.ps1; .\scripts\test-core.ps1   # тесты, их много: scripts/test-*.ps1
.\.tools\android\platform-tools\adb.exe devices                    # телефон подключён по Wi-Fi
.\.tools\android\platform-tools\adb.exe install -r dist\mimohodom-<версия>-debug.apk
```

Выпуск: поднять `versionCode`/`versionName` в `app/build.gradle.kts`, скопировать `app/build/outputs/apk/debug/app-debug.apk` в `dist/mimohodom-<версия>-debug.apk` (в git не входит), проверить `aapt2 dump badging` и `apksigner verify --print-certs` (инструменты в `.tools/android/build-tools/35.0.0`; сертификат должен совпадать с прежней сборкой: SHA-256 `f66583ea…ed45c`), затем `adb install -r`. Обновить версию в AGENTS.md и запись в VERIFICATION.md.

## Подводные камни этой машины

- **Git:** `fatal: detected dubious ownership` — каждую команду запускай как `git -c safe.directory=K:/Projects/fast …`; глобальный конфиг не меняй. Идентичность не настроена, коммить так: `git -c user.name=Claude -c user.email=noreply@anthropic.com commit` (так подписаны прежние коммиты). В конце сообщения — строка `Co-Authored-By` из системной инструкции.
- **PowerShell 5.1 и stderr:** вывод Gradle/javac в stderr превращается в «ошибку» (`NativeCommandError`), хотя сборка успешна. Реальный результат смотри по `BUILD SUCCESSFUL` и коду выхода: `cmd /c "powershell -NoProfile -Command \"& .\scripts\build-local.ps1 -Tasks @('assembleDebug','lintDebug')\" > %TEMP%\build.log 2>&1"`. Список задач передавай массивом `@(...)`, иначе склеится в одну.
- **Кириллица в regex:** для регистронезависимого поиска нужен `(?iu)`.
- **Не вызывай `python`** в Bash (зависает). Правки скриптов — через Edit, а не `sed` (теряются кавычки).
- Рабочая папка может содержать правки других сессий: перед коммитом смотри `git status` и добавляй только свои файлы (`git add <файлы>`), никогда `git add -A`.
- Ограничения проверки: на устройстве и с реальной моделью новые функции (напоминания, вопросы о планах, видео, папки) не проверены; сборка и локальные тесты проходят.

## Принципы

После завершённой правки — проверки и локальный коммит с кратким описанием; платные запросы без автоповторов, с лимитами; пользовательские данные при миграциях сохраняются; ключи, APK и локальные инструменты в репозиторий не попадают.
