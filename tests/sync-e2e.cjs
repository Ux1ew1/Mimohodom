// Сквозная проверка: Rust-компьютер (headless) <-> Java-клиент (SyncE2ETest).
// Запуск: node tests/sync-e2e.cjs <headless.exe> <java.exe> <classpath>
const { spawn } = require('node:child_process');
const readline = require('node:readline');
const path = require('node:path');
const os = require('node:os');

const [headlessExe, javaExe, classpath] = process.argv.slice(2);
if (!headlessExe || !javaExe || !classpath) throw Error('usage: sync-e2e.cjs <headless> <java> <classpath>');

function lines(child, tag, handler) {
  readline.createInterface({ input: child.stdout }).on('line', l => { console.log(`[${tag}] ${l}`); handler(l); });
  readline.createInterface({ input: child.stderr }).on('line', l => console.log(`[${tag}!] ${l}`));
}
function fail(message) { console.error('FAIL: ' + message); process.exitCode = 1; for (const c of [pc, phone]) try { c.kill(); } catch {} }

const dataDir = path.join(os.tmpdir(), 'mimo-e2e-headless-' + process.pid);
const pc = spawn(headlessExe, [dataDir], { stdio: ['pipe', 'pipe', 'pipe'] });
let phone = null, dump = null, javaDone = false, ready = false, uri = '';
const waiters = [];
function waitFor(test) { return new Promise((res, rej) => { waiters.push([test, res]); setTimeout(() => rej(Error('таймаут ожидания')), 60000); }); }
function feed(line) { for (const w of [...waiters]) if (w[0](line)) { waiters.splice(waiters.indexOf(w), 1); w[1](line); } }

lines(pc, 'pc', l => {
  if (l.startsWith('PAIR ')) uri = l.slice(5);
  if (l === 'READY') ready = true;
  if (l.startsWith('DUMP ')) dump = JSON.parse(l.slice(5));
  feed(l);
});

(async () => {
  await waitFor(l => l === 'READY').catch(e => { if (!ready) throw e; });
  phone = spawn(javaExe, ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', classpath, 'app.fastnotes.SyncE2ETest', uri], { stdio: ['pipe', 'pipe', 'pipe'] });
  let javaOut = [];
  lines(phone, 'phone', l => { javaOut.push(l); feed(l); });
  phone.on('exit', code => { javaDone = true; feed('__EXIT__' + code); });
  await waitFor(l => l === 'PHASE1');
  pc.stdin.write('ADD Новая с ПК|тело с компьютера\n');
  await waitFor(l => l === 'ADDED');
  phone.stdin.write('GO\n');
  const exit = await waitFor(l => l.startsWith('__EXIT__'));
  if (exit !== '__EXIT__0') return fail('Java-клиент завершился с кодом ' + exit.slice(8));
  if (!javaOut.includes('E2E OK')) return fail('Java-клиент не дошёл до конца');
  pc.stdin.write('DUMP\n');
  await waitFor(l => l.startsWith('DUMP '));
  const titles = dump.notes.map(n => n.title);
  const need = (ok, msg) => { if (!ok) throw Error(msg); console.log('  ok (pc): ' + msg); };
  need(!titles.includes('Отчёт'), 'удаление с телефона дошло до компьютера');
  need(titles.includes('С телефона'), 'новая заметка телефона на компьютере');
  need(dump.notes.find(n => n.title === 'Купить молоко и хлеб').body === 'Купить молоко, хлеб и сыр', 'правка телефона применена');
  need(titles.includes('Новая с ПК'), 'заметка компьютера цела');
  need(dump.files.includes('phone-img.jpg'), 'картинка телефона загружена на компьютер');
  const phoneChat = dump.chats.find(c => c.title === 'С телефона');
  need(phoneChat && phoneChat.messages.length === 2, 'чат телефона дошёл до компьютера');
  const milk = dump.notes.find(n => n.title === 'Купить молоко и хлеб');
  const phoneAnswer = phoneChat.messages.find(m => m.role === 'assistant');
  need(phoneAnswer.body.startsWith('Вот [[note:' + milk.id + ']]'), 'ссылка на заметку переведена в номер компьютера');
  need(phoneAnswer.sources.length === 1 && phoneAnswer.sources[0].id === milk.id, 'источник переведён в номер компьютера');
  need(dump.files.includes('phone-chat.jpg'), 'картинка чата телефона загружена на компьютер');
  need(dump.chats.some(c => c.title === 'Про отчёт'), 'чат компьютера цел');
  const phoneTop = dump.notes.find(n => n.title === 'С телефона');
  need(dump.stacks.length === 1 && dump.stacks[0].top === phoneTop.uid && dump.stacks[0].members.length === 1 && dump.stacks[0].members[0] === milk.uid, 'стопка телефона заменила стопку компьютера');
  need(dump.notes.find(n => n.title === 'С телефона').images[0] === 'phone-img.jpg', 'ссылка на картинку');
  pc.stdin.write('QUIT\n');
  console.log('E2E driver OK');
})().catch(e => fail(e.message));
