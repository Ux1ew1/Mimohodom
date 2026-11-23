const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');
const dir = path.join(__dirname, '../app/src/main/java/app/fastnotes/');
const store = fs.readFileSync(path.join(dir, 'Store.java'), 'utf8');
const manager = fs.readFileSync(path.join(dir, 'ManagerStore.java'), 'utf8');
const folders = fs.readFileSync(path.join(dir, 'FolderStore.java'), 'utf8');
function method(source, signature) {
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
const literal = /((?:"(?:\\.|[^"\\])*"\s*(?:\+\s*)?)+)/;
function join(group) { return [...group.matchAll(/"(?:\\.|[^"\\])*"/g)].map(x => JSON.parse(x[0])).join(''); }
function statements(body) {
  return [...body.matchAll(new RegExp(String.raw`db\.execSQL\(\s*` + literal.source + String.raw`\s*[,)]`, 'g'))].map(m => join(m[1]));
}
let count = 0;
function check(value, name) { if (!value) throw Error(name); count++; }

// Старая база версии 7: заметки до появления папок.
const db = new DatabaseSync(':memory:');
db.exec('PRAGMA foreign_keys=ON');
const create = statements(method(store, 'void onCreate(SQLiteDatabase db)'));
for (const sql of create) {
  if (sql.startsWith('ALTER TABLE notes ADD COLUMN image')) for (const s of statements(method(store, 'void addAssistantColumns(SQLiteDatabase db)'))) db.exec(s);
  db.exec(sql);
}
for (const s of statements(method(store, 'void addMetadata(SQLiteDatabase db)'))) db.exec(s);
for (const s of statements(method(manager, 'static void create(SQLiteDatabase db)'))) db.exec(s);
for (const s of statements(method(store, 'void addReminderOptions(SQLiteDatabase db)'))) db.exec(s);
const insert = db.prepare('INSERT INTO notes(title,body,created,updated) VALUES(?,?,?,?)');
for (let i = 1; i <= 5; i++) insert.run('Заметка ' + i, 'Текст ' + i, 1000 * i, 2000 * i);
check(!Object.keys(db.prepare('SELECT * FROM notes WHERE id=1').get()).includes('folder'), 'Version 7 notes have no folder column');

// Миграция 7 -> 8 с настоящим SQL из Store.addFolders (повторный вызов безопасен).
const migration = statements(method(store, 'void addFolders(SQLiteDatabase db)'));
check(migration.length === 3, 'Read actual folders migration SQL');
for (const s of migration) db.exec(s);
for (const s of migration.slice(1)) db.exec(s); // повтор без столбца
const columns = Object.keys(db.prepare('SELECT * FROM notes WHERE id=1').get());
check(columns.length === 17 && columns[16] === 'folder', 'Folder is the last column read by Store.read');
const kept = db.prepare('SELECT title,body,created,updated,folder FROM notes WHERE id=3').get();
check(kept.title === 'Заметка 3' && kept.created === 3000 && kept.updated === 6000 && kept.folder === 0, 'Migration keeps notes and puts them in All notes');
check(db.prepare('SELECT count(*) AS n FROM notes_fts').get().n === 5, 'Search index untouched by migration');

// Корзина (SQLite 13): FolderStore считает только заметки вне корзины, поэтому столбец нужен до запросов.
const trashSql = statements(method(store, "void addTrash(SQLiteDatabase db)"));
for (const s of trashSql) db.exec(s);

// Запросы из FolderStore.
const sqlOf = prefix => {
  const found = [...folders.matchAll(new RegExp(literal.source, 'g'))].map(m => join(m[1])).find(s => s.startsWith(prefix));
  if (!found) throw Error('SQL missing: ' + prefix);
  return found;
};
const move = sqlOf('UPDATE notes SET folder=? WHERE id=?');
const unfile = sqlOf('UPDATE notes SET folder=0');
const list = sqlOf('SELECT f.id,f.name,f.auto');
const addFolder = db.prepare('INSERT INTO folders(name,key,created,auto) VALUES(?,?,?,?)');
const work = Number(addFolder.run('Работа', 'работа', 1, 0).lastInsertRowid);
const videos = Number(addFolder.run('Видео', 'видео', 2, 1).lastInsertRowid);
let duplicate = false;
try { addFolder.run('РАБОТА', 'работа', 3, 0); } catch { duplicate = true; }
check(duplicate, 'Folder key is unique');
check(db.prepare(list).all().map(f => f.name).join() === 'Видео,Работа', 'Folders sorted by key');

db.prepare(move).run(work, 1, 1);
db.prepare(move).run(work, 2, 2);
check(db.prepare('SELECT count(*) AS n FROM notes WHERE folder=?').get(work).n === 2, 'Notes move into a folder');
check(db.prepare('SELECT updated FROM notes WHERE id=1').get().updated === 2000, 'Moving does not change updated');
const folderCount = () => Object.values(db.prepare(list).all().find(f => f.id === work))[3];
check(folderCount() === 2, 'Folder note counts');
db.exec('UPDATE notes SET trashed=5 WHERE id=1');
check(folderCount() === 1, 'Notes in trash are not counted in a folder');
db.exec('UPDATE notes SET trashed=0 WHERE id=1');
check(db.prepare('SELECT n.* FROM notes_fts JOIN notes n ON n.id=notes_fts.rowid WHERE notes_fts MATCH ?').all('заметка*').length === 5, 'Search still finds moved notes');

// Стопка: итог переносит участников.
db.prepare('INSERT INTO note_groups(top) VALUES(?)').run(5);
db.prepare('INSERT INTO note_members(note,top) VALUES(?,?)').run(3, 5);
db.prepare('INSERT INTO note_members(note,top) VALUES(?,?)').run(4, 5);
db.prepare(move).run(videos, 5, 5);
check(db.prepare('SELECT count(*) AS n FROM notes WHERE folder=?').get(videos).n === 3, 'Stack top moves with its members');
db.prepare(move).run(work, 3, 3);
check(db.prepare('SELECT folder FROM notes WHERE id=3').get().folder === work && db.prepare('SELECT folder FROM notes WHERE id=5').get().folder === videos, 'A member alone moves alone');

// Удаление папки возвращает заметки, не удаляя их.
db.prepare(unfile).run(work);
db.prepare('DELETE FROM folders WHERE id=?').run(work);
check(db.prepare('SELECT count(*) AS n FROM notes').get().n === 5, 'Deleting a folder keeps every note');
check(db.prepare('SELECT count(*) AS n FROM notes WHERE folder=?').all(work)[0].n === 0, 'No note points at a deleted folder');
check(db.prepare('SELECT folder FROM notes WHERE id=1').get().folder === 0, 'Notes return to All notes');
check(db.prepare('SELECT count(*) AS n FROM notes WHERE folder=?').get(videos).n === 2, 'Other folders are untouched');
// Версия 11: за сколько минут ИИ предлагает напомнить (Store.addSuggestionLead).
const lead = statements(method(store, "void addSuggestionLead(SQLiteDatabase db)"));
check(lead.length === 1 && lead[0].includes("suggestion_lead INTEGER NOT NULL DEFAULT -1"), "Read actual suggestion lead migration SQL");
db.exec(lead[0]);
check(db.prepare("SELECT suggestion_lead FROM notes WHERE id=1").get().suggestion_lead === -1, "Old notes have no suggested lead");
check(method(store, "void onCreate(SQLiteDatabase db)").includes("addSuggestionLead(db)"), "Fresh install also gets the column");
check(/oldV < 11\)\s*addSuggestionLead\(db\)/.test(method(store, "void onUpgrade(SQLiteDatabase db, int oldV, int newV)")), "Upgrade 10 -> 11 adds the column");
// Версия 12: план у заметки (дерево PlanTree) и привязка чата к заметке.
const plans = statements(method(store, "void addPlans(SQLiteDatabase db)"));
check(plans.length === 2, "Read actual plan migration SQL");
for (const sql of plans) db.exec(sql);
check(db.prepare("SELECT plan FROM notes WHERE id=1").get().plan === "", "Old notes have no plan");
check(db.prepare("PRAGMA table_info(chats)").all().some(c => c.name === "note"), "Chats have note column");
check(method(store, "void onCreate(SQLiteDatabase db)").includes("addPlans(db)"), "Fresh install also gets plans");
check(/oldV < 12\)\s*addPlans\(db\)/.test(method(store, "void onUpgrade(SQLiteDatabase db, int oldV, int newV)")), "Upgrade 11 -> 12 adds plans");
// Версия 13: корзина (столбец добавлен выше, перед запросами FolderStore).
check(trashSql.length === 1 && trashSql[0].includes("trashed INTEGER NOT NULL DEFAULT 0"), "Read actual trash migration SQL");
check(db.prepare("SELECT trashed FROM notes WHERE id=1").get().trashed === 0, "Old notes are not in trash");
check(method(store, "void onCreate(SQLiteDatabase db)").includes("addTrash(db)"), "Fresh install also gets trash");
check(/oldV < 13\)\s*addTrash\(db\)/.test(method(store, "void onUpgrade(SQLiteDatabase db, int oldV, int newV)")), "Upgrade 12 -> 13 adds trash");
// Версия 14: журнал агента (AgentLog), проверяется в tests/agent-log-sql.cjs.
// Версия 15: все даты серии «по списку» (триггер обмена — в tests/sync-sql.cjs).
const times = statements(method(store, "void addReminderTimes(SQLiteDatabase db)"));
check(times.length === 1 && times[0].includes("reminder_times TEXT NOT NULL DEFAULT ''"), "Read actual reminder times migration SQL");
db.exec(times[0]);
check(db.prepare("SELECT reminder_times FROM notes WHERE id=1").get().reminder_times === "", "Old notes have no date list");
check(method(store, "void onCreate(SQLiteDatabase db)").includes("addReminderTimes(db)"), "Fresh install also gets date lists");
check(/oldV < 15\)\s*addReminderTimes\(db\)/.test(method(store, "void onUpgrade(SQLiteDatabase db, int oldV, int newV)")), "Upgrade 14 -> 15 adds date lists");
check(/oldV < 15\)\s*addReminderTimes\(db\)/.test(method(store, "void onUpgrade(SQLiteDatabase db, int oldV, int newV)")), "Upgrade 14 -> 15 kept");
// Версия 16: кто начал чат (chats.origin) — триггер и перенос пометок в tests/sync-sql.cjs.
check(method(store, "void onCreate(SQLiteDatabase db)").includes("addChatOrigin(db)"), "Fresh install also gets chat origin");
check(/oldV < 16\)\s*\{\s*addChatOrigin\(db\);\s*moveCheckInMarks\(db\);/.test(method(store, "void onUpgrade(SQLiteDatabase db, int oldV, int newV)")), "Upgrade 15 -> 16 adds chat origin and moves marks");
// Версия 18: локальные векторы в note_embeddings (tests/semantic-sql.cjs).
check(/super\(context, name, null, 18\)/.test(store), "Database version is 18");
db.close();
console.log(`PASS: ${count} SQLite folder checks (production SQL)`);
