import fs from 'node:fs';

const BASE = 'https://polza.ai/api/v1/';
const BUDGET = 45;
const BASELINE = 'qwen/qwen4-max';
const GEMMA = 'google/gemma-3-27b-it';
const REVISION = 4;
const CANDIDATES = [
  'google/gemini-3.1-flash-lite',
  'google/gemini-3.8-flash',
  'qwen/qwen3.8-flash',
  'xiaomi/mimo-v2.6-flash-mopd',
  'deepseek/deepseek-v4-flash',
  'deepseek/deepseek-chat',
];
const modes = ['chat', 'review'];
const [caseFile, reportFile, ...flags] = process.argv.slice(2);
const dry = flags.includes('--dry');
const stage4 = flags.includes('--stage4');
const resume = flags.find(flag => flag.startsWith('--resume='))?.slice('--resume='.length);
const cases = JSON.parse(fs.readFileSync(caseFile, 'utf8'));
const baseline = JSON.parse(
  fs.readFileSync(new URL('../tests/agent-planning-baseline.json', import.meta.url), 'utf8'),
);
const key = (process.env.POLZA_KEY || '').trim().replace(/^Bearer\s+/i, '');

function payloadFor(fixture, model, variant) {
  const payload = structuredClone(fixture.payload);
  payload.model = model;
  if (variant === 'old') {
    const system = payload.messages[0];
    if (fixture.kind === 'review') {
      const endOfManager = system.content.indexOf('нет — пустой массив.');
      const boundary = system.content.indexOf('\n', endOfManager);
      if (endOfManager < 0 || boundary < 0)
        throw new Error('Не найдена граница исходного промпта проверки');
      const review = system.content.slice(boundary).split('\nДля planning')[0];
      system.content = baseline.manager + review;
      const input = payload.messages[1];
      const rows = JSON.parse(input.content);
      for (const row of rows) {
        delete row.has_plan;
        row.complete = row.text_complete;
        delete row.text_complete;
      }
      input.content = JSON.stringify(rows);
    } else {
      system.content = baseline.chat;
    }
  }
  return payload;
}

function sameIds(actual, expected) {
  return Array.isArray(actual) && actual.length === expected.length
    && expected.every(id => actual.map(Number).includes(id));
}

function matches(pattern, text) {
  return new RegExp(pattern, 'iu').test(text);
}

function flatten(nodes) {
  if (!Array.isArray(nodes)) return [];
  return nodes.flatMap(node => {
    if (typeof node?.text !== 'string' || !node.text.trim()) return [];
    return [node, ...flatten(node.children)];
  });
}

