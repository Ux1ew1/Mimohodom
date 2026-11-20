// Журнал агента (SQLite 14): настоящий SQL из AgentLog.java и миграция в Store.java.
const fs = require('node:fs');
const { DatabaseSync } = require('node:sqlite');
const assert = require('node:assert/strict');
const log = fs.readFileSync('app/src/main/java/app/fastnotes/AgentLog.java', 'utf8');
const store = fs.readFileSync('app/src/main/java/app/fastnotes/Store.java', 'utf8');
const manager = fs.readFileSync('app/src/main/java/app/fastnotes/ManagerStore.java', 'utf8');
let count = 0;
function check(value, message) { assert.ok(value, message); count++; }
const literal = body => [...body.matchAll(/"(?:\\.|[^"\\])*"/g)].map(s => JSON.parse(s[0])).join('');
function method(source, signature) {
  const start = source.indexOf(signature);
  assert.ok(start >= 0, 'method ' + signature);
  let depth = 0, i = source.indexOf('{', start);
  for (let j = i; j < source.length; j++) {
    if (source[j] === '{') depth++;
    if (source[j] === '}' && --depth === 0) return source.slice(i, j + 1);
  }
  throw new Error('unterminated ' + signature);
}
const execs = body => [...body.matchAll(/execSQL\(\s*((?:"(?:\\.|[^"\\])*"\s*\+?\s*)+)[,)]/g)].map(x => literal(x[1]));
const queries = body => [...body.matchAll(/rawQuery\(\s*((?:"(?:\\.|[^"\\])*"\s*\+?\s*)+),/g)].map(x => literal(x[1]));

const create = execs(method(log, 'static void create(SQLiteDatabase db)'));
check(create.length === 2, 'table and index from AgentLog.create');
check(create.every(sql => sql.includes('IF NOT EXISTS')), 'migration can run twice');
const db = new DatabaseSync(':memory:');
db.exec('PRAGMA foreign_keys=ON');
for (const sql of create) db.exec(sql);
for (const sql of create) db.exec(sql);
check(db.prepare("SELECT count(*) n FROM sqlite_master WHERE name='agent_log'").get().n === 1, 'one table');
const cols = db.prepare('PRAGMA table_info(agent_log)').all().map(c => c.name);
check(['id', 'created', 'action', 'data', 'undo', 'state'].every(c => cols.includes(c)), 'columns id, created, action, data, undo, state');

const now = 1_800_000_000_000, day = 86_400_000;
const insert = db.prepare('INSERT INTO agent_log(created,action,data) VALUES(?,?,?)');
insert.run(now - 31 * day, 'name', '{"note":1,"title":"Старое"}');
insert.run(now - 2 * day, 'create', '{"note":2,"title":"Дело"}');
insert.run(now - 60_000, 'group', '{"note":3,"title":"Стопка"}');
insert.run(now - 1000, 'answer', '{"note":4,"answer":"Да"}');
check(db.prepare('SELECT state FROM agent_log WHERE id=4').get().state === 'done', 'new entries are done');
check(db.prepare('SELECT undo FROM agent_log WHERE id=4').get().undo === '{}', 'undo defaults to empty');

const recent = queries(method(log, 'static List<Entry> recent(Store store, int limit, long sinceMs)'));
check(recent.length === 1, 'recent query extracted');
const rows = db.prepare(recent[0]).all(now - day, 20);
check(rows.length === 2 && rows[0].id === 4 && rows[1].id === 3, 'last day, newest first');
check(db.prepare(recent[0]).all(0, 2).length === 2, 'limit applies');

const undo = method(log, 'static String undo(Store store, long id)');
const pick = queries(undo)[0];
check(db.prepare(pick).get(3).action === 'group', 'undo reads the entry');
const mark = execs(undo).find(sql => sql.startsWith('UPDATE agent_log'));
db.prepare(mark).run('undone', 3);
check(db.prepare('SELECT state FROM agent_log WHERE id=3').get().state === 'undone', 'undo marks the entry');

const prune = execs(method(log, 'static void prune(Store store, long now)'));
db.prepare(prune[0]).run(now - 30 * day);
check(db.prepare('SELECT count(*) n FROM agent_log').get().n === 3, 'older than 30 days pruned');
check(!db.prepare('SELECT id FROM agent_log WHERE id=1').get(), 'the old one is gone');

// Отмена стопки: удаление строки note_groups снимает исходные (каскад), старая стопка собирается снова.
const ddl = [...manager.matchAll(/db\.execSQL\(\s*((?:"(?:\\.|[^"\\])*"\s*\+?\s*)+)\);/g)]
  .map(x => literal(x[1])).filter(sql => sql.startsWith('CREATE TABLE'));
db.exec('CREATE TABLE notes(id INTEGER PRIMARY KEY,title TEXT,body TEXT)');
for (const sql of ddl) db.exec(sql);
db.exec("INSERT INTO notes VALUES(1,'a',''),(2,'b',''),(3,'c',''),(10,'Старая',''),(20,'Новая','')");
db.exec('INSERT INTO note_groups(top) VALUES(20)');
db.exec('INSERT INTO note_members(note,top) VALUES(1,20),(2,20),(3,20),(10,20)');
db.exec('DELETE FROM note_groups WHERE top=20');
check(db.prepare('SELECT count(*) n FROM note_members').get().n === 0, 'sources leave the stack with its top');
const restore = execs(method(log, 'private static String undoGroup(Store store, SQLiteDatabase db, JSONObject data)'));
check(restore.length === 2, 'restore SQL extracted');
db.prepare(restore[0]).run(10);
db.prepare(restore[0]).run(10);
db.prepare(restore[1]).run(2, 10);
db.prepare(restore[1]).run(3, 10);
check(db.prepare('SELECT count(*) n FROM note_members WHERE top=10').get().n === 2, 'old stack rebuilt');
check(db.prepare('SELECT count(*) n FROM note_groups').get().n === 1, 'old top restored once');

// Миграция в Store: свежая установка и обновление 13 → 14.
check(method(store, 'void onCreate(SQLiteDatabase db)').includes('AgentLog.create(db)'), 'fresh install gets the journal');
check(/oldV < 14\)\s*AgentLog\.create\(db\)/.test(method(store, 'void onUpgrade(SQLiteDatabase db, int oldV, int newV)')), 'upgrade 13 -> 14 adds the journal');
check(/super\(context, name, null, 14\)/.test(store), 'database version is 14');
db.close();
console.log(`PASS: ${count} agent log SQL checks (production SQL)`);
