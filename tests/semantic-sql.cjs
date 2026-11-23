const fs = require('node:fs');
const { DatabaseSync } = require('node:sqlite');
const assert = require('node:assert/strict');
const store = fs.readFileSync('app/src/main/java/app/fastnotes/Store.java', 'utf8');
const index = fs.readFileSync('app/src/main/java/app/fastnotes/SemanticIndex.java', 'utf8');
let count = 0;
function check(value, message) { assert.ok(value, message); count++; }
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
const literal = body => [...body.matchAll(/"(?:\\.|[^"\\])*"/g)].map(s => JSON.parse(s[0])).join('');
const execs = body => [...body.matchAll(/execSQL\(\s*((?:"(?:\\.|[^"\\])*"\s*\+?\s*)+)[,)]/g)].map(x => literal(x[1]));
const migration = method(store, 'private void addSemanticIndex');
const db = new DatabaseSync(':memory:');
db.exec('PRAGMA foreign_keys=ON');
db.exec('CREATE TABLE notes(id INTEGER PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL)');
for (const sql of execs(migration)) db.exec(sql);
db.exec("INSERT INTO notes VALUES(1,'Поездка','К морю'),(2,'Ремонт','Плитка')");
db.prepare('INSERT INTO note_embeddings VALUES(?,?,?)').run(1, 'old', Buffer.alloc(1024));
check(db.prepare('SELECT count(*) n FROM note_embeddings').get().n === 1, 'migration creates local vector table');
db.prepare('INSERT OR REPLACE INTO note_embeddings(note_id,fingerprint,vector) VALUES(?,?,?)').run(1, 'new', Buffer.alloc(1024));
check(db.prepare('SELECT fingerprint FROM note_embeddings WHERE note_id=1').get().fingerprint === 'new', 'changed fingerprint replaces one vector');
check(db.prepare('SELECT count(*) n FROM note_embeddings').get().n === 1, 'unchanged notes are not duplicated');
db.prepare('DELETE FROM notes WHERE id=1').run();
check(db.prepare('SELECT count(*) n FROM note_embeddings').get().n === 0, 'deleting note cascades to vector');
check(store.includes('super(context, name, null, 21)') && store.includes('if (oldV < 18) addSemanticIndex(db)'), 'SQLite 21 carries the 18 upgrade');
check(index.includes('!old.fingerprint.equals(fingerprint)'), 'index recomputes changed content');
check(index.indexOf('if (!changed.isEmpty())') < index.indexOf('db.beginTransaction()'), 'unchanged archive opens no write transaction');
check(index.includes('insertWithOnConflict'), 'only stale vectors upserted');
check(!index.includes('Polza.') && !index.includes('http'), 'index has no cloud dependency');
db.close();
console.log(`PASS: ${count} semantic SQL checks (real SQLite + production SQL)`);
