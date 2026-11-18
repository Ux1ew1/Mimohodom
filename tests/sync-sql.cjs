// Схема и триггеры синхронизации (Store.addSync) на настоящем SQLite: берём SQL прямо из Store.java.
const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/java/app/fastnotes/Store.java'), 'utf8');

function method(signature) {
  const start = source.indexOf(signature);
  if (start < 0) throw Error(`Method missing: ${signature}`);
  const opening = source.indexOf('{', start);
  let depth = 1, quoted = false, escaped = false;
  for (let i = opening + 1; i < source.length; i++) {
    const c = source[i];
    if (quoted) { if (escaped) escaped = false; else if (c === '\\') escaped = true; else if (c === '"') quoted = false; }
    else if (c === '"') quoted = true;
    else if (c === '{') depth++;
    else if (c === '}' && --depth === 0) return source.slice(opening + 1, i);
  }
  throw Error('Unclosed method');
}

// Вычисляет выражение вида "a" + "b" + имя + "c" до запятой или закрывающей скобки.
function expression(text, at, env) {
  let out = '', i = at;
  for (;;) {
    while (/\s/.test(text[i])) i++;
    if (text[i] === '"') {
      let j = i + 1;
      while (text[j] !== '"') j += text[j] === '\\' ? 2 : 1;
      out += JSON.parse(text.slice(i, j + 1));
      i = j + 1;
    } else if (/[A-Za-z_]/.test(text[i])) {
      let j = i;
      while (/[A-Za-z_0-9]/.test(text[j])) j++;
      const name = text.slice(i, j);
      if (!(name in env)) throw Error('unknown identifier ' + name);
      out += env[name];
      i = j;
    } else throw Error('bad expression at ' + text.slice(i, i + 20));
    while (/\s/.test(text[i])) i++;
    if (text[i] === '+') { i++; continue; }
    return [out, i];
  }
}

