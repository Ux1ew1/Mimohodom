// Платная проверка общей проверки заметок на настоящей модели Polza.AI (этап 5: поле kinds и
// вопросы по классу). Один запрос, обычно 1–3 ₽. Ключ — из POLZA_KEY, нигде не печатается и не пишется.
// Запуск через scripts/probe-review-polza.ps1 <модель>.
import fs from 'node:fs';

const [payloadFile, responseFile] = process.argv.slice(2);
const key = (process.env.POLZA_KEY || '').trim().replace(/^["']|["']$/g, '').replace(/^Bearer\s+/i, '');
if (!key) { console.error('Нет POLZA_KEY'); process.exit(2); }
const payload = JSON.parse(fs.readFileSync(payloadFile, 'utf8'));
const started = Date.now();
const res = await fetch('https://polza.ai/api/v1/chat/completions', {
  method: 'POST',
  headers: { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' },
  body: JSON.stringify(payload),
  signal: AbortSignal.timeout(180000),
});
const body = await res.text();
if (!res.ok) { console.error('HTTP ' + res.status + ': ' + body.slice(0, 300)); process.exit(1); }
fs.writeFileSync(responseFile, body);
const json = JSON.parse(body);
const choice = json.choices?.[0] || {};
const usage = json.usage || {};
console.log(`model=${payload.model} finish_reason=${choice.finish_reason} time=${((Date.now() - started) / 1000).toFixed(1)}s`);
console.log(`completion_tokens=${usage.completion_tokens} из max_tokens=${payload.max_tokens} · prompt_tokens=${usage.prompt_tokens} · cost_rub=${usage.cost_rub ?? '?'}`);