function score(fixture, response, variant) {
  const choice = response.choices?.[0];
  const raw = choice?.message?.content;
  if (choice?.finish_reason === 'length' || typeof raw !== 'string' || !raw.trim())
    return { points: 0, critical: [choice?.finish_reason === 'length' ? 'truncated' : 'empty'] };
  let result;
  try {
    // Allow a surrounding Markdown fence or prose, as the production parsers do.
    result = JSON.parse(raw.slice(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
    if (!result || typeof result !== 'object' || Array.isArray(result))
      throw new Error('expected an object');
  } catch {
    return { points: 0, critical: ['invalid_json'] };
  }
  const critical = [];
  const quality = [];
  const exp = fixture.expected;
  const question = typeof result.question === 'string' && result.question.trim();
  const nodes = flatten(result.plan?.nodes);
  const actions = Array.isArray(result.actions) ? result.actions : [];
  const hasDraft = !!(result.plan || result.collection || result.edit || actions.length);
  if (fixture.kind === 'review') {
    const reasons = Object.entries(result.planning || {});
    const sources = JSON.parse(fixture.payload.messages[1].content);
    const normalize = text => text.toLowerCase().replaceAll('ё', 'е').trim().replace(/\s+/g, ' ');
    const valid = ([id, value]) => {
      if (variant !== 'new') return typeof value === 'string' && value.trim();
      const kind = result.kinds?.[id];
      const count = value?.mode === 'plan' && kind === 'complex' ? 1
        : value?.mode === 'checklist' && (kind === 'complex' || kind === 'event') ? 2 : 0;
      if (!count || typeof value.reason !== 'string' || !value.reason.trim()
          || !Array.isArray(value.evidence) || value.evidence.length !== count
          || !value.evidence.every(quote => typeof quote === 'string' && normalize(quote).length >= 4))
        return false;
      const row = sources.find(note => note.id === Number(id));
      if (!row) return false;
      const source = normalize(row.title) + '\n' + normalize(row.text);
      const [first, second] = value.evidence.map(normalize);
      if (!source.includes(first)) return false;
      if (count === 1) return true;
      return source.includes(second) && !first.includes(second) && !second.includes(first)
        && (source.indexOf(first) + first.length <= source.lastIndexOf(second)
          || source.indexOf(second) + second.length <= source.lastIndexOf(first));
    };
    const planned = reasons.filter(valid)
      .map(([id]) => Number(id))
      .filter(id => variant !== 'new' || !fixture.planned.includes(id));
    if (!sameIds(planned, exp.planning)) critical.push('wrong_planning_ids');
    for (const [id, mode] of Object.entries(exp.planModes || {}))
      if (result.planning?.[id]?.mode !== mode) critical.push(`wrong_plan_mode:${id}`);
    if (!sameIds(Object.keys(result.kinds || {}), fixture.notes)
        || !Array.isArray(result.notes) || !sameIds(result.notes.map(row => row?.id), fixture.notes)
        || !Array.isArray(result.actions)) critical.push('incomplete_review');
    const noteRows = Array.isArray(result.notes) ? result.notes : [];
    for (const row of noteRows) {
      if (exp.planning.includes(Number(row?.id)) && matches(
        '\\((?:отмечено )?выполнено?\\)|всё выполнено|дело закрыто', row?.metadata?.summary || '',
      )) critical.push('invented_completion');
    }
    quality.push(reasons.every(entry => valid(entry)
      && (variant === 'new' ? entry[1].reason : entry[1]).length <= 120));
    quality.push(actions.every(action => action?.action !== 'ask'
      || !exp.planning.includes(Number(action.note))));
  } else {
    const lines = nodes.map(node => [node.text, node.who, node.at].filter(Boolean).join(' '));
    let subject = result.say || '';
    if (fixture.kind === 'plan') {
      subject = lines.join('\n');
      const target = Number(result.note ?? fixture.linked);
      if (!nodes.length || !fixture.notes.includes(target) || question
          || result.collection || result.edit || actions.length) critical.push('wrong_plan_format');
    } else if (fixture.kind === 'collection') {
      subject = [result.collection?.body, ...lines].filter(Boolean).join('\n');
      if (!result.collection?.title || !result.collection?.body
          || !sameIds(result.collection?.sources, exp.sources) || question || actions.length
          || (exp.planRequired && !nodes.length)) critical.push('incomplete_collection');
    } else if (fixture.kind === 'question') {
      subject = question || '';
      if (!question || hasDraft) critical.push('wrong_question_format');
    } else if (!subject || hasDraft || question) {
      critical.push('wrong_summary_format');
    }
    for (const link of raw.matchAll(/\[\[note:(\d+)\]\]/g))
      if (!fixture.notes.includes(Number(link[1]))) critical.push('unknown_note');
    for (const pattern of exp.required || [])
      if (!matches(pattern, subject)) critical.push(`missing:${pattern}`);
    if (exp.done && !nodes.some(node => node.done === true && matches(exp.done, node.text)))
      critical.push('lost_done');
    if (exp.order) {
      let previous = -1;
      for (const pattern of exp.order) {
        previous = lines.findIndex((line, i) => i > previous && matches(pattern, line));
        if (previous < 0) {
          critical.push('lost_step_order');
          break;
        }
      }
    }
    if (exp.minSteps && nodes.length < exp.minSteps) critical.push('too_few_steps');
    if (fixture.stage === 'stage4' && exp.maxSteps && nodes.length > exp.maxSteps)
      critical.push('extra_steps');
    if (exp.forbidden && matches(exp.forbidden, subject)) critical.push('unrelated_steps');
    quality.push(nodes.length <= (exp.maxSteps || 12));
    quality.push(String(result.say || '').trim().split(/\s+/).length <= 90);
    if (question) quality.push(question.length <= 150);
  }
  const points = critical.length ? 0 : quality.filter(Boolean).length / quality.length;
  return { points, critical };
}

function average(rows) {
  return rows.reduce((total, row) => total + row.points, 0) / rows.length;
}

function priceFrom(model) {
  const price = model.top_provider?.pricing;
  const input = Number(price?.prompt_per_million ?? NaN);
  const output = Number(price?.completion_per_million ?? NaN);
  return input >= 0 && output >= 0 && Number.isFinite(input + output) ? { input, output } : null;
}

function upperCost(payload, rate) {
  // UTF-8 bytes plus message overhead deliberately overestimate input tokens.
  const input = Buffer.byteLength(JSON.stringify(payload.messages), 'utf8') + 256;
  // V4 Flash reported separate reasoning above max_tokens on the initial run.
  const output = payload.max_tokens * (payload.model === 'deepseek/deepseek-v4-flash' ? 3 : 1.5);
  return (input * rate.input + output * rate.output) / 1e6;
}

const modeOf = fixture => fixture.kind === 'review' ? 'review' : 'chat';
const screen = cases.filter(fixture => fixture.stage === 'screen');
const holdout = cases.filter(fixture => fixture.stage === 'holdout');
const pairedCases = cases.filter(fixture => fixture.stage === 'stage4'
  || (fixture.stage === 'holdout' && fixture.kind === 'review'));
const variants = stage4 ? [[BASELINE, 'new'], [GEMMA, 'new']]
  : [[BASELINE, 'old'], [BASELINE, 'new'], ...CANDIDATES.map(id => [id, 'new'])];
if (stage4 && resume) throw new Error('Парное сравнение запускается с новым набором результатов');
if (!stage4) for (const fixture of cases) payloadFor(fixture, BASELINE, 'old');
if (!dry && !key) throw new Error('Задайте POLZA_KEY или запустите с -DryRun');

const listing = await fetch(BASE + 'models?type=chat', { signal: AbortSignal.timeout(30_000) });
if (!listing.ok) throw new Error(`Каталог Polza.AI вернул HTTP ${listing.status}`);
const catalog = (await listing.json()).data;
const rates = Object.fromEntries(variants.map(([id]) => [
  id, priceFrom(catalog.find(model => model.id === id) || {}),
]));
if (!rates[BASELINE]) throw new Error('Нет подтверждённого тарифа Qwen4 Max');
if (stage4 && !rates[GEMMA]) throw new Error('Нет подтверждённого тарифа Gemma 3 27B');
const active = variants.filter(([id]) => rates[id]);
const cheap = CANDIDATES.filter(id => rates[id]);
const estimated = active.map(([model, variant]) => ({
  model, variant,
  estimated_ceiling_rub: (stage4 ? pairedCases : screen).reduce(
    (sum, fixture) => sum + upperCost(payloadFor(fixture, model, variant), rates[model]), 0,
  ),
}));
console.table(estimated);
if (!stage4)
  for (const model of CANDIDATES.filter(id => !rates[id])) console.log(`Нет тарифа: ${model}`);
if (dry) {
  const fixtureCount = stage4 ? pairedCases.length : `${screen.length} + ${holdout.length}`;
  console.log(`Тестовых случаев: ${fixtureCount}. Лимит: ${BUDGET} ₽. Запросов к моделям: 0.`);
  process.exit(0);
}

const previous = resume ? JSON.parse(fs.readFileSync(resume, 'utf8')) : null;
const rows = previous?.rows.map(row => {
  const previousRevision = previous.revision ?? 1;
  if (row.variant === 'new' && previousRevision === 1) row.variant = 'pilot';
  if (row.variant === 'new' && previousRevision === 2 && row.mode === 'review')
    row.variant = 'pilot-review';
  const fixture = cases.find(item => item.id === row.fixture);
  return Object.assign(row, score(fixture, {
    choices: [{ finish_reason: row.finish_reason, message: { content: row.answer } }],
  }, row.variant));
}) ?? [];
let spent = rows.reduce((total, row) => total + row.reserved_rub, 0);
let pending = 0;
let halted = false;
const recommendations = {};

function saveReport() {
  fs.writeFileSync(reportFile, JSON.stringify({ revision: REVISION, budget: BUDGET,
    spent_or_reserved: spent, halted, rates, recommendations, rows }, null, 2));
}
const holdoutReserve = holdout.reduce((sum, fixture) => sum
  + upperCost(payloadFor(fixture, BASELINE, 'new'), rates[BASELINE])
  + Math.max(0, ...cheap.map(model => upperCost(payloadFor(fixture, model, 'new'), rates[model]))), 0);

async function run(fixture, model, variant, reserve = 0) {
  if (rows.some(row => row.fixture === fixture.id && row.model === model && row.variant === variant))
    return;
  const payload = payloadFor(fixture, model, variant);
  const ceiling = upperCost(payload, rates[model]);
  if (halted || spent + pending + ceiling + reserve > BUDGET) return;
  pending += ceiling;
  const row = { fixture: fixture.id, stage: fixture.stage, mode: modeOf(fixture), model, variant };
  const start = performance.now();
  let response;
  try {
    const http = await fetch(BASE + 'chat/completions', {
      method: 'POST',
      headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(90_000),
    });
    row.http = http.status;
    response = await http.json();
  } catch {
    halted = true;
  }
  const usage = response?.usage;
  const charged = Number(usage?.cost_rub ?? NaN);
  row.cost_rub = charged >= 0 && Number.isFinite(charged) ? charged : null;
  row.reserved_rub = row.cost_rub ?? ceiling;
  row.ms = Math.round(performance.now() - start);
  row.usage = usage ?? null;
  row.answer = response?.choices?.[0]?.message?.content ?? null;
  row.finish_reason = response?.choices?.[0]?.finish_reason ?? null;
  Object.assign(row, score(fixture, response || {}, variant));
  pending -= ceiling;
  spent += row.reserved_rub;
  if (row.cost_rub > ceiling || row.http === 401 || row.http === 402 || row.http === 403)
    halted = true;
  rows.push(row);
  saveReport();
  console.log(`${row.fixture} ${model} ${variant}: ${row.points}, ${row.reserved_rub.toFixed(3)} ₽`);
}

if (stage4) {
  const estimate = estimated.reduce((total, row) => total + row.estimated_ceiling_rub, 0);
  if (estimate > BUDGET) throw new Error('Парный прогон превышает лимит по оценке');
  for (const fixture of pairedCases) {
    for (const [model, variant] of active) await run(fixture, model, variant);
    if (halted) break;
  }
  const summary = active.flatMap(([model]) => modes.map(mode => {
    const observed = rows.filter(row => row.model === model && row.mode === mode);
    return {
      model, mode, attempted: observed.length,
      expected: pairedCases.filter(fixture => modeOf(fixture) === mode).length,
      passed: observed.filter(row => !row.critical.length).length,
      api_failures: observed.filter(row => !(row.http >= 200 && row.http < 300)).length,
      truncated: observed.filter(row => row.finish_reason === 'length').length,
      reported_rub: observed.every(row => row.cost_rub !== null)
        ? observed.reduce((sum, row) => sum + row.cost_rub, 0) : null,
      rub_or_reserved: observed.reduce((sum, row) => sum + row.reserved_rub, 0),
    };
  }));
  recommendations.paired = summary;
  saveReport();
  console.table(summary);
  console.log(`Учтено ${spent.toFixed(2)} ₽. Ответы и оценки: ${reportFile}`);
  process.exit(halted || summary.some(row => row.attempted !== row.expected) ? 1 : 0);
}

const queue = [...active];
async function screenModels() {
  while (queue.length && !halted) {
    const [model, variant] = queue.shift();
    for (const fixture of screen) await run(fixture, model, variant, holdoutReserve);
  }
}
await Promise.all(Array.from({ length: 3 }, screenModels));

function results(model, mode, stage) {
  return rows.filter(row => row.model === model && row.variant === 'new'
    && row.mode === mode && row.stage === stage);
}

function qualifies(candidate, reference, expected) {
  return candidate.length === expected && reference.length === expected
    && candidate.every(row => !row.critical.length)
    && average(candidate) >= average(reference) - 0.05;
}

function meanCost(rows) {
  return rows.reduce((total, row) => total + row.reserved_rub, 0) / rows.length;
}

for (const mode of modes) {
  const initial = results(BASELINE, mode, 'screen');
  const initialCount = screen.filter(fixture => modeOf(fixture) === mode).length;
  const candidate = cheap
    .map(model => ({ model, rows: results(model, mode, 'screen') }))
    .filter(entry => qualifies(entry.rows, initial, initialCount)
      && meanCost(entry.rows) < meanCost(initial))
    .sort((a, b) => meanCost(a.rows) - meanCost(b.rows))[0]?.model;
  const validation = holdout.filter(fixture => modeOf(fixture) === mode);
  for (const fixture of validation) {
    await run(fixture, BASELINE, 'new');
    if (candidate) await run(fixture, candidate, 'new');
  }
  const checked = candidate && results(candidate, mode, 'holdout');
  const reference = results(BASELINE, mode, 'holdout');
  const eligible = checked && qualifies(checked, reference, validation.length)
    && meanCost([...checked, ...results(candidate, mode, 'screen')])
      < meanCost([...reference, ...initial]);
  recommendations[mode] = {
    candidate: candidate ?? null,
    automatic_shortlist: eligible ? candidate : BASELINE,
    complete: initial.length === initialCount && reference.length === validation.length
      && (!checked || checked.length === validation.length),
    // Confirm fidelity and readability against the synthetic inputs before choosing a default.
    needs_review: true,
  };
}

saveReport();
console.log(`Учтено ${spent.toFixed(2)} ₽. Ответы и оценки: ${reportFile}`);
