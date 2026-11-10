const fs = require('node:fs');
const { DatabaseSync } = require('node:sqlite');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/java/app/fastnotes/ManagerStore.java','utf8');
const ddl = [...source.matchAll(/db\.execSQL\(\s*((?:"(?:\\.|[^"\\])*"\s*\+?\s*)+)\);/g)]
  .map(x=>[...x[1].matchAll(/"(?:\\.|[^"\\])*"/g)].map(s=>JSON.parse(s[0])).join(''))
  .filter(sql=>sql.startsWith('CREATE TABLE'));
const db = new DatabaseSync(':memory:');
db.exec('PRAGMA foreign_keys=ON');
db.exec('CREATE TABLE notes(id INTEGER PRIMARY KEY,title TEXT,body TEXT,reminder INTEGER,image TEXT)');
db.exec("INSERT INTO notes VALUES(1,'Рецепт','Мука',12345,'image.jpg'),(2,'Рецепт','Молоко',0,''),(3,'Итог','Мука и молоко',0,'')");
let checks=0;
function check(value, message){assert.ok(value,message);checks++;}
check(ddl.length===5,'All production migration tables extracted');
for(const sql of ddl)db.exec(sql);
check(db.prepare('SELECT body FROM notes WHERE id=1').get().body==='Мука','Migration preserves text');
check(db.prepare('SELECT reminder,image FROM notes WHERE id=1').get().image==='image.jpg','Migration preserves attachment');
db.exec('INSERT INTO note_groups(top) VALUES(3)');
db.exec('INSERT INTO note_members(note,top) VALUES(1,3),(2,3)');
check(db.prepare('SELECT count(*) n FROM note_members WHERE top=3').get().n===2,'Two sources grouped');
db.exec('UPDATE note_groups SET expanded=1-expanded WHERE top=3');
check(db.prepare('SELECT expanded FROM note_groups').get().expanded===1,'Expand persisted');
db.exec('UPDATE note_groups SET expanded=1-expanded WHERE top=3');
check(db.prepare('SELECT expanded FROM note_groups').get().expanded===0,'Collapse persisted');
for(let i=4;i<=11;i++) {
  db.prepare('INSERT INTO notes(id,title,body) VALUES(?,?,?)').run(i,'Исходник','Текст');
  db.prepare('INSERT INTO note_members(note,top) VALUES(?,3)').run(i);
}
check(db.prepare('SELECT count(*) n FROM note_members WHERE top=3').get().n===10,'Ten sources supported independently of visual limit');
db.exec('DELETE FROM notes WHERE id=2');
check(db.prepare('SELECT count(*) n FROM note_members').get().n===9,'Deleting source removes only its membership');
check(db.prepare('SELECT count(*) n FROM note_groups').get().n===1,'Deleting source retains group');
db.exec('DELETE FROM notes WHERE id=3');
check(db.prepare('SELECT count(*) n FROM note_groups').get().n===0,'Deleting top removes group');
check(db.prepare('SELECT count(*) n FROM note_members').get().n===0,'Deleting top releases originals');
check(db.prepare('SELECT reminder,image FROM notes WHERE id=1').get().reminder===12345,'Original reminder retained');
check(db.prepare('SELECT count(*) n FROM notes').get().n===9,'Originals survive deletion of summary');
db.exec("INSERT INTO manager_review VALUES(1,'revision',123,1)");
db.exec("INSERT INTO manager_runs(started,state) VALUES(123,'uncertain')");
check(db.prepare('SELECT failed FROM manager_review').get().failed===1,'Ambiguous request persisted');
db.exec("INSERT INTO manager_proposals(identity,data,created) VALUES('identity','{}',123)");
db.exec("UPDATE manager_proposals SET state='dismissed' WHERE id=1");
db.exec("INSERT OR IGNORE INTO manager_proposals(identity,data,created) VALUES('identity','{}',124)");
check(db.prepare('SELECT count(*) n FROM manager_proposals').get().n===1,'Dismissed suggestion remains deduplicated');
db.exec('DELETE FROM notes WHERE id=1');
check(db.prepare('SELECT count(*) n FROM manager_review').get().n===0,'Deleted source releases queue entry');
db.exec('BEGIN');
db.exec("INSERT INTO notes VALUES(99,'Итог','Черновик',0,'')");
db.exec('INSERT INTO note_groups(top) VALUES(99)');
db.exec('ROLLBACK');
check(!db.prepare('SELECT id FROM notes WHERE id=99').get(),'Rolled-back acceptance leaves no summary');
check(db.prepare('SELECT count(*) n FROM note_groups').get().n===0,'Rolled-back acceptance leaves no group');
db.close();
console.log(`PASS: ${checks} manager SQLite checks (production migration SQL)`);
