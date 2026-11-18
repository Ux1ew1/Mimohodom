// Проверяет чистые функции консольного клиента агента (scripts/agent.mjs) без устройства.
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { pathToFileURL } = require('node:url');
const path = require('node:path');

(async () => {
  const client = await import(pathToFileURL(path.join(__dirname, '..', 'scripts', 'agent.mjs')).href);
  let count = 0;
  const check = (actual, expected, name) => {
    assert.deepEqual(actual, expected, name);
    count++;
  };

  // Сборка строки команды: позиционные слова всегда в кавычках, опции остаются опциями
  check(client.buildLine(['ui.tap', 'Сохранить заметку']), 'ui.tap "Сохранить заметку"', 'позиционный аргумент');
  check(client.buildLine(['ui.tap', 'Назад', '--nth=1', '--exact']), 'ui.tap "Назад" --nth="1" --exact', 'опции');
  check(client.buildLine(['ui.type', '--hello', 'x']), 'ui.type --hello "x"', 'любое --слово считается опцией');
  check(client.buildLine(['ui.type', '--', '--hello']), 'ui.type "--hello"', 'терминатор --');
  check(client.buildLine(['ui.type', 'a "b"\nc']), 'ui.type "a \\"b\\"\\nc"', 'кавычки и перенос');
  check(client.buildLine(['ui.type', '--target=desc:Поиск по заметкам', 'текст']), 'ui.type --target="desc:Поиск по заметкам" "текст"', 'значение опции с пробелами');
  check(client.buildLine(['ping']), 'ping', 'команда без аргументов');
  check(client.quoteArg('c:\\dir'), '"c:\\\\dir"', 'обратная косая черта');

  // Токенайзер клиента (для batch) совпадает с правилами моста
  check(client.tokenizeLine('ui.tap "Сохранить заметку" --nth=1'), ['ui.tap', 'Сохранить заметку', '--nth=1'], 'tokenizeLine');
  check(client.tokenizeLine("a 'b c' \"d \\\"e\\\"\""), ['a', 'b c', 'd "e"'], 'tokenizeLine кавычки');
  for (const sample of ['', 'x y', '"q"', 'a\\b', 'строка\nвторая', "it's", '--x']) {
    const words = client.tokenizeLine(`cmd ${client.quoteArg(sample)}`);
    check(words, ['cmd', sample], `круговой обход «${sample}»`);
  }

  // Ответ content call
  const reply = { ok: true, text: 'привет', ms: 5 };
  const bundle = `Result: Bundle[{r=${Buffer.from(JSON.stringify(reply)).toString('base64url')}}]`;
  check(client.parseBundle(bundle), reply, 'parseBundle');
  check(client.parseBundle('Result: null'), null, 'parseBundle без ответа');
  check(client.parseBundle(''), null, 'parseBundle пусто');

  // Оболочка устройства
  check(client.shellQuote("it's $x"), `'it'\\''s $x'`, 'shellQuote');

  // Версия для имени APK берётся из Gradle
  check(/mimohodom-\d+\.\d+\.\d+-agent\.apk$/.test(client.defaultApk()), true, 'имя APK');

  // Запуск без устройства: справка печатается, ошибка adb не роняет клиент трассой
  const help = execFileSync(process.execPath, [path.join(__dirname, '..', 'scripts', 'agent.mjs')], { encoding: 'utf8' });
  check(help.includes('Локальные команды клиента'), true, 'справка без аргументов');

  console.log(`agent-client: ${count} проверок пройдено`);
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
