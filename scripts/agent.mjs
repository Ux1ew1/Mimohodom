#!/usr/bin/env node
// Консольный клиент для проверки «Мимоходом» агентом: каждое действие пользователя — одна команда.
//   node scripts/agent.mjs help
//   node scripts/agent.mjs ui.tap "Сохранить заметку"
// Полное описание — docs/AGENT.md. Нужна сборка agent (scripts/build-agent.ps1) на устройстве или эмуляторе.
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const PACKAGE = 'app.fastnotes';
const AUTHORITY = `${PACKAGE}.agent`;
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

// ===== Чистые функции (проверяются tests/agent-client.cjs) =====

/** Слово в двойных кавычках: мост всегда читает его как позиционный аргумент. */
export function quoteArg(value) {
  const escaped = String(value)
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\t/g, '\\t')
    .replace(/\r/g, '\\r');
  return `"${escaped}"`;
}

/** Из argv получает строку команды моста. Опции вида --ключ[=значение] остаются опциями. */
export function buildLine(argv) {
  const [name, ...rest] = argv;
  let literal = false;
  const words = rest.map((word) => {
    if (!literal && word === '--') {
      literal = true;
      return null;
    }
    const option = !literal && /^--[A-Za-z][\w-]*(=|$)/.exec(word);
    if (option) {
      const eq = word.indexOf('=');
      return eq < 0 ? word : `${word.slice(0, eq + 1)}${quoteArg(word.slice(eq + 1))}`;
    }
    return quoteArg(word);
  });
  return [name, ...words.filter((w) => w !== null)].join(' ');
}

/** Достаёт JSON ответа из вывода `content call` («Result: Bundle[{r=...}]»). */
export function parseBundle(output) {
  const match = /\br=([A-Za-z0-9_-]*)/.exec(output);
  if (!match) return null;
  return JSON.parse(Buffer.from(match[1], 'base64url').toString('utf8'));
}

/** Строка для оболочки устройства: одинарные кавычки со всеми спецсимволами внутри. */
export function shellQuote(value) {
  return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

// ===== adb =====

function findAdb() {
  if (process.env.ADB) return process.env.ADB;
  const local = join(root, '.tools', 'android', 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  return existsSync(local) ? local : 'adb';
}

let serial = process.env.ANDROID_SERIAL || '';

function adb(args, { binary = false, input } = {}) {
  const full = serial ? ['-s', serial, ...args] : args;
  const result = spawnSync(findAdb(), full, {
    encoding: binary ? 'buffer' : 'utf8',
    maxBuffer: 256 * 1024 * 1024,
    input,
  });
  if (result.error) {
    fail(`не удалось запустить adb (${result.error.message}). Укажите путь в переменной ADB.`);
  }
  return result;
}

function shell(command) {
  const r = adb(['shell', command]);
  return { code: r.status, out: (r.stdout || '').trim(), err: (r.stderr || '').trim() };
}

function fail(message, code = 1) {
  console.error(`ОШИБКА: ${message}`);
  process.exit(code);
}

// ===== Мост =====

function bridge(line) {
  const arg = `b64:${Buffer.from(line, 'utf8').toString('base64url')}`;
  const r = adb(['shell', 'content', 'call', '--uri', `content://${AUTHORITY}`, '--method', 'run', '--arg', arg]);
  const output = `${r.stdout || ''}${r.stderr || ''}`;
  const reply = parseBundle(output);
  if (reply) return reply;
  let why = output.trim() || 'пустой ответ';
  if (/no devices|device .* not found|offline|unauthorized/i.test(why)) why = `устройство недоступно: ${why}`;
  else if (/Permission Denial|SecurityException/i.test(why)) why = `доступ запрещён (нужен adb shell): ${why}`;
  else if (/Unable to find provider|Could not find provider|No provider|null/i.test(why) || !/Result/.test(why))
    why = `мост не отвечает. Установлена ли сборка agent поверх обычной? (node scripts/agent.mjs install) Вывод: ${why}`;
  return { ok: false, error: why };
}

function printReply(reply, asJson) {
  if (asJson) {
    console.log(JSON.stringify(reply, null, 2));
    return;
  }
  if (reply.ok) {
    if (reply.text) console.log(reply.text.replace(/\n+$/, ''));
    else if (reply.data) console.log(JSON.stringify(reply.data, null, 2));
    else console.log('ok');
  } else {
    console.error(`ОШИБКА: ${reply.error}`);
    if (reply.hint) console.error(`подсказка: ${reply.hint}`);
  }
}

// ===== Локальные команды (то, что не умеет приложение изнутри) =====

const MAIN = `${PACKAGE}/.MainActivity`;

function startActivity(extra = '') {
  shell('input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard');
  return shell(`am start -n ${MAIN} ${extra}`);
}

function waitForeground(timeoutMs = 15000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const r = bridge('ping');
    if (r.ok && r.data && r.data.foreground) return true;
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 400);
  }
  return false;
}

