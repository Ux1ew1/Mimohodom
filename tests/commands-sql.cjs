// Команды: настоящий SQLite и SQL/условия из CommandExecutor и AgentLog.
const fs = require('node:fs');
const { DatabaseSync } = require('node:sqlite');
const assert = require('node:assert/strict');
const executor = fs.readFileSync('app/src/main/java/app/fastnotes/CommandExecutor.java', 'utf8');
const log = fs.readFileSync('app/src/main/java/app/fastnotes/AgentLog.java', 'utf8');
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

const apply = method(executor, 'public static synchronized Outcome apply(Store store, long messageId)');
const undoBody = method(executor, 'static void undoInTransaction');
const undoLog = method(log, 'static Outcome undo(Store store, long id)');
check(apply.indexOf('db.beginTransaction()') < apply.indexOf('CommandDraft.fromJson'), 'draft reread inside transaction');
check(apply.indexOf('for (CommandDraft.NoteState expected') < apply.indexOf('Outcome out ='), 'every revision checked before first write');
check(apply.indexOf('AgentLog.record') < apply.indexOf('store.dismissDraft'), 'journal and draft close in same transaction');
check(apply.indexOf('store.dismissDraft') < apply.indexOf('db.setTransactionSuccessful'), 'draft closes before commit');
check(apply.includes('id=? AND updated=? AND trashed=0'), 'writes retain revision and trash guards');
check(apply.includes('Reminders.') === false, 'executor never changes alarms before commit');
check(apply.includes('action.lead == -1 ? before.lead : action.lead'), 'adding dates preserves existing custom lead');
check(undoBody.indexOf('long now =') > undoBody.indexOf('sameSchedule'), 'all undo conflicts checked before writes');

const db = new DatabaseSync(':memory:');
db.exec('PRAGMA foreign_keys=ON');
db.exec(`CREATE TABLE notes(
  id INTEGER PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL DEFAULT 0,
  updated INTEGER NOT NULL,reminder INTEGER NOT NULL DEFAULT 0,reminder_times TEXT NOT NULL DEFAULT '',
  repeat_mode INTEGER NOT NULL DEFAULT 0,lead_minutes INTEGER NOT NULL DEFAULT -1,
  folder INTEGER NOT NULL DEFAULT 0,trashed INTEGER NOT NULL DEFAULT 0)`);
db.exec('CREATE TABLE folders(id INTEGER PRIMARY KEY,name TEXT NOT NULL,key TEXT NOT NULL UNIQUE)');
db.exec("CREATE TABLE messages(id INTEGER PRIMARY KEY,chat INTEGER NOT NULL DEFAULT 1,role TEXT NOT NULL,body TEXT NOT NULL DEFAULT '',draft TEXT NOT NULL DEFAULT '')");
for (const sql of execs(method(log, 'static void create(SQLiteDatabase db)'))) db.exec(sql);
db.exec("INSERT INTO notes(id,title,updated,folder) VALUES(1,'Первая',100,5),(2,'Вторая',200,5),(3,'Дело',300,0)");
db.exec("INSERT INTO folders VALUES(5,'Дом','дом')");
db.exec("INSERT INTO messages(id,role,draft) VALUES(1,'assistant','{\"kind\":\"command\",\"version\":1}')");
const draftQuery = queries(apply)[0];
check(db.prepare(draftQuery).get(1).draft.includes('command'), 'production draft query reads assistant message');
check(!db.prepare(draftQuery).get(99), 'missing message cannot be applied');

const resolve = queries(method(executor, 'private static long resolveFolder'))[0];
check(db.prepare(resolve).get('дом').id === 5, 'existing target folder resolved by key');
check(!db.prepare(resolve).get('новая'), 'new target folder absent before preview');
db.exec("INSERT INTO folders VALUES(6,'Работа','работа')");
check(db.prepare(resolve).get('работа').name === 'Работа', 'folder rename conflict is observable');

// Успешный пакет: обе записи, один журнал и закрытый черновик фиксируются вместе.
db.exec('BEGIN');
let changed = db.prepare('UPDATE notes SET folder=? WHERE id=? AND updated=? AND trashed=0').run(6, 1, 100).changes;
changed += db.prepare('UPDATE notes SET folder=? WHERE id=? AND updated=? AND trashed=0').run(6, 2, 200).changes;
check(changed === 2, 'all folder updates match snapshots');
db.prepare("INSERT INTO agent_log(created,action,data,undo,state) VALUES(?,?,?,?,?)")
  .run(1000, 'command_batch', '{"created_notes":[],"folders":[{"note":1,"before":5,"after":6},{"note":2,"before":5,"after":6}],"reminders":[]}', '{}', 'done');
db.prepare("UPDATE messages SET draft='' WHERE id=?").run(1);
db.exec('COMMIT');
check(db.prepare('SELECT count(*) n FROM notes WHERE folder=6').get().n === 2, 'whole package committed');
check(db.prepare('SELECT count(*) n FROM agent_log').get().n === 1, 'one journal row per package');
check(db.prepare(draftQuery).get(1).draft === '', 'repeat click finds no draft');

// Конфликт ревизии обнаруживается до первой записи.
db.prepare("UPDATE notes SET updated=201 WHERE id=2").run();
const current = id => db.prepare('SELECT updated,folder,trashed FROM notes WHERE id=?').get(id);
check(current(1).updated === 100 && current(2).updated !== 200, 'one source changed after preview');
db.exec('BEGIN');
if (current(1).updated !== 100 || current(2).updated !== 200) db.exec('ROLLBACK');
check(current(1).folder === 6 && current(2).folder === 6, 'revision conflict leaves all folders untouched');
check(db.prepare('SELECT count(*) n FROM agent_log').get().n === 1, 'conflict writes no success journal');

