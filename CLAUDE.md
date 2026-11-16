# CLAUDE.md — быстрый вход в проект «Мимоходом»

Нативное Android-приложение на Java, `K:\Projects\fast`, пакет `app.fastnotes`, GitHub `Ux1ew1/Mimohodom`. Общается с пользователем по-русски. Подробности — в `AGENTS.md` (поведение по версиям), `README.md` (секция на версию), `VERIFICATION.md` (что проверено и что НЕ проверено на устройстве), `docs/AGENT.md` (консольный мост). Не изучай проект с нуля — начни с этих файлов и этой памятки.

## Состояние на 16.11.2026
- Версия **0.9.1** (`versionCode = 35`), SQLite `Store` версии **8** (папки добавлены в 8). Рабочая ветка `agent-console-bridge` (HEAD 998c58e) содержит всё: 0.6.x–0.9.1, папки, видео YouTube, вопросы о планах (`CheckIn`), память чата, мост для агента, исправления по отчёту тестировщика.
- Локальный `main` отстаёт (da4912a, это 0.9.0 картинки); `origin/main` на 6d4f7a0 (0.6.1). **С версии 0.6.2 ничего не отправлено в GitHub**: пользователь каждый раз отвечает «Сначала проверю». Пушить только по прямой просьбе; без force (при расхождении истории: ветка-бэкап и rebase, не force). После пуша результат Actions записывать в `VERIFICATION.md` отдельно от локальных проверок.
- `reports/` не отслеживается git (отчёты `app-tester`), не добавлять в коммиты без просьбы.
- На устройстве почти ничего не проверялось (нет эмулятора на машине агента; тестирует сам пользователь на телефоне). Не утверждай «работает на устройстве».

## Рабочий цикл пользователя
Я делаю этап → сборка → тесты → локальный коммит → отправляю APK (`dist/mimohodom-<версия>-debug.apk`, SendUserFile) → пользователь тестирует на телефоне и просит следующий шаг. Каждый релиз обновляет `AGENTS.md` (версия и поведение), `README.md`, `VERIFICATION.md`. Для установки поверх: `versionCode` растёт, `applicationId` и отладочный ключ те же. Перед выдачей APK проверить версию и подпись.

## Сборка и проверки (Windows)
- Локальные инструменты в `.tools` (jdk, android, gradle 8.11.1, ndk, cmake). Собирать через **Bash**, не PowerShell (PS 5.1 считает вывод компилятора в stderr ошибкой): `JAVA_HOME=.tools/jdk/*`, `ANDROID_HOME=.tools/android`, `.tools/gradle/gradle-8.11.1/bin/gradle.bat assembleDebug lintDebug assembleDebugAndroidTest`. Либо `scripts/build-local.ps1`. Сборка agent: `scripts/build-agent.ps1` / `node scripts/agent.mjs build`.
- Тесты JVM без Android: `scripts/test-core, -polza, -manager, -aicore, -reminders, -images, -image-input, -image-list, -checkin, -folders, -video, -agent .ps1`, плюс `node tests/metadata-sql.cjs` и `node tests/manager-sql.cjs`. Все зарегистрированы в `.github/workflows/android.yml`; новый тест добавляй и туда. В PowerShell-скриптах `"-Dfile.encoding=UTF-8"` в кавычках.
- Androidtest (`ManagerStorageTest` и др.) компилируются, но не запускались.

## Архитектура (кратко)
- UI целиком в коде, без XML: `MainActivity` (~4500 строк; помощники `text/button/row/column/gap/pad/shape/ripple/secondary/section/toggle/settingsRow`, `Glyph`). Вкладки: 0 «Заметки», 1 «Записать» (старт), 2 «ИИ-чат»; настройки — индекс 3 (хаб из 4 разделов: ИИ, напоминания, помощник, приложение; автосохранение).
- Данные: `Store` (SQLite + FTS), `Note`, `ManagerStore`, `FolderStore`. Новые столбцы — только в конец; миграции идемпотентны (`hasColumn`); `Store.onCreate` должен сохранять ровно 8 inline-вызовов `execSQL` (на это опираются node-тесты, извлекающие SQL из `Store.java`).
- ИИ: `Polza` (облако, ключ в `Secrets`/Keystore), `ChatRunner`, `LocalLlm` (GGUF через JNI, `native/llama.cpp` — подмодуль) + `AiCore`/`AiCorePrompt` (Gemini Nano, только на переднем плане). `LocalLlm.cloudBackground(c)` = фоновые функции идут через Polza при выключенном локальном режиме или AICore; при GGUF приостановлены.
- Фон: `BackgroundAnalysis`, `ManagerJob`, `ReviewBudget` (дневной лимит 4, снимается пользователем), `SummaryRunner`, `CheckInJob` (помощник сам пишет вопросы), `Reminders`/`ReminderReceiver`/`BootReceiver`.
- Чистая Java с JVM-тестами (без Android — сохраняй это): `ReminderPolicy`, `ImageCleanup`, `ShareParser`, `CropMath`, `ImageList`, `CheckIn`, `ChatMemo`, `FolderPolicy`, `FolderSuggestion`, `VideoAnalysis`, `VideoMerge`, `YoutubeLink`, `AiCorePrompt`, `AgentCommand`.
- Напоминания: точные будильники при `SCHEDULE_EXACT_ALARM`, пропущенные до 7 дней, откладывание, повторы (`repeat_mode`, `lead_minutes`), утренняя сводка, каналы `reminders/suggestions/manager/digest/checkin`. Повтор двигается через `arm`/`advance` (не через `schedule`, она гасит показанное уведомление).
- Картинки: `files/images/<UUID>.jpg`; до 8 на заметку в одном столбце `notes.image` через запятую — всегда разбирать `ImageList.parse`. Любую новую ссылку на картинку добавляй в `ImageFiles.referenced`, иначе очистка удалит файл. Камера: `ACTION_IMAGE_CAPTURE` + `FileProvider` (`${applicationId}.files`), без CAMERA permission. Чат — по-прежнему одна картинка (`chatImage.<id>`).
- Мост агента: код только в `app/src/agent` (не должен попасть в debug/release). Новым кнопкам без текста задавай `setContentDescription`. Агент-тестировщик `app-tester` проверяет приложение на устройстве через `scripts/agent.mjs`.

## Git и окружение
- Команды git: `git -c safe.directory=K:/Projects/fast ...`. Автор коммита: переменные окружения `GIT_AUTHOR_NAME/EMAIL`, `GIT_COMMITTER_NAME/EMAIL` = `Claude` / `noreply@anthropic.com` (конфиг не менять). Коммиты заканчивай строкой `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Перед коммитом смотри `git diff --cached`, добавляй только файлы задачи. APK, `.tools`, ключи, `reports/` в Git не кладём.
- HTTPS к GitHub нестабилен (TLS handshake) — повторяй push/fetch.
- Правки файлов скриптами: определяй CRLF/LF (в индексе LF, в рабочем дереве по-разному), не создавай `\r\r\n`. Длинные Python-скрипты не вставляй в heredoc Bash — пиши в scratchpad через Write и запускай.
- Линт: пользовательские View от `ImageView` требуют `@SuppressLint("AppCompatCustomView")`.

## Не проверено нигде
UI на устройстве; запуск androidTest; Gemini Nano и GGUF на телефоне; реальные вызовы Polza.AI с картинками/видео; миграции 0.6.x → 0.9.1 на телефоне; облачная сборка Actions после 6d4f7a0.