function nativeDump() {
  const path = '/sdcard/agent-ui.xml';
  const d = shell(`uiautomator dump ${path}`);
  if (d.code !== 0) fail(`uiautomator dump: ${d.out || d.err}`);
  const xml = (adb(['exec-out', 'cat', path]).stdout || '').toString();
  const lines = [];
  for (const node of xml.matchAll(/<node [^>]*>/g)) {
    const attr = (name) => (new RegExp(`${name}="([^"]*)"`).exec(node[0]) || [])[1] || '';
    const text = attr('text');
    const desc = attr('content-desc');
    const click = attr('clickable') === 'true';
    if (!text && !desc && !click) continue;
    const cls = attr('class').split('.').pop();
    lines.push(`${cls}${text ? ` "${text}"` : ''}${desc ? ` desc="${desc}"` : ''}${click ? ' [click]' : ''} ${attr('bounds')}`);
  }
  return lines.join('\n') || '(пусто)';
}

/** dist/mimohodom-<версия>-agent.apk, версия берётся из app/build.gradle.kts. */
export function defaultApk() {
  const gradle = readFileSync(join(root, 'app', 'build.gradle.kts'), 'utf8');
  const version = (/versionName = "([^"]+)"/.exec(gradle) || [])[1] || 'unknown';
  return join(root, 'dist', `mimohodom-${version}-agent.apk`);
}

function screenshot(file) {
  const r = adb(['exec-out', 'screencap', '-p'], { binary: true });
  if (r.status !== 0 || !r.stdout || r.stdout.length < 100) fail('screencap не вернул изображение (устройство спит?)');
  const out = file || join(root, '.tools', 'agent-shots', `shot-${new Date().toISOString().replace(/[:.]/g, '-')}.png`);
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, r.stdout);
  return out;
}