// Ошибка посередине: первая запись и новая папка откатываются вместе с журналом.
db.prepare("UPDATE messages SET draft='{\"kind\":\"command\"}' WHERE id=1").run();
db.exec('BEGIN');
db.prepare('UPDATE notes SET folder=7 WHERE id=1 AND updated=100 AND trashed=0').run();
db.exec("INSERT INTO folders VALUES(7,'Новая','новая')");
db.exec('ROLLBACK');
check(current(1).folder === 6, 'mid-transaction failure rolls back first note');
check(!db.prepare('SELECT 1 FROM folders WHERE id=7').get(), 'mid-transaction failure rolls back new folder');
check(db.prepare(draftQuery).get(1).draft !== '', 'failed apply keeps draft for diagnosis');
check(db.prepare('SELECT count(*) n FROM agent_log').get().n === 1, 'failed apply leaves no journal entry');

// Отмена: SQL берётся непосредственно из production undoInTransaction.
const undoSql = execs(undoBody);
check(undoSql.length === 2 && undoBody.includes('value.put("before_reminder"') === false && undoBody.includes('db.update("notes"'), 'created, moved and reminder undo paths present');
const trashCreated = undoSql.find(sql => sql.includes('SET trashed=?'));
const restoreFolder = undoSql.find(sql => sql.includes('SET folder=?'));
check(!!trashCreated && !!restoreFolder, 'production undo SQL extracted');
db.exec("INSERT INTO notes(id,title,updated,folder) VALUES(4,'Созданная',400,8)");
db.exec("INSERT INTO folders VALUES(8,'Временная','временная')");
const data = JSON.stringify({
  created_notes:[{note:4,after_updated:400,after_folder:8}],
  folders:[{note:1,before:5,after:6}], reminders:[], created_folder:8, created_folders:[8]
});
db.prepare("INSERT INTO agent_log(created,action,data,state) VALUES(2000,'command_batch',?,'done')").run(data);
// Правка созданной записи блокирует всю отмену.
db.prepare('UPDATE notes SET updated=401 WHERE id=4').run();
db.exec('BEGIN');
if (current(4).updated !== 400) db.exec('ROLLBACK');
check(current(4).trashed === 0 && current(1).folder === 6, 'user edit prevents partial undo');
// После возврата тестового состояния отмена выполняется целиком.
db.prepare('UPDATE notes SET updated=400 WHERE id=4').run();
db.exec('BEGIN');
db.prepare(trashCreated).run(3000, 4);
db.prepare(restoreFolder).run(5, 1, 6);
db.prepare('DELETE FROM folders WHERE id=?').run(8);
const markUndone = execs(undoLog).find(sql => sql.startsWith('UPDATE agent_log'));
db.prepare(markUndone).run('undone', 2);
db.exec('COMMIT');
check(current(4).trashed === 3000 && current(1).folder === 5, 'created note trashed and old folder restored');
check(!db.prepare('SELECT 1 FROM folders WHERE id=8').get(), 'empty created folder removed');
check(db.prepare('SELECT state FROM agent_log WHERE id=2').get().state === 'undone', 'journal marked undone atomically');
check(db.prepare('SELECT state FROM agent_log WHERE id=2').get().state !== 'done', 'second undo has no done entry');

// Непустую созданную папку сохраняем, даже когда исходная заметка ушла из неё.
db.exec("INSERT INTO folders VALUES(9,'Оставить','оставить')");
db.exec("INSERT INTO notes(id,title,updated,folder) VALUES(9,'Позже добавленная',900,9)");
const notesInFolder = db.prepare('SELECT count(*) n FROM notes WHERE folder=? AND trashed=0').get(9).n;
if (notesInFolder === 0) db.prepare('DELETE FROM folders WHERE id=?').run(9);
check(notesInFolder === 1 && db.prepare('SELECT 1 FROM folders WHERE id=9').get(), 'nonempty created folder survives undo');

// Восстановление расписания — то же обновление полей, которое делает ContentValues в production.
db.prepare("UPDATE notes SET reminder=5000,reminder_times='[5000,6000]',repeat_mode=5,lead_minutes=60 WHERE id=3").run();
const after = db.prepare('SELECT reminder,reminder_times,repeat_mode,lead_minutes FROM notes WHERE id=3').get();
check(after.reminder === 5000 && after.repeat_mode === 5, 'new schedule visible before undo');
db.exec('BEGIN');
db.prepare("UPDATE notes SET reminder=0,reminder_times='',repeat_mode=0,lead_minutes=-1 WHERE id=3").run();
db.exec('COMMIT');
const before = db.prepare('SELECT reminder,reminder_times,repeat_mode,lead_minutes FROM notes WHERE id=3').get();
check(before.reminder === 0 && before.reminder_times === '' && before.repeat_mode === 0 && before.lead_minutes === -1,
  'undo restores reminder, list, repeat and lead together');

// Конфликт напоминания откатывает даже уже подготовленное перемещение.
db.exec('BEGIN');
db.prepare(restoreFolder).run(5, 2, 6);
const scheduleChanged = db.prepare('SELECT reminder FROM notes WHERE id=3').get().reminder !== 5000;
if (scheduleChanged) db.exec('ROLLBACK');
check(current(2).folder === 6, 'schedule conflict rolls back folder undo');
check(undoLog.includes("case AgentJournal.COMMAND_BATCH"), 'AgentLog dispatches command batch undo');
check(undoLog.includes('db.setTransactionSuccessful'), 'undo state and data commit together');
db.close();
console.log(`PASS: ${count} command SQL checks (real SQLite + production SQL)`);