// Все db.execSQL(...) метода по порядку; переменные stamp/now/table подставляются.
function statements(body, env = {}) {
  const out = [];
  const localEnv = { ...env };
  for (const m of body.matchAll(/String (stamp|now)\s*=\s*/g)) {
    const [value] = expression(body, m.index + m[0].length, localEnv);
    localEnv[m[1]] = value;
  }
  for (const m of body.matchAll(/db\.execSQL\(\s*/g)) {
    const [sql, end] = expression(body, m.index + m[0].length, localEnv);
    const rest = body.slice(end, end + 40);
    out.push({ sql, params: /^\s*,/.test(rest) });
  }
  return out;
}

let count = 0;
function check(value, name) { if (!value) throw Error('FAIL: ' + name); count++; }

const create = method('void onCreate(SQLiteDatabase db)');
const base = statements(create).filter(s => !s.params).map(s => s.sql);
check(base.length === 8, 'onCreate по-прежнему содержит ровно 8 встроенных запросов');
const sync = method('void addSync(SQLiteDatabase db)');
const syncStatements = statements(sync, { table: '@TABLE@' });
check(syncStatements.filter(s => s.sql.includes('CREATE TRIGGER')).length === 5, 'пять триггеров синхронизации');

function build(withNotes) {
  const db = new DatabaseSync(':memory:');
  db.exec('PRAGMA foreign_keys=ON');
  const assistant = statements(method('void addAssistantColumns(SQLiteDatabase db)')).map(s => s.sql);
  for (const sql of base) {
    if (sql.startsWith('ALTER TABLE notes ADD COLUMN image')) for (const a of assistant) db.exec(a);
    db.exec(sql);
  }
  // addMetadata без заполнения; addReminderOptions и addFolders: их ALTER/CREATE уже в onCreate не входят.
  const metadata = statements(method('void addMetadata(SQLiteDatabase db)')).map(s => s.sql);
  db.exec(metadata[0]); db.exec(metadata[1]);
  if (withNotes) {
    db.prepare('INSERT INTO notes(title,body,created,updated,metadata,search_text) VALUES(?,?,?,?,?,?)').run('Старая 1', 'тело 1', 1000, 1100, '{}', 'старая');
    db.prepare('INSERT INTO notes(title,body,created,updated,metadata,search_text) VALUES(?,?,?,?,?,?)').run('Старая 2', 'тело 2', 2000, 2200, '{}', 'вторая');
  }
  for (const sql of metadata.slice(2)) db.exec(sql);
  for (const s of statements(method('void addReminderOptions(SQLiteDatabase db)'))) db.exec(s.sql);
  const folders = statements(method('void addFolders(SQLiteDatabase db)')).map(s => s.sql);
  for (const sql of folders) db.exec(sql);
  if (withNotes) db.exec("INSERT INTO folders(name,key,created) VALUES('Работа','работа',3000)");
  return db;
}
function runSync(db) {
  for (const s of syncStatements) {
    if (s.sql.includes('@TABLE@')) {
      for (const t of ['notes', 'folders']) { try { db.exec(s.sql.replaceAll('@TABLE@', t)); } catch (e) { if (!String(e.message).includes('duplicate column')) throw e; } }
    } else if (s.params) {
      if (s.sql.startsWith('INSERT OR IGNORE INTO sync_meta')) db.prepare(s.sql).run(Date.now(), 'a'.repeat(32), 'Тест');
      else if (s.sql.startsWith('UPDATE folders SET uid=?')) { /* перебор папок делает Java */ for (const f of db.prepare("SELECT id FROM folders WHERE uid=''").all()) db.prepare(s.sql).run(`folder-${f.id}`, 3000, f.id, f.id); }
      else throw Error('необработанный запрос с параметрами: ' + s.sql);
    } else db.exec(s.sql);
  }
}
// То же, что SqliteSyncStore.stampPending(): нумерация заметок, созданных после последней отправки.
function stampPending(db) {
  for (const row of db.prepare('SELECT id FROM notes WHERE sync_seq=0 ORDER BY id').all()) {
    db.exec("UPDATE sync_meta SET seq=seq+1,hlc=max(hlc+1,CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)) WHERE id=1");
    const m = db.prepare('SELECT hlc,seq FROM sync_meta WHERE id=1').get();
    db.prepare("UPDATE notes SET uid=CASE WHEN uid='' THEN lower(hex(randomblob(16))) ELSE uid END,sync_ts=?,sync_seq=?,sync_from='' WHERE id=?").run(m.hlc, m.seq, row.id);
  }
}
const meta = db => db.prepare('SELECT * FROM sync_meta WHERE id=1').get();

// ---- Новая установка ----
{
  const db = build(false);
  runSync(db);
  check(meta(db).seq === 0 && meta(db).device.length === 32, 'sync_meta создан');
  db.prepare('INSERT INTO notes(title,body,created,updated,metadata,search_text) VALUES(?,?,?,?,?,?)').run('Первая', 'молоко', 10, 10, '{}', 'молоко');
  let n = db.prepare('SELECT * FROM notes WHERE id=1').get();
  check(n.uid === '' && n.sync_seq === 0 && meta(db).seq === 0, 'новая заметка ждёт нумерации и индекс поиска цел');
  check(db.prepare("SELECT count(*) c FROM notes_fts WHERE notes_fts MATCH 'молоко*'").get().c === 1, 'вставка заметки не ломает поисковый индекс');
  stampPending(db);
  n = db.prepare('SELECT * FROM notes WHERE id=1').get();
  check(/^[0-9a-f]{32}$/.test(n.uid), 'новая заметка получила uid');
  check(n.sync_seq === 1 && n.sync_ts > 0 && meta(db).seq === 1, 'новая заметка пронумерована');
  const t1 = n.sync_ts;
  db.prepare('UPDATE notes SET body=?,updated=? WHERE id=1').run('молоко и хлеб', 11);
  n = db.prepare('SELECT * FROM notes WHERE id=1').get();
  check(n.sync_seq === 2 && n.sync_ts > t1, 'правка текста двигает seq и ts');
  // Фоновые изменения ИИ ts не двигают.
  db.prepare("UPDATE notes SET metadata=?,search_text=?,ai_checked=11,ai_failed=0,suggestion=5,suggestion_reason='x' WHERE id=1").run('{"ai":{}}', 'молоко хлеб');
  n = db.prepare('SELECT * FROM notes WHERE id=1').get();
  check(n.sync_seq === 2 && meta(db).seq === 2, 'метаданные ИИ и предложения не меняют метки');
  // Переезд в папку — изменение.
  db.prepare("INSERT INTO folders(name,key,created,uid) VALUES('Дом','дом',5,'abc')").run();
  const f = db.prepare("SELECT * FROM folders WHERE key='дом'").get();
  check(f.uid === 'abc' && f.sync_seq === 3, 'папка с заданным uid сохранила его и получила seq');
  db.prepare('UPDATE notes SET folder=? WHERE id=1').run(f.id);
  check(db.prepare('SELECT sync_seq FROM notes WHERE id=1').get().sync_seq === 4, 'перенос в папку двигает seq');
  db.prepare("UPDATE folders SET name='Семья',key='семья' WHERE id=?").run(f.id);
  check(db.prepare('SELECT sync_seq FROM folders WHERE id=?').get(f.id).sync_seq === 5, 'переименование папки двигает seq');
  // Принятая от соседа запись: явный seq, триггеры не вмешиваются.
  const before = meta(db).seq;
  db.prepare("INSERT INTO notes(title,body,created,updated,metadata,search_text,uid,sync_ts,sync_seq,sync_from) VALUES('Чужая','текст',1,1,'{}','чужая','remote-uid',777,99,'peer')").run();
  const r = db.prepare("SELECT * FROM notes WHERE uid='remote-uid'").get();
  check(r.sync_ts === 777 && r.sync_seq === 99 && r.sync_from === 'peer' && meta(db).seq === before, 'принятая запись не перештампована');
  db.prepare("UPDATE notes SET body='правка соседа',sync_ts=888,sync_seq=100,sync_from='peer' WHERE uid='remote-uid'").run();
  check(db.prepare("SELECT sync_ts FROM notes WHERE uid='remote-uid'").get().sync_ts === 888 && meta(db).seq === before, 'принятая правка не перештампована');
  // Удаление оставляет надгробие.
  db.prepare('DELETE FROM notes WHERE id=1').run();
  const tomb = db.prepare("SELECT * FROM tombstones WHERE kind='note' AND uid=?").get(n.uid);
  check(tomb && tomb.seq === meta(db).seq && tomb.ts >= n.sync_ts && tomb.from_peer === '', 'удаление заметки оставило надгробие');
  db.prepare('DELETE FROM folders WHERE id=?').run(f.id);
  check(db.prepare("SELECT count(*) c FROM tombstones WHERE kind='folder'").get().c === 1, 'удаление папки оставило надгробие');
  // Поиск продолжает работать вместе с триггерами.
  check(db.prepare("SELECT count(*) c FROM notes_fts WHERE notes_fts MATCH 'чужая*'").get().c === 1, 'поисковый индекс в порядке');
  // ts не уменьшается, даже если часы телефона откатились: hlc растёт на 1.
  db.prepare("UPDATE sync_meta SET hlc=hlc+10000000000 WHERE id=1").run();
  const hlc = meta(db).hlc;
  db.prepare("INSERT INTO notes(title,body,created,updated,metadata,search_text) VALUES('Позже','т',1,1,'{}','т')").run();
  stampPending(db);
  check(db.prepare("SELECT sync_ts FROM notes WHERE title='Позже'").get().sync_ts === hlc + 1, 'часы не идут назад');
  // Правка заметки, не успевшей получить uid, выдаёт его триггером правки.
  db.prepare("INSERT INTO notes(title,body,created,updated,metadata,search_text) VALUES('Черновик','т',1,1,'{}','т')").run();
  db.prepare("UPDATE notes SET body='правка до первой отправки',updated=2 WHERE title='Черновик'").run();
  const draft = db.prepare("SELECT * FROM notes WHERE title='Черновик'").get();
  check(/^[0-9a-f]{32}$/.test(draft.uid) && draft.sync_seq > 0, 'правка до первой отправки выдала uid и seq');
  db.prepare("DELETE FROM notes WHERE title='Черновик'").run();
  db.prepare("DELETE FROM tombstones WHERE uid=?").run(draft.uid);
}

// ---- Обновление с версии 8: существующие записи получают uid и номера ----
{
  const db = build(true);
  runSync(db);
  const notes = db.prepare('SELECT id,uid,sync_ts,sync_seq,updated FROM notes ORDER BY id').all();
  check(notes.length === 2 && notes.every(n => /^[0-9a-f]{32}$/.test(n.uid)) && notes[0].uid !== notes[1].uid, 'старые заметки получили разные uid');
  check(notes[0].sync_seq === 1 && notes[1].sync_seq === 2 && notes[1].sync_ts === 2200, 'старые заметки пронумерованы, ts = время правки');
  const folder = db.prepare('SELECT * FROM folders').get();
  check(folder.uid === 'folder-1' && folder.sync_seq === 1, 'старая папка получила uid');
  check(meta(db).seq >= 2 && meta(db).hlc >= 2200, 'счётчики подтянуты к существующим данным');
  db.prepare("UPDATE notes SET title='Новое' WHERE id=1").run();
  db.prepare("INSERT INTO notes(title,body,created,updated,metadata,search_text) VALUES('Свежая','т',1,1,'{}','т')").run();
  stampPending(db);
  check(db.prepare("SELECT sync_seq FROM notes WHERE title='Свежая'").get().sync_seq === meta(db).seq, 'после обновления триггеры работают');
  runSync(db); // повторный вызов безопасен
  check(db.prepare('SELECT count(*) c FROM notes').get().c === 3, 'повторный запуск миграции безопасен');
}
console.log(`sync-sql OK (${count} проверок)`);