const LOCAL = {
  devices() {
    return adb(['devices', '-l']).stdout.trim();
  },
  doctor() {
    const lines = [];
    const devices = adb(['devices']).stdout.split('\n').slice(1).filter((l) => /\bdevice\b/.test(l));
    lines.push(`устройств: ${devices.length}${devices.length ? ` (${devices.map((l) => l.split('\t')[0]).join(', ')})` : ''}`);
    if (devices.length === 0) return `${lines.join('\n')}\nподключите телефон по USB (отладка по USB) или запустите эмулятор`;
    const version = shell(`dumpsys package ${PACKAGE} | grep -m1 versionName`).out;
    lines.push(version ? `приложение: ${version.trim()}` : 'приложение не установлено: node scripts/agent.mjs install');
    const ping = bridge('ping');
    lines.push(ping.ok ? `мост: ${ping.text}` : `мост: нет (${ping.error})`);
    return lines.join('\n');
  },
  build() {
    const r = spawnSync('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', join(root, 'scripts', 'build-agent.ps1')], { stdio: 'inherit' });
    if (r.status !== 0) fail('сборка agent не удалась');
    return 'сборка готова';
  },
  install(args) {
    const apk = args[0] || defaultApk();
    if (!existsSync(apk)) fail(`нет файла ${apk}. Соберите: node scripts/agent.mjs build`);
    // Без -g: разрешения на личном телефоне не выдаём, их подтверждает пользователь.
    const r = adb(['install', '-r', apk]);
    const out = `${r.stdout}${r.stderr}`.trim();
    if (r.status !== 0 || /Failure/.test(out)) {
      fail(`${out}\nЕсли подпись другая, сначала удалите приложение (потеряются данные!): adb uninstall ${PACKAGE}`);
    }
    return out;
  },
  launch(args, opts) {
    if (opts.fresh) shell(`am force-stop ${PACKAGE}`);
    const r = startActivity();
    if (r.code !== 0) fail(r.out || r.err);
    return waitForeground() ? `запущено; ${bridge('state').text}` : 'активность запущена, но мост не видит её на экране (экран заблокирован? приложение собрано как agent?)';
  },
  stop() {
    shell(`am force-stop ${PACKAGE}`);
    return 'остановлено';
  },
  reset(args, opts) {
    if (!opts.yes) fail(`reset стирает ВСЕ данные ${PACKAGE} на устройстве (заметки, чаты, настройки). Повторите с --yes, только на тестовом устройстве или эмуляторе.`, 2);
    return shell(`pm clear ${PACKAGE}`).out;
  },
  shot(args) {
    return screenshot(args[0]);
  },
  logcat(args, opts) {
    const pid = shell(`pidof ${PACKAGE}`).out.split(/\s+/)[0];
    const lines = Number(opts.lines || 200);
    const filter = opts.bridge ? ['AgentBridge:I', '*:S'] : pid ? [`--pid=${pid}`] : [];
    const r = adb(['logcat', '-d', '-v', 'time', '-t', String(lines), ...filter]);
    return (r.stdout || r.stderr).trim() || '(лог пуст)';
  },
  'native-dump'() {
    return nativeDump();
  },
  shortcut(args) {
    const actions = { 'new-note': 'NEW_NOTE', 'new-chat': 'NEW_CHAT', notes: 'OPEN_NOTES', search: 'SEARCH' };
    const action = actions[args[0]];
    if (!action) fail(`shortcut: ${Object.keys(actions).join(' | ')}`);
    startActivity(`-a ${PACKAGE}.action.${action}`);
    return waitForeground() ? bridge('state').text : 'запущено';
  },
  'open-note'(args) {
    if (!/^\d+$/.test(args[0] || '')) fail('open-note <номер заметки>');
    startActivity(`--el note ${args[0]}`);
    return waitForeground() ? bridge('state').text : 'запущено';
  },
  'share-text'(args) {
    if (!args[0]) fail('share-text "текст или ссылка"');
    startActivity(`-a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT ${shellQuote(args[0])}`);
    return waitForeground() ? bridge('state').text : 'запущено';
  },
  adb(args) {
    const r = adb(args);
    return `${r.stdout || ''}${r.stderr || ''}`.trim();
  },
};

const LOCAL_HELP = `Локальные команды клиента (не через приложение):
  devices                       список устройств adb
  doctor                        устройство, версия приложения, отвечает ли мост
  build                         собрать сборку agent (scripts/build-agent.ps1)
  install [apk]                 установить сборку agent поверх текущей (adb install -r)
  launch [--fresh]              запустить и дождаться экрана; --fresh сначала останавливает приложение
  stop                          остановить приложение
  reset --yes                   стереть ВСЕ данные приложения (pm clear) — только тестовое устройство
  shot [файл.png]               снимок экрана (в .tools/agent-shots/, путь печатается)
  logcat [--lines=200] [--bridge]  лог приложения; --bridge — только команды моста
  native-dump                   дерево экрана через uiautomator (видит и системные окна)
  shortcut new-note|new-chat|notes|search   быстрые действия значка приложения
  open-note <id>                открыть заметку, как из уведомления
  share-text "текст"            «Поделиться» текстом или ссылкой в приложение
  adb <аргументы>               любая команда adb
  batch <файл|->                выполнить команды построчно (# — комментарий; --keep-going не останавливаться на ошибке)
Общие флаги клиента: --json (сырой JSON ответа), --serial=ID (устройство).
Команды приложения (ui.*, notes.*, chat.*, …) — ниже, их печатает мост.`;

