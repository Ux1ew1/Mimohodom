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
const storeSource=fs.readFileSync('app/src/main/java/app/fastnotes/Store.java','utf8');
const pendingSql=[...storeSource.matchAll(/\.rawQuery\(\s*((?:"(?:\\.|[^"\\])*"\s*\+?\s*)+),/g)]
  .map(x=>[...x[1].matchAll(/"(?:\\.|[^"\\])*"/g)].map(s=>JSON.parse(s[0])).join(''))
  .find(sql=>sql.startsWith('SELECT * FROM notes WHERE ai_checked<>updated'));
check(!!pendingSql,'Production pending analysis SQL extracted');
db.exec('ALTER TABLE notes ADD COLUMN ai_checked INTEGER DEFAULT 0');
db.exec('ALTER TABLE notes ADD COLUMN updated INTEGER DEFAULT 1');
db.exec('ALTER TABLE notes ADD COLUMN created INTEGER DEFAULT 1');
db.exec('ALTER TABLE notes ADD COLUMN trashed INTEGER NOT NULL DEFAULT 0');
for(let id=45;id<=48;id++) db.prepare('INSERT INTO notes(id,title,body,created) VALUES(?,?,?,100)').run(id,'Авто','Текст');
db.exec('INSERT INTO note_groups(top) VALUES(47)');
db.exec('INSERT INTO note_members(note,top) VALUES(45,47),(46,47)');
let candidates=db.prepare(pendingSql).all().map(n=>n.id);
check(candidates.includes(48) && !candidates.some(id=>[45,46,47].includes(id)),'Standalone note eligible, whole stack excluded');
db.exec('UPDATE notes SET trashed=5 WHERE id=48');
check(!db.prepare(pendingSql).all().some(n=>n.id===48),'Note in trash is not analysed');
db.exec('UPDATE notes SET trashed=0 WHERE id=48');
// ManagerStore.topMap/membersMap (список читает стопки одним проходом) совпадают с top()/members() по заметке.
const topOne=id=>{const r=db.prepare('SELECT top FROM note_groups WHERE top=? UNION SELECT top FROM note_members WHERE note=?').get(id,id);return r?r.top:0;};
const topMap=new Map();
for(const r of db.prepare('SELECT note, top FROM note_members').all()) topMap.set(r.note,r.top);
for(const r of db.prepare('SELECT top FROM note_groups').all()) topMap.set(r.top,r.top);
check([45,46,47,48].every(id=>(topMap.get(id)||0)===topOne(id)),'topMap matches per-note top()');
const membersMap=new Map();
for(const r of db.prepare('SELECT top, note FROM note_members ORDER BY top, note').all()){if(!membersMap.has(r.top))membersMap.set(r.top,[]);membersMap.get(r.top).push(r.note);}
check(JSON.stringify(membersMap.get(47))===JSON.stringify(db.prepare('SELECT note FROM note_members WHERE top=? ORDER BY note').all(47).map(r=>r.note)) && !membersMap.has(48),'membersMap matches per-top members()');
db.exec('UPDATE note_groups SET expanded=1 WHERE top=47');
db.exec('UPDATE notes SET updated=2 WHERE id IN (45,47)');
candidates=db.prepare(pendingSql).all().map(n=>n.id);
check(!candidates.some(id=>[45,46,47].includes(id)),'Expanded and edited stack remains excluded');
db.exec('DELETE FROM notes WHERE id=47');
candidates=db.prepare(pendingSql).all().map(n=>n.id);
check(candidates.includes(45) && candidates.includes(46),'Deleting top makes released standalone notes eligible');
// Экран корзины (Store.trashed): исходные колоды, удалённой целиком, не показываются отдельно.
const trashSql=[...storeSource.matchAll(/\.rawQuery\(\s*((?:"(?:\\.|[^"\\])*"\s*\+?\s*)+),/g)]
  .map(x=>[...x[1].matchAll(/"(?:\\.|[^"\\])*"/g)].map(s=>JSON.parse(s[0])).join(''))
  .find(sql=>sql.startsWith('SELECT * FROM notes WHERE trashed>0'));
check(!!trashSql,'Production trash list SQL extracted');
for(let id=60;id<=63;id++) db.prepare('INSERT INTO notes(id,title,body,created,trashed) VALUES(?,?,?,100,?)').run(id,'Т','т',id===63?0:500);
db.exec('INSERT INTO note_groups(top) VALUES(60)');
db.exec('INSERT INTO note_members(note,top) VALUES(61,60)');
check(db.prepare(trashSql).all().map(n=>n.id).sort().join()==='60,62','Trash lists the stack top and loose notes, not members with it');
db.exec('UPDATE notes SET trashed=0 WHERE id=60');
check(db.prepare(trashSql).all().map(n=>n.id).sort().join()==='61,62','Member of a restored top is listed on its own');
// Этап 5 (SQLite 17): класс заметки в manager_review, чаты помощника и чат по заметке.
{
  const literalSql = body => [...body.matchAll(/"(?:\\.|[^"\\])*"/g)].map(s => JSON.parse(s[0])).join('');
  const queryOf = (src, prefix) => {
    const found = [...src.matchAll(/(?:rawQuery|execSQL)\(\s*((?:"(?:\\.|[^"\\])*"\s*\+?\s*)+)[,)]/g)]
      .map(x => literalSql(x[1])).find(sql => sql.startsWith(prefix));
    if (!found) throw Error('SQL missing: ' + prefix);
    return found;
  };
  const alter = queryOf(storeSource, 'ALTER TABLE manager_review ADD COLUMN kind');
  db.exec(alter);
  db.exec("INSERT INTO notes(id,title,body) VALUES(70,'Выезд','x'),(71,'Код','1234')");
  db.exec("INSERT INTO manager_review(note,revision,attempted,failed) VALUES(70,'r',1,0),(71,'r',1,0)");
  check(db.prepare('SELECT kind FROM manager_review WHERE note=70').get().kind === '', 'Old reviews have no kind');
  db.prepare(queryOf(source, 'UPDATE manager_review SET kind=? WHERE note=?')).run('complex', 70);
  // claim обновляет строку, а не заменяет её: класс сохраняется до новой оценки.
  db.prepare('UPDATE manager_review SET revision=?,attempted=?,failed=? WHERE note=?').run('r2', 2, 1, 70);
  check(db.prepare('SELECT kind FROM manager_review WHERE note=70').get().kind === 'complex', 'Claim keeps the kind');
  check(!/CONFLICT_REPLACE/.test(source.slice(source.indexOf('public long claim'), source.indexOf('public List<Set<Long>> knownGroups()'))), 'Claim no longer replaces review rows');
  const kinds = db.prepare(queryOf(source, 'SELECT note,kind FROM manager_review WHERE kind')).all();
  check(kinds.length === 1 && kinds[0].note === 70, 'Only classified notes listed');
  db.exec("CREATE TABLE chats(id INTEGER PRIMARY KEY,title TEXT,created INTEGER,note INTEGER NOT NULL DEFAULT 0,origin TEXT NOT NULL DEFAULT 'user')");
  db.exec("INSERT INTO chats(id,title,created,note,origin) VALUES(1,'Как дела: Выезд',1000,70,'agent'),(2,'План: Выезд',1100,70,'note'),(3,'Вопрос',2000,0,'agent'),(4,'Свой',3000,0,'user')");
  check(db.prepare(queryOf(storeSource, 'SELECT id FROM chats WHERE note=?')).get(70).id === 2, 'Note chat skips the progress chat');
  db.exec('DELETE FROM chats WHERE id=2');
  check(!db.prepare(queryOf(storeSource, 'SELECT id FROM chats WHERE note=?')).get(70), 'Progress chat is never the note chat');
  const today = queryOf(storeSource, "SELECT count(*) FROM chats WHERE origin='agent'");
  check(db.prepare(today).get(1500)['count(*)'] === 1 && db.prepare(today).get(0)['count(*)'] === 2, 'Agent chats counted since day start');
  check(/oldV < 17\)\s*addTaskKinds\(db\)/.test(storeSource) && /addTaskKinds\(db\);\s*addSemanticIndex\(db\);\s*\}/.test(storeSource), 'Upgrade 16 -> 17 and fresh install add kinds');
}
db.close();
console.log(`PASS: ${checks} manager SQLite checks (production migration SQL)`);
