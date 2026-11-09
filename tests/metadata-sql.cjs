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
function statements(body) {
  return [...body.matchAll(/db\.execSQL\(\s*((?:"(?:\\.|[^"\\])*"\s*(?:\+\s*)?)+)\s*\);/g)]
    .map(match => [...match[1].matchAll(/"(?:\\.|[^"\\])*"/g)].map(x => JSON.parse(x[0])).join(''));
}
let count = 0;
function check(value, name) { if (!value) throw Error(name); count++; }
const db = new DatabaseSync(':memory:');
db.exec('PRAGMA foreign_keys=ON');
const base = statements(method('void onCreate(SQLiteDatabase db)'));
check(base.length === 8, 'Read actual pre-metadata creation SQL');
for (const sql of base) {
  if (sql.startsWith('ALTER TABLE notes ADD COLUMN image')) for (const assistant of statements(method('void addAssistantColumns(SQLiteDatabase db)'))) db.exec(assistant);
  db.exec(sql);
}
db.prepare('INSERT INTO notes(title,body,created,updated,ai_checked) VALUES(?,?,?,?,?)').run('Семейный рецепт', 'Мука и молоко', 1000, 2000, 2000);
const migration = statements(method('void addMetadata(SQLiteDatabase db)'));
check(migration.length >= 10, 'Read actual metadata migration SQL');
db.exec(migration[0]); db.exec(migration[1]);
// Java backfill is covered separately by MetadataTest; seed its output before the actual index DDL.
db.prepare('UPDATE notes SET metadata=?,search_text=? WHERE id=1').run(JSON.stringify({version:1,title:'Семейный рецепт',description:'Мука и молоко',created_at:1000,updated_at:2000}), 'Семейный рецепт Мука молоко');
for (const sql of migration.slice(2)) db.exec(sql);
let old = db.prepare('SELECT * FROM notes WHERE id=1').get();
check(old.created === 1000 && old.updated === 2000 && old.title === 'Семейный рецепт', 'Migration preserves note and timestamps');
check(old.ai_checked === 0, 'Old successful revisions eligible for first AI metadata pass');
const query = [...method('List<Note> searchNotes(String query)').matchAll(/rawQuery\(\s*((?:"(?:\\.|[^"\\])*"\s*(?:\+\s*)?)+)\s*,/g)]
  .map(match => [...match[1].matchAll(/"(?:\\.|[^"\\])*"/g)].map(x => JSON.parse(x[0])).join(''))
  .find(sql => sql.startsWith('SELECT n.* FROM notes_fts'));
check(Boolean(query), 'Use production FTS lookup SQL');
const search = term => db.prepare(query).all(term);
check(search('реце*').length === 1, 'Backfilled note indexed');
db.prepare('INSERT INTO notes(title,body,created,updated,metadata,search_text) VALUES(?,?,?,?,?,?)').run('Билеты', 'Купить билеты', 3000, 3000, '{}', 'отпуск каникулы Самара');
check(search('каникул*')[0].title === 'Билеты', 'AI-only synonym found through index');
check(search('самар*').length === 1, 'Named entity prefix found');
check(search('КАНИКУЛ*').length === 1, 'Unicode case folding');
const revision = db.prepare('SELECT revision FROM search_state WHERE id=1').get().revision;
db.prepare('UPDATE notes SET metadata=?,search_text=? WHERE id=2').run('{"ai":{"aliases":["поездка"]}}', 'поездка');
check(search('поезд*').length === 1 && search('каникул*').length === 0, 'Index follows metadata updates');
check(db.prepare('SELECT revision FROM search_state WHERE id=1').get().revision > revision, 'Cache revision changes with metadata');
db.prepare('UPDATE notes SET body=?,updated=?,metadata=?,search_text=? WHERE id=2').run('Новый текст',4000,'{}','новый текст');
check(search('поезд*').length === 0 && search('новы*').length === 1, 'Editing removes stale search hints');
const stale = db.prepare('UPDATE notes SET metadata=? WHERE id=? AND updated=?').run('{"ai":"stale"}',2,3000);
check(stale.changes === 0, 'Revision condition rejects late AI result');
db.prepare('DELETE FROM notes WHERE id=2').run();
check(search('новы*').length === 0, 'Deletion removes FTS entry');
check(db.prepare('SELECT count(*) AS n FROM notes_fts').get().n === 1, 'Index contains one row per remaining note');
db.close();
console.log(`PASS: ${count} SQLite metadata/index checks (production SQL)`);