// ===== Разбор аргументов клиента =====

/** Флаги локальных команд. Команды приложения получают все свои опции целиком (у history тоже есть --lines). */
function splitClientFlags(argv) {
  const opts = {};
  const rest = [];
  for (const word of argv) {
    const m = /^--(json|fresh|yes|lines|bridge|keep-going)(?:=(.*))?$/.exec(word);
    if (m) opts[m[1]] = m[2] === undefined ? true : m[2];
    else rest.push(word);
  }
  return { opts, rest };
}

function runOne(argv, opts) {
  const [name, ...args] = argv;
  if (name === 'help' && !args.length) {
    console.log(LOCAL_HELP);
    console.log('');
    const r = bridge('help');
    if (r.ok) console.log(r.text);
    else console.log(`(справка приложения недоступна: ${r.error})`);
    return true;
  }
  if (LOCAL[name]) {
    const own = splitClientFlags(args);
    const merged = { ...opts, ...own.opts };
    const out = LOCAL[name](own.rest, merged);
    if (merged.json) console.log(JSON.stringify({ ok: true, text: out }));
    else if (out) console.log(out);
    return true;
  }
  const reply = bridge(buildLine(argv));
  printReply(reply, argv.includes('--json'));
  return reply.ok;
}

function batch(file, opts) {
  const text = file === '-' || !file ? readFileSync(0, 'utf8') : readFileSync(file, 'utf8');
  let failed = 0;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    console.log(`> ${line}`);
    // Строка файла разбирается теми же правилами кавычек, что и в мосте.
    const words = tokenizeLine(line);
    const ok = words[0] === 'batch' ? false : runOne(words, opts);
    if (!ok) {
      failed++;
      if (!opts['keep-going']) {
        console.error(`остановлено на ошибке (строка: ${line})`);
        return 1;
      }
    }
  }
  return failed ? 1 : 0;
}

/** Те же правила кавычек, что в AgentCommand.tokenize. */
export function tokenizeLine(line) {
  const words = [];
  let word = '';
  let inWord = false;
  for (let i = 0; i < line.length; ) {
    const c = line[i];
    if (c === '"') {
      inWord = true;
      i++;
      let closed = false;
      while (i < line.length) {
        const d = line[i++];
        if (d === '"') { closed = true; break; }
        if (d === '\\' && i < line.length) {
          const e = line[i++];
          word += e === 'n' ? '\n' : e === 't' ? '\t' : e === 'r' ? '\r' : e;
        } else word += d;
      }
      if (!closed) throw new Error('не закрыта двойная кавычка');
    } else if (c === "'") {
      inWord = true;
      const end = line.indexOf("'", i + 1);
      if (end < 0) throw new Error('не закрыта одинарная кавычка');
      word += line.slice(i + 1, end);
      i = end + 1;
    } else if (/\s/.test(c)) {
      if (inWord) { words.push(word); word = ''; inWord = false; }
      i++;
    } else { inWord = true; word += c; i++; }
  }
  if (inWord) words.push(word);
  return words;
}

function main() {
  const argv = [];
  for (const word of process.argv.slice(2)) {
    const m = /^--serial=(.+)$/.exec(word);
    if (m) serial = m[1];
    else argv.push(word);
  }
  const rest = argv;
  const opts = { 'keep-going': argv.includes('--keep-going') };
  if (rest.length === 0) {
    console.log(LOCAL_HELP);
    process.exit(0);
  }
  if (rest[0] === 'batch') process.exit(batch(rest.slice(1).find((w) => !w.startsWith('--')), opts));
  process.exit(runOne(rest, opts) ? 0 : 1);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main();
