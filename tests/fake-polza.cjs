// Заглушка Polza.AI для ручной проверки ИИ-чата компьютера без платных запросов.
//   node tests/fake-polza.cjs [порт]
//   MIMO_AI_BASE=http://127.0.0.1:<порт>/api cargo run -p mimo-core --example dev
// Отвечает как модель: ссылкой на первую заметку из данных, черновиком заметки, пачкой
// напоминаний или разбором картинки — в зависимости от вопроса. Для помощника: проверка заметок,
// выжимка, вопрос о планах и диалог после него.
"use strict";
const http = require("http");
const port = Number(process.argv[2] || 5199);

function answer(payload) {
  const msgs = payload.messages || [];
  const system = String((msgs[0] || {}).content || "");
  const last = msgs[msgs.length - 1] || {};
  const parts = Array.isArray(last.content) ? last.content : [{ type: "text", text: String(last.content || "") }];
  const text = parts.filter((p) => p.type === "text").map((p) => p.text).join("\n").toLowerCase();
  const images = parts.filter((p) => p.type === "image_url").length;
  // Фоновый помощник: проверка заметок, выжимка, вопрос о планах, диалог, начатый помощником.
  if (system.includes("Ты менеджер заметок")) {
    let data = [];
    try { data = JSON.parse(String(last.content || "[]")); } catch (_) { /* пусто */ }
    const stop = new Set(["после", "перед", "нужно", "очень", "когда", "будет", "этого"]);
    const notes = data.map((n) => {
      const word = (String(n.text || n.title).toLowerCase().match(/[а-яё]{5,}/g) || []).find((w) => !stop.has(w)) || "заметка";
      const row = { id: n.id, title: n.needs_title ? "Заметка про " + word : "", reminder_at: null, reason: "", metadata: { topics: [word], summary: String(n.text || "").slice(0, 80) } };
      if (!n.has_reminder && /завтра/i.test(n.text || "")) {
        const d = new Date(Date.now() + 86400000), p = (k) => String(k).padStart(2, "0"), off = -d.getTimezoneOffset(), sign = off >= 0 ? "+" : "-";
        row.reminder_at = d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()) + "T10:00:00" + sign + p(Math.floor(Math.abs(off) / 60)) + ":" + p(Math.abs(off) % 60);
        row.reason = "завтра";
      }
      return row;
    });
    const suggestions = [];
    if (data.length >= 2 && data[0].complete && data[1].complete) {
      suggestions.push({ type: "merge", sources: [data[0].id, data[1].id], reason: "Похожие записи: можно собрать вместе.", title: "Общая заметка", body: data[0].text + "\n\n" + data[1].text });
    }
    return JSON.stringify({ notes, suggestions });
  }
  if (system.includes("Составь одну краткую содержательную выжимку")) {
    return JSON.stringify({ title: "Выжимка по теме", body: "Короткая выжимка заглушки по заметкам." });
  }
  if (system.includes("ОДНО короткое сообщение")) return "Что у вас запланировано на ближайшие дни?";
  if (system.includes("Этот диалог начал ты сам")) {
    return "Записываю.\n[[memo]]Планы на неделю записаны[[/memo]]\n[[draft]]" + JSON.stringify({ title: "Планы на неделю", body: "Со слов пользователя: " + text }) + "[[/draft]]";
  }
  if (system.includes("Ответ только JSON")) {
    return JSON.stringify({ title: "Заметка по картинке", summary: "На картинке " + images + " изображение(я). Это ответ заглушки.", reminder_at: null, reason: "", metadata: { topics: ["картинка"] } });
  }
  const id = (system.match(/"id":(\d+)/) || [])[1];
  if (text.includes("напомн")) {
    const d = new Date(Date.now() + 2 * 86400000), p = (n) => String(n).padStart(2, "0");
    const day = (k) => { const x = new Date(d.getTime() + k * 86400000); return `${x.getFullYear()}-${p(x.getMonth() + 1)}-${p(x.getDate())}`; };
    return "Предлагаю два напоминания.\n[[reminders]]\n" + day(0) + " 10:00 | Позвонить врачу | уточнить время\n" + day(1) + " 18:30 | Забрать посылку\n[[/reminders]]";
  }
  if (text.includes("создай заметку") || text.includes("сохрани")) {
    return "Подготовил заметку.\n[[draft]]" + JSON.stringify({ title: "Список покупок", body: "Молоко, хлеб, яблоки", metadata: { topics: ["покупки"] } }) + "[[/draft]]";
  }
  if (images) return "Вижу картинок: " + images + ". Это ответ заглушки.";
  return id ? "По вашим заметкам: смотрите [[note:" + id + "]]. Это ответ заглушки." : "Заметок не нашёл. Это ответ заглушки.";
}

http.createServer((req, res) => {
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    res.setHeader("Content-Type", "application/json");
    if (req.url.includes("/models")) return res.end(JSON.stringify({ data: [{ id: "google/gemini-2.5-flash" }, { id: "openai/gpt-4o-mini" }] }));
    if (req.url.includes("/balance")) return res.end(JSON.stringify({ amount: "100.00", available: "99.50" }));
    if (req.url.includes("chat/completions")) {
      const payload = JSON.parse(body || "{}");
      return setTimeout(() => res.end(JSON.stringify({
        choices: [{ message: { content: answer(payload) }, finish_reason: "stop" }],
        usage: { total_tokens: 321, cost_rub: "0.00" },
      })), 600);
    }
    res.statusCode = 404; res.end("{}");
  });
}).listen(port, "127.0.0.1", () => console.log("fake polza on http://127.0.0.1:" + port + "/api"));
