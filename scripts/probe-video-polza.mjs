// Одноразовая проверка: понимает ли Polza.AI ссылку на видео YouTube (модели Gemini).
// Ключ берётся из переменной POLZA_KEY и нигде не печатается. Делает до трёх коротких платных запросов.
const key = (process.env.POLZA_KEY || '').trim().replace(/^["']|["']$/g, '').replace(/^Bearer\s+/i, '');
if (!key) { console.error('Нет POLZA_KEY'); process.exit(2); }
console.log('key length:', key.length, '(без пробелов и кавычек)');
const base = 'https://polza.ai/api/v1/';
const video = process.argv[3] || 'https://www.youtube.com/watch?v=jNQXAC9IVRw'; // 19 секунд
const headers = { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' };
const clip = s => String(s).slice(0, 1800);

const list = await fetch(base + 'models?type=chat', { headers: { Authorization: headers.Authorization }, signal: AbortSignal.timeout(30000) });
console.log('models:', list.status);
const body = await list.json().catch(() => ({}));
const ids = (Array.isArray(body) ? body : body.data || []).map(m => m.id || m).filter(Boolean);
const gemini = ids.filter(id => /gemini/i.test(id));
console.log('gemini models:', gemini.slice(0, 20).join(', ') || '(нет)');
const model = process.argv[2] || gemini.find(id => /flash/i.test(id)) || gemini[0];
if (!model) { console.error('Нет модели Gemini'); process.exit(3); }
console.log('using model:', model, '\nvideo:', video);

const formats = {
  video_url: { type: 'video_url', video_url: { url: video } },
  file: { type: 'file', file: { file_data: video } },
  image_url: { type: 'image_url', image_url: { url: video } },
};
for (const [name, part] of Object.entries(formats)) {
  const r = await fetch(base + 'chat/completions', {
    method: 'POST', headers, signal: AbortSignal.timeout(90000),
    body: JSON.stringify({ model, max_tokens: 900, temperature: 0, messages: [{ role: 'user', content: [{ type: 'text', text: 'Сделай краткую сводку этого видео на русском: название темы и 5-7 пунктов по сути. Если речь идёт о фактах, не выдумывай ничего, чего нет в видео.' }, part] }] }),
  });
  const text = await r.text();
  let j; try { j = JSON.parse(text); } catch { j = null; }
  const answer = j?.choices?.[0]?.message?.content;
  console.log(`[${name}] status ${r.status}`, answer ? 'ANSWER: ' + clip(answer) : 'ERROR: ' + clip(text));
  if (j?.usage) console.log('  usage:', JSON.stringify({ total: j.usage.total_tokens, cost_rub: j.usage.cost_rub }));
  if (answer) break;
}
