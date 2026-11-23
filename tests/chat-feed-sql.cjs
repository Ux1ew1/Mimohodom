'use strict';
// Лента чатов (0.10.22): запрос Store.chatFeed на настоящей SQLite — последнее сообщение,
// время последней активности, число ответов пользователя и порядок «свежие сверху».
const fs = require('node:fs');
const { DatabaseSync } = require('node:sqlite');
const assert = require('node:assert/strict');

const source = fs.readFileSync(__dirname + '/../app/src/main/java/app/fastnotes/Store.java', 'utf8');
const method = source.slice(source.indexOf('public List<ChatFeed.Row> chatFeed()'));
const last = /String last = "([^"]+)";/.exec(method)[1];
const raw = /rawQuery\(\s*([\s\S]*?),\s*null\)\)/.exec(method)[1];
const sql = raw
  .split('+')
  .map((part) => part.trim())
  .map((part) => (part === 'last' ? last : JSON.parse(part)))
  .join('');

const db = new DatabaseSync(':memory:');
db.exec(`CREATE TABLE chats(id INTEGER PRIMARY KEY, title TEXT, created INTEGER, note INTEGER DEFAULT 0, origin TEXT DEFAULT 'user');
CREATE TABLE messages(id INTEGER PRIMARY KEY, chat INTEGER, role TEXT, body TEXT, sources TEXT, created INTEGER, draft TEXT DEFAULT '');`);
db.exec(`INSERT INTO chats VALUES(1,'Старый',100,0,'user'),(2,'Новый без ответа',500,0,'user'),(3,'Помощник',200,7,'agent');
INSERT INTO messages(chat,role,body,sources,created,draft) VALUES
 (1,'user','вопрос','[]',110,''),(1,'assistant','ответ','[]',900,''),
 (3,'assistant','Как продвигается?','[]',300,'{"kind":"progress","note":7}');`);
const rows = db.prepare(sql).all().map((r) => Object.values(r));
assert.deepEqual(rows.map((r) => r[0]), [1, 2, 3], 'order by last activity');
assert.equal(rows[0][5], 'ответ', 'last message body');
assert.equal(rows[0][6], 'assistant', 'last message role');
assert.equal(rows[0][8], 900, 'time of last message');
assert.equal(rows[0][9], 1, 'user messages');
assert.equal(rows[1][5], null, 'chat without messages');
assert.equal(rows[1][8], 500, 'falls back to chat creation');
assert.equal(rows[2][7], '{"kind":"progress","note":7}', 'draft of last message');
assert.equal(rows[2][9], 0, 'no user answers');
console.log('chat feed SQL ok');
