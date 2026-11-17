// Заглушка Polza.AI для ручной проверки ИИ-чата компьютера без платных запросов.
//   node tests/fake-polza.cjs [порт]
//   MIMO_AI_BASE=http://127.0.0.1:<порт>/api cargo run -p mimo-core --example dev
// Отвечает как модель: ссылкой на первую заметку из данных, черновиком заметки, пачкой
// напоминаний или разбором картинки — в зависимости от вопроса.
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
