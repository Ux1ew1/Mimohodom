"use strict";
// Интерфейс «Мимоходом» для компьютера. Вся логика данных — в ядре (Rust), сюда приходят команды `rpc`.

const tauri = window.__TAURI__;
async function rpc(cmd, args = {}) {
  if (tauri && tauri.core) return tauri.core.invoke("rpc", { cmd, args });
  const r = await fetch("/rpc", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ cmd, args }) });
  const j = await r.json();
  if (j.error) throw new Error(j.error);
  return j.ok;
}
const $ = (s, root = document) => root.querySelector(s);
const el = (tag, props = {}, ...kids) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === "class") e.className = v;
    else if (k === "text") e.textContent = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else if (v !== false && v != null) e.setAttribute(k, v === true ? "" : v);
  }
  for (const kid of kids.flat()) if (kid != null) e.append(kid.nodeType ? kid : document.createTextNode(kid));
  return e;
};

const S = {
  view: "notes", folder: null, query: "", notes: [], folders: [], draft: null, dirty: false,
  grid: false, imgCache: new Map(), sync: null, lastStatus: "", lastPeerSync: 0, code: null,
  chat: null, chats: [], messages: [], attach: [], ai: null, assist: null, assistSig: "", assistLog: null, videoWatching: false, modalClose: null,
  cal: null, fired: [], firedSig: "", shown: 200,
};

// ---------- общие помощники ----------
function toast(text, err = false) {
  const t = $("#toast");
  t.textContent = text; t.className = "toast" + (err ? " err" : "");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.add("hidden"), err ? 6000 : 2800);
}
const fail = (e) => toast(String(e && e.message ? e.message : e), true);

function modal(build) {
  return new Promise((resolve) => {
    const m = $("#modal");
    const close = (v) => { S.modalClose = null; m.classList.add("hidden"); m.replaceChildren(); resolve(v); };
    S.modalClose = close;
    m.replaceChildren(el("div", { class: "box" }, build(close)));
    m.classList.remove("hidden");
    m.onclick = (ev) => { if (ev.target === m) close(null); };
  });
}
function askText(title, initial = "", ok = "Готово") {
  return modal((close) => {
    const input = el("input", { class: "field", value: initial, style: "width:100%" });
    input.addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.isComposing) close(input.value); });
    setTimeout(() => { input.focus(); input.select(); }, 0);
    return [el("h3", { text: title }), input, el("div", { class: "btns" },
      el("button", { class: "secondary", text: "Отмена", onclick: () => close(null) }),
      el("button", { class: "primary", text: ok, onclick: () => close(input.value) }))];
  });
}
function confirmBox(title, text, ok = "Удалить") {
  return modal((close) => [el("h3", { text: title }), el("div", { class: "note", text }), el("div", { class: "btns" },
    el("button", { class: "secondary", text: "Отмена", onclick: () => close(false) }),
    el("button", { class: "primary", style: "background:var(--red)", text: ok, onclick: () => close(true) }))]);
}
async function imageUrl(name) {
  if (S.imgCache.has(name)) return S.imgCache.get(name);
  const url = await rpc("image_get", { name });
  if (url) S.imgCache.set(name, url);
  return url;
}
function fmtDate(ms) { return new Date(ms).toLocaleString("ru-RU", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }); }
function toLocalInput(ms) {
  if (!ms) return "";
  const d = new Date(ms), p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}
const REPEATS = [["0", "Не повторять"], ["1", "Каждый день"], ["2", "Каждую неделю"], ["3", "Каждый месяц"], ["4", "Каждый год"]];
const LEADS = [["-1", "Как в настройках"], ["0", "В момент события"], ["5", "За 5 минут"], ["15", "За 15 минут"], ["30", "За 30 минут"], ["60", "За час"], ["1440", "За день"], ["2880", "За 2 дня"], ["4320", "За 3 дня"], ["10080", "За неделю"]];

// ---------- боковая панель ----------
function renderSide() {
  for (const b of document.querySelectorAll(".nav")) b.classList.toggle("active", b.dataset.view === S.view);
  const box = $("#folders"); box.replaceChildren();
  const total = S.folders.reduce((a, f) => a + f.notes, 0);
  const all = el("button", { class: "folder" + (S.folder === null ? " active" : ""), onclick: () => selectFolder(null) },
    el("span", { text: "Все заметки" }));
  box.append(all);
  for (const f of S.folders) {
    const row = el("button", { class: "folder" + (S.folder === f.id ? " active" : ""), onclick: () => selectFolder(f.id),
      oncontextmenu: (e) => { e.preventDefault(); folderMenu(f); } },
      el("span", { text: f.name }), el("span", { class: "count", text: String(f.notes) }));
    box.append(row);
  }
  void total;
}
async function folderMenu(f) {
  const act = await modal((close) => [el("h3", { text: f.name }),
    el("div", { class: "btns", style: "justify-content:flex-start;flex-wrap:wrap" },
      el("button", { class: "secondary", text: "Переименовать", onclick: () => close("rename") }),
      el("button", { class: "danger", text: "Удалить папку", onclick: () => close("delete") }),
      el("button", { class: "secondary", text: "Закрыть", onclick: () => close(null) }))]);
  try {
    if (act === "rename") {
      const name = await askText("Новое имя папки", f.name, "Переименовать");
      if (name) { await rpc("rename_folder", { id: f.id, name }); await reload(); }
    } else if (act === "delete") {
      if (await confirmBox("Удалить папку?", "Заметки не удалятся, они останутся в «Все заметки».")) {
        await rpc("delete_folder", { id: f.id });
        if (S.folder === f.id) S.folder = null;
        await reload();
      }
    }
  } catch (e) { fail(e); }
}
async function selectFolder(id) {
  if (!(await leaveEditor())) return;
  S.folder = id; S.view = "notes"; showView(); await reload();
}

// ---------- список заметок ----------
const SHOW_STEP = 200;
async function loadNotes() {
  S.notes = await rpc("notes", { query: S.query, folder: S.folder });
  S.shown = SHOW_STEP;
}
async function reload() {
  S.folders = await rpc("folders");
  if (S.folder !== null && !S.folders.some((f) => f.id === S.folder)) S.folder = null;
  await loadNotes();
  renderSide(); renderList();
}
function renderList() {
  const box = $("#notes");
  box.classList.toggle("grid", S.grid);
  box.replaceChildren();
  if (!S.notes.length) {
    box.append(el("div", { class: "empty", text: S.query ? "Ничего не найдено" : "Здесь пока нет заметок. Нажмите «Новая заметка»." }));
    return;
  }
  // Рисуем по частям: на десятках тысяч заметок сразу все карточки подвесили бы окно.
  const shown = S.notes.slice(0, S.shown);
  for (const n of shown) {
    const card = el("button", { class: "card" + (n.member_of ? " member" : "") + (S.draft && S.draft.id === n.id ? " active" : ""), onclick: () => openNote(n.id) });
    if (n.images.length) {
      const img = el("img", { class: "thumb", alt: "" });
      imageUrl(n.images[0]).then((u) => { if (u) img.src = u; else img.remove(); });
      card.append(img);
    }
    const meta = el("div", { class: "m" }, el("span", { text: fmtDate(n.updated) }));
    if (n.reminder) meta.append(el("span", { class: "tag", text: "⏰ " + fmtDate(n.reminder) }));
    if (n.images.length > 1) meta.append(el("span", { text: n.images.length + " фото" }));
    if (n.suggestion) meta.append(el("span", { class: "tag suggest", title: n.suggestion_reason, text: "💡 напомнить " + fmtDate(n.suggestion) }));
    if (n.stack) meta.append(el("span", { class: "tag stack", role: "button", title: "Показать или скрыть исходные заметки", text: "Стопка · " + n.stack.count + (n.stack.expanded ? " ▾" : " ▸"), onclick: (e) => { e.stopPropagation(); toggleStack(n.id); } }));
    card.append(el("div", { class: "t", text: n.title }), el("div", { class: "b", text: n.body }), meta);
    box.append(card);
  }
  if (S.notes.length > shown.length) {
    box.append(el("button", { class: "secondary", style: "grid-column:1/-1", text: "Показать ещё (" + (S.notes.length - shown.length) + ")", onclick: () => { S.shown += SHOW_STEP; renderList(); } }));
  }
}

// ---------- редактор ----------
async function openNote(id) {
  if (!(await leaveEditor())) return;
  const n = await rpc("note", { id });
  if (!n) { await reload(); return; }
  S.draft = { id: n.id, title: n.title_auto ? "" : n.title, body: n.body, reminder: n.reminder, repeat: n.repeat, lead: n.lead, images: [...n.images], folder: n.folder, updated: n.updated, stamp: n.sync_ts, suggestion: n.suggestion || 0, reason: n.suggestion_reason || "", stack: n.stack };
  S.dirty = false; renderEditor(); renderList();
}
async function newNote() {
  if (!(await leaveEditor())) return;
  S.draft = { id: null, title: "", body: "", reminder: 0, repeat: 0, lead: -1, images: [], folder: S.folder || 0 };
  S.dirty = false; renderEditor(); renderList();
  setTimeout(() => { const t = $("#e-body"); if (t) t.focus(); }, 0);
}
async function leaveEditor() {
  if (S.draft && S.dirty) {
    if (S.draft.title.trim() || S.draft.body.trim() || S.draft.images.length) {
      try { await saveDraft(true); } catch (e) { fail(e); return false; }
    }
  }
  return true;
}
function closeEditor() { S.draft = null; S.dirty = false; renderEditor(); renderList(); }
async function saveDraft(silent = false) {
  const d = S.draft; if (!d) return;
  // В новую заметку вставили только ссылки на видео: предложим сводку вместо заметки-ссылки (не при автосохранении).
  if (!silent && !d.id && !d.images.length && !d.title.trim()) {
    let info = null;
    try { info = await rpc("video_info", { text: d.body }); } catch (_) { /* сохраняем как обычную заметку */ }
    if (info && info.only_links) {
      const choice = await offerVideo(info.links, true);
      if (choice === null) return;
      if (choice === "start") { if (await startVideo(info.links)) closeEditor(); return; }
    }
  }
  const saved = await rpc("save_note", {
    id: d.id, title: d.title, body: d.body, reminder: d.reminder, repeat: d.repeat, lead: d.lead, images: d.images, folder: d.folder,
  });
  d.id = saved.id; d.updated = saved.updated; d.stamp = saved.sync_ts; S.dirty = false;
  if (!silent) toast("Сохранено");
  await reload(); updateFoot();
}
function markDirty() { S.dirty = true; updateFoot(); }
function updateFoot() { const f = $("#e-state"); if (f) f.textContent = S.dirty ? "Есть несохранённые изменения" : S.draft && S.draft.id ? "Сохранено" : "Новая заметка"; }

function shrinkToJpeg(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file), img = new Image();
    img.onload = () => {
      const max = 2048, k = Math.min(1, max / Math.max(img.width, img.height));
      const c = document.createElement("canvas");
      c.width = Math.max(1, Math.round(img.width * k)); c.height = Math.max(1, Math.round(img.height * k));
      const g = c.getContext("2d"); g.fillStyle = "#fff"; g.fillRect(0, 0, c.width, c.height); g.drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(url);
      c.toBlob((b) => b ? resolve(b) : reject(new Error("Не удалось сжать картинку")), "image/jpeg", 0.86);
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error("Не удалось открыть картинку")); };
    img.src = url;
  });
}
function blobToBase64(blob) {
  return new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result).split(",")[1]); r.onerror = rej; r.readAsDataURL(blob); });
}
async function addImages(files) {
  const d = S.draft; if (!d) return;
  for (const f of files) {
    if (d.images.length >= 8) { toast("В заметке не больше 8 картинок", true); break; }
    if (!f.type.startsWith("image/")) continue;
    try {
      const data = await blobToBase64(await shrinkToJpeg(f));
      d.images.push(await rpc("image_add", { data }));
      markDirty();
    } catch (e) { fail(e); }
  }
  renderStrip();
}
function renderStrip() {
  const box = $("#e-strip"); if (!box || !S.draft) return;
  box.replaceChildren();
  S.draft.images.forEach((name, i) => {
    const img = el("img", { alt: "Картинка " + (i + 1), onclick: async () => {
      const u = await imageUrl(name);
      if (u) modal((close) => [el("img", { class: "big", src: u, onclick: () => close(null) })]);
    } });
    imageUrl(name).then((u) => { if (u) img.src = u; });
    box.append(el("div", { class: "img" }, img, el("button", { class: "x", title: "Убрать", "aria-label": "Убрать картинку", onclick: () => {
      S.draft.images.splice(i, 1); markDirty(); renderStrip();
    } }, "×")));
  });
}
function renderEditor() {
  const host = $("#editor"), split = $(".split");
  if (!S.draft) { host.classList.add("hidden"); split.classList.add("no-editor"); host.replaceChildren(); return; }
  host.classList.remove("hidden"); split.classList.remove("no-editor");
  const d = S.draft;
  const title = el("input", { class: "title", id: "e-title", placeholder: "Название (необязательно)", value: d.title, oninput: (e) => { d.title = e.target.value; markDirty(); } });
  const body = el("textarea", { id: "e-body", placeholder: "Что записать?", oninput: (e) => { d.body = e.target.value; markDirty(); clearTimeout(refreshVideo.timer); refreshVideo.timer = setTimeout(refreshVideo, 400); } });
  body.value = d.body;
  let videoLinks = [];
  const videoBtn = el("button", { class: "secondary hidden", text: "Сводка видео", title: "Сделать отдельную заметку-сводку по ссылке на YouTube из текста", onclick: async () => {
    if (await offerVideo(videoLinks, false) === "start") await startVideo(videoLinks);
  } });
  async function refreshVideo() {
    try { videoLinks = (await rpc("video_info", { text: d.body })).links; videoBtn.classList.toggle("hidden", !videoLinks.length); } catch (_) { /* кнопка просто не появится */ }
  }
  const rem = el("input", { type: "datetime-local", value: toLocalInput(d.reminder), onchange: (e) => {
    d.reminder = e.target.value ? new Date(e.target.value).getTime() : 0; markDirty(); syncOpts();
  } });
  const clearRem = el("button", { class: "danger", text: "Сбросить", onclick: () => { d.reminder = 0; rem.value = ""; markDirty(); syncOpts(); } });
  const rep = el("select", { onchange: (e) => { d.repeat = +e.target.value; markDirty(); } }, REPEATS.map(([v, t]) => el("option", { value: v, text: t })));
  rep.value = String(d.repeat);
  const lead = el("select", { onchange: (e) => { d.lead = +e.target.value; markDirty(); } }, LEADS.map(([v, t]) => el("option", { value: v, text: t })));
  lead.value = String(d.lead);
  const fold = el("select", { onchange: (e) => { d.folder = +e.target.value; markDirty(); } },
    el("option", { value: "0", text: "Все заметки" }), S.folders.map((f) => el("option", { value: String(f.id), text: f.name })));
  fold.value = String(d.folder);
  const pick = el("input", { type: "file", accept: "image/*", multiple: true, class: "hidden", onchange: (e) => { addImages([...e.target.files]); e.target.value = ""; } });
  function syncOpts() { const on = !!d.reminder; rep.disabled = !on; lead.disabled = !on; clearRem.classList.toggle("hidden", !on); }
  const foot = el("div", { class: "foot" }, el("div", { class: "grow", id: "e-state" }),
    d.stack ? el("button", { class: "secondary", text: "Разобрать стопку", title: "Исходные заметки снова появятся в списке", onclick: async () => {
      if (await confirmBox("Разобрать стопку?", "Исходные заметки вернутся в список, итоговая заметка останется.", "Разобрать")) { try { await rpc("stack_release", { top: d.id }); d.stack = null; renderEditor(); await reload(); } catch (e) { fail(e); } }
    } }) : null,
    d.id ? el("button", { class: "danger", text: "Удалить", onclick: async () => {
      if (await confirmBox("Удалить заметку?", "Заметка исчезнет и на синхронизированных устройствах.")) {
        try { await rpc("delete_note", { id: d.id }); closeEditor(); await reload(); } catch (e) { fail(e); }
      }
    } }) : null,
    el("button", { class: "secondary", text: "Закрыть", onclick: async () => { if (await leaveEditor()) closeEditor(); } }),
    el("button", { class: "save", text: "Сохранить", onclick: () => saveDraft().catch(fail) }));
  const banner = el("div", { class: "banner" + (d.suggestion ? "" : " hidden") }, el("span", { class: "txt", text: d.suggestion ? "Помощник нашёл дату: " + fmtDate(d.suggestion) + (d.reason ? " — " + d.reason : "") : "" }),
    el("button", { class: "compact main", text: "Поставить напоминание", onclick: () => { d.reminder = d.suggestion; rem.value = toLocalInput(d.reminder); d.suggestion = 0; banner.classList.add("hidden"); markDirty(); syncOpts(); } }),
    el("button", { class: "compact", text: "Скрыть", onclick: async () => { try { await rpc("suggestion_dismiss", { id: d.id }); d.suggestion = 0; banner.classList.add("hidden"); await reload(); } catch (e) { fail(e); } } }));
  host.replaceChildren(title, el("hr"), banner, body, el("div", { class: "strip", id: "e-strip" }),
    el("div", { class: "opts" },
      el("button", { class: "secondary", text: "+ Картинка", onclick: () => pick.click() }), pick, videoBtn,
      el("label", {}, "Напомнить", rem, clearRem), el("label", {}, rep), el("label", {}, lead),
      el("label", {}, "Папка", fold)),
    foot);
  syncOpts(); renderStrip(); updateFoot(); refreshVideo();
  host.onpaste = (e) => { const files = [...(e.clipboardData?.files || [])].filter((f) => f.type.startsWith("image/")); if (files.length) { e.preventDefault(); addImages(files); } };
  host.ondragover = (e) => e.preventDefault();
  host.ondrop = (e) => { e.preventDefault(); addImages([...e.dataTransfer.files]); };
}

// ---------- видео YouTube ----------
// Сводки по ссылкам: платный запрос к модели, которая сама смотрит ролик. Как на телефоне: окно с ценой,
// отдельная заметка на ролик, затем предложение добавить её в общую заметку по той же теме.
const VIDEO_QUEUE = [];
function infoBox(title, text) {
  return modal((close) => [el("h3", { text: title }), el("div", { class: "note", style: "white-space:pre-line", text }),
    el("div", { class: "btns" }, el("button", { class: "primary", text: "Понятно", onclick: () => close(true) }))]);
}
/** Окно с ценой. Возвращает "start", "keep" (сохранить ссылку как есть) или null (отмена). */
async function offerVideo(links, canKeep) {
  await loadAi();
  const ai = S.ai, model = ai.videoModel || ai.defaultVideoModel, many = links.length > 1;
  return modal((close) => {
    const keep = canKeep ? el("button", { class: "secondary", text: "Сохранить как есть", onclick: () => close("keep") }) : null;
    const cancel = el("button", { class: "secondary", text: "Отмена", onclick: () => close(null) });
    if (!ai.hasKey) {
      return [el("h3", { text: "Подключите Polza.AI" }),
        el("div", { class: "note", text: "Сводку видео делает модель Polza.AI. Укажите ключ API в настройках ИИ" + (canKeep ? " или сохраните ссылку как обычную заметку." : ".") }),
        el("div", { class: "btns" }, cancel, keep)];
    }
    const text = (many ? "Ссылок на видео: " + links.length + ", каждая — отдельный запрос. Модель " : "Ссылка на видео будет отправлена в Polza.AI, и модель ") + model +
      " посмотрит ролик. Запрос оплачивается с вашего баланса Polza.AI, цена растёт с длиной видео. Ориентир: ролик на 10 минут обошёлся примерно в 2 ₽ на " + ai.defaultVideoModel +
      ". Результат появится отдельной заметкой; повтор после ошибки — только вручную.";
    return [el("h3", { text: many ? "Сделать сводки видео?" : "Сделать сводку видео?" }), el("div", { class: "note", text }),
      el("div", { class: "btns" }, cancel, keep, el("button", { class: "primary", text: "Сделать сводку", onclick: () => close("start") }))];
  });
}
async function startVideo(links) {
  try { await rpc("video_start", { links }); toast("Видео отправлено на анализ. Это может занять до минуты."); watchVideo(); return true; }
  catch (e) { fail(e); return false; }
}
/** Следит за запуском, пока он идёт, и забирает результат один раз. */
async function watchVideo() {
  if (S.videoWatching) return;
  S.videoWatching = true;
  const pill = $("#video-pill");
  let outcome = null;
  try {
    for (;;) {
      const p = await rpc("video_poll");
      if (!p.running) { outcome = p.outcome; break; }
      pill.classList.remove("hidden");
      pill.textContent = p.kind === "merge" ? "Объединяю видео в одну заметку…" : "Сводка видео: " + p.done + " из " + p.total + "…";
      await new Promise((r) => setTimeout(r, 1500));
    }
  } catch (e) { fail(e); }
  pill.classList.add("hidden");
  S.videoWatching = false;
  if (outcome) await videoFinished(outcome).catch(fail);
}
async function videoFinished(o) {
  await reload();
  if (S.draft && S.draft.id && !S.dirty) await refreshOpen();
  const idle = S.view === "notes" && !S.draft;
  if (o.kind === "merge") {
    if (o.merge_error) await infoBox("Объединить не удалось", o.merge_error); else toast("Видео добавлено в общую заметку");
    const asked = await askNextMerge();
    if (o.merged && !asked && idle) await openNote(o.merged);
    return;
  }
  const made = o.created.length;
  if (o.error) await infoBox("Сводка видео не готова", (made ? "Готово заметок: " + made + ".\n\n" : "") + o.error);
  else {
    VIDEO_QUEUE.push(...o.created);
    toast(made > 1 ? "Готово сводок: " + made : made === 1 ? "Сводка видео готова" : "Это видео уже есть в заметках");
  }
  const asked = !o.error && (await askNextMerge());
  const open = made === 1 ? o.created[0] : !made && o.existing.length === 1 ? o.existing[0] : 0;
  if (open && !asked && !o.error && idle) await openNote(open);
}
/** Предлагает добавить свежую сводку в заметку по той же теме; true — объединение запущено. */
async function askNextMerge() {
  while (VIDEO_QUEUE.length) {
    const id = VIDEO_QUEUE.shift();
    let target = null;
    try { target = await rpc("video_merge_candidate", { id }); } catch (_) { continue; }
    if (!target) continue;
    const yes = await modal((close) => [el("h3", { text: "Добавить в общую заметку?" }),
      el("div", { class: "note", text: "Видео «" + target.fresh_title + "» похоже на тему заметки «" + target.title + "». Добавить его отдельным разделом туда и обновить общую сводку? Нужен ещё один небольшой платный запрос (только текст, без видео). Отдельная заметка о видео после этого удаляется." }),
      el("div", { class: "btns" }, el("button", { class: "secondary", text: "Оставить отдельно", onclick: () => close(false) }), el("button", { class: "primary", text: "Объединить", onclick: () => close(true) }))]);
    if (!yes) continue;
    if (S.draft && (S.draft.id === id || S.draft.id === target.id)) {
      if (S.dirty) { toast("Заметка открыта с несохранёнными правками, сводка осталась отдельной.", true); continue; }
      closeEditor();
    }
    try { await rpc("video_merge", { src: id, dst: target.id }); toast("Объединяю видео в одну заметку…"); watchVideo(); return true; }
    catch (e) { fail(e); }
  }
  return false;
}

// ---------- ИИ-чат ----------
const HINTS = ["Что у меня запланировано на неделю?", "Найди рецепт в моих заметках", "Создай заметку со списком покупок"];
const MAX_ATTACH = 6;

async function loadAi() { S.ai = await rpc("ai_state"); return S.ai; }
function aiReady() { return S.ai && S.ai.hasKey && S.ai.model; }

async function renderChatView() {
  await loadAi();
  S.chats = await rpc("chats");
  if (S.chat !== null && !S.chats.some((c) => c.id === S.chat)) S.chat = null;
  renderChatList();
  if (S.chat !== null) S.messages = await rpc("chat_messages", { chat: S.chat });
  renderChat();
  if (S.ai.state.running) watchChat();
}
function renderChatList() {
  const box = $("#chats"); box.replaceChildren();
  $(".chat-split").classList.toggle("open", S.chat !== null);
  if (!S.chats.length) box.append(el("div", { class: "chat-note", style: "padding:6px 10px", text: "Чатов пока нет" }));
  for (const c of S.chats) {
    box.append(el("button", { class: "chat-item" + (c.id === S.chat ? " active" : ""), onclick: () => openChat(c.id) },
      el("div", { class: "t", text: c.title }), el("div", { class: "d", text: fmtDate(c.last) })));
  }
}
async function openChat(id) {
  S.chat = id; S.attach = []; S.messages = await rpc("chat_messages", { chat: id });
  renderChatList(); renderChat();
  setTimeout(() => { const t = $("#chat-text"); if (t) t.focus(); }, 0);
}
async function newChat(text) {
  try {
    const id = await rpc("chat_new", {});
    S.chats = await rpc("chats");
    await openChat(id);
    if (typeof text === "string" && text) { $("#chat-text").value = text; sendChat(); }
  } catch (e) { fail(e); }
}
async function chatMenu() {
  const c = S.chats.find((x) => x.id === S.chat); if (!c) return;
  const act = await modal((close) => [el("h3", { text: c.title }),
    el("div", { class: "btns", style: "justify-content:flex-start;flex-wrap:wrap" },
      el("button", { class: "secondary", text: "Переименовать", onclick: () => close("rename") }),
      el("button", { class: "danger", text: "Удалить чат", onclick: () => close("delete") }),
      el("button", { class: "secondary", text: "Закрыть", onclick: () => close(null) }))]);
  try {
    if (act === "rename") {
      const t = await askText("Название чата", c.title, "Переименовать");
      if (t) { await rpc("chat_rename", { id: c.id, title: t }); await renderChatView(); }
    } else if (act === "delete") {
      if (await confirmBox("Удалить чат?", "Переписка и картинки этого чата удалятся. Заметки, созданные из чата, останутся.")) {
        await rpc("chat_delete", { id: c.id }); S.chat = null; await renderChatView();
      }
    }
  } catch (e) { fail(e); }
}

// Текст ответа: ссылки [[note:ID]] на показанные заметки — кликабельные «названия».
function answerNodes(body, sources) {
  const titles = new Map((sources || []).map((s) => [s.id, s.title]));
  const out = []; const re = /\[\[note:(\d+)]]/g; let last = 0, m;
  while ((m = re.exec(body))) {
    out.push(body.slice(last, m.index)); last = re.lastIndex;
    const id = +m[1];
    if (titles.has(id)) {
      let t = titles.get(id) || "Заметка"; if (t.length > 44) t = t.slice(0, 44) + "…";
      out.push(el("a", { class: "ref", href: "#", onclick: (e) => { e.preventDefault(); goNote(id); } }, "«" + t + "»"));
    }
  }
  out.push(body.slice(last));
  return out;
}
function splitImages(body) {
  const names = []; const text = body.replace(/\n?\[\[img:([A-Za-z0-9_.,\-]*)]]/g, (_, list) => { for (const n of list.split(",")) if (n && !names.includes(n)) names.push(n); return ""; }).trim();
  return { text, names };
}
async function goNote(id) {
  S.view = "notes"; showView(); await reload(); openNote(id);
}
function thumbs(names) {
  const box = el("div", { class: "imgs" });
  for (const name of names) {
    const img = el("img", { alt: "Картинка", onclick: async () => { const u = await imageUrl(name); if (u) modal((close) => [el("img", { class: "big", src: u, onclick: () => close(null) })]); } });
    imageUrl(name).then((u) => { if (u) img.src = u; else img.alt = "Картинка не найдена"; });
    box.append(img);
  }
  return box;
}
function draftOffer(m) {
  const title = el("input", { value: m.draft.title || "", "aria-label": "Название заметки" });
  const body = el("textarea", { "aria-label": "Текст заметки" }); body.value = m.draft.body || "";
  return el("div", { class: "offer" }, el("div", { class: "chat-note", style: "margin-bottom:6px", text: "Предложение заметки — проверьте и сохраните" }), title, body,
    el("div", { class: "btns" },
      el("button", { class: "compact main", text: "Сохранить заметку", onclick: async () => {
        try { await rpc("draft_accept", { message: m.id, title: title.value, body: body.value }); toast("Заметка сохранена"); await refreshChat(); await reload(); } catch (e) { fail(e); }
      } }),
      el("button", { class: "compact", text: "Не сохранять", onclick: async () => { try { await rpc("draft_dismiss", { message: m.id }); await refreshChat(); } catch (e) { fail(e); } } })));
}
function remindersOffer(m) {
  const items = m.draft.items || []; const skip = new Set();
  const count = el("span");
  const create = el("button", { class: "compact main", onclick: async () => {
    try { const n = await rpc("reminders_accept", { message: m.id, skip: [...skip] }); toast("Создано напоминаний: " + n); await refreshChat(); await reload(); } catch (e) { fail(e); }
  } }, count);
  const upd = () => { count.textContent = "Создать: " + (items.length - skip.size); create.disabled = skip.size === items.length; };
  const list = items.map((it, i) => {
    const box = el("input", { type: "checkbox", checked: true, "aria-label": fmtDate(it.at) + ", " + it.title, onchange: (e) => { if (e.target.checked) skip.delete(i); else skip.add(i); upd(); } });
    return el("label", {}, box, el("span", {}, el("b", { text: fmtDate(it.at) + " · " + it.title }), it.body ? el("div", { class: "chat-note", text: it.body }) : null));
  });
  upd();
  return el("div", { class: "offer" }, el("div", { class: "chat-note", style: "margin-bottom:4px", text: "Напоминания по датам: снимите лишние" + (m.draft.skipped ? " · пропущено строк: " + m.draft.skipped : "") }), ...list,
    el("div", { class: "btns" }, create,
      el("button", { class: "compact", text: "Не создавать", onclick: async () => { try { await rpc("draft_dismiss", { message: m.id }); await refreshChat(); } catch (e) { fail(e); } } })));
}
function bubble(m) {
  const b = el("div", { class: "bubble " + (m.role === "user" ? "user" : "assistant") });
  b.append(el("div", { class: "who", text: m.role === "user" ? "Вы" : "Помощник" }));
  if (m.role === "user") {
    const { text, names } = splitImages(m.body);
    if (text) b.append(el("div", { class: "txt", text }));
    if (names.length) b.append(thumbs(names));
  } else {
    const clean = m.body.replace(/\[\[memo]][\s\S]*?(\[\[\/memo]]|$)/g, "").replace(/\[\[recap]]/g, "").trim();
    b.append(el("div", { class: "txt" }, answerNodes(clean, m.sources)));
    const memo = /\[\[memo]]([\s\S]*?)(\[\[\/memo]]|$)/.exec(m.body);
    if (memo) for (const line of memo[1].split("\n").map((x) => x.trim()).filter(Boolean)) b.append(el("div", { class: "memo", text: "✓ Запомнил: " + line }));
    for (const s of m.sources || []) {
      b.append(el("button", { class: "src", title: "Открыть заметку", onclick: () => goNote(s.id) },
        el("span", { "aria-hidden": "true", text: "▤" }), el("span", { class: "n", text: s.title }), el("span", { class: "arrow", text: "›" })));
    }
    if (m.draft && m.draft.kind === "reminders") b.append(remindersOffer(m));
    else if (m.draft && m.draft.title) b.append(draftOffer(m));
  }
  return b;
}
function renderAttach() {
  const box = $("#chat-attach"); if (!box) return;
  box.replaceChildren();
  S.attach.forEach((name, i) => {
    const img = el("img", { alt: "Вложение " + (i + 1) });
    imageUrl(name).then((u) => { if (u) img.src = u; });
    box.append(el("div", { class: "img" }, img, el("button", { class: "x", title: "Убрать", "aria-label": "Убрать картинку", onclick: () => { S.attach.splice(i, 1); renderAttach(); } }, "×")));
  });
}
async function attachFiles(files) {
  for (const f of files) {
    if (!f.type.startsWith("image/")) continue;
    if (S.attach.length >= MAX_ATTACH) { toast("К сообщению — не больше " + MAX_ATTACH + " картинок", true); break; }
    try { S.attach.push(await rpc("image_add", { data: await blobToBase64(await shrinkToJpeg(f)) })); } catch (e) { fail(e); }
  }
  renderAttach();
}
function renderChat() {
  const host = $("#chat-main"); host.replaceChildren();
  if (S.chat === null) {
    host.append(el("div", { class: "chat-empty" }, el("h2", { text: "Спросите о своих заметках" }),
      el("div", { class: "chat-note", style: "margin-bottom:12px", text: aiReady() ? "Помощник отвечает по вашим заметкам и может создать новую. Запросы идут в Polza.AI и оплачиваются с вашего баланса." : "Чтобы начать, укажите ключ Polza.AI и модель в настройках." }),
      aiReady() ? el("div", { class: "hints" }, el("button", { class: "compact main", text: "Новый чат", onclick: () => newChat() }), HINTS.map((h) => el("button", { class: "compact", text: h, onclick: () => newChat(h) })))
        : el("button", { class: "compact main", text: "Открыть настройки ИИ", onclick: () => { S.view = "settings"; showView(); } })));
    return;
  }
  const c = S.chats.find((x) => x.id === S.chat);
  const st = S.ai.state;
  host.append(el("div", { class: "chat-head" },
    el("button", { class: "more back", title: "К списку чатов", "aria-label": "К списку чатов", onclick: () => { S.chat = null; renderChatList(); renderChat(); } }, "‹"),
    el("div", { class: "t", text: c ? c.title : "Чат" }),
    el("button", { class: "more", title: "Меню чата", "aria-label": "Меню чата", onclick: chatMenu }, "⋮")));
  const list = el("div", { class: "msgs", id: "chat-msgs" });
  if (!S.messages.length) {
    list.append(el("div", { class: "chat-empty" }, el("h2", { text: "Новый чат" }),
      el("div", { class: "hints" }, HINTS.map((h) => el("button", { class: "compact", text: h, onclick: () => { $("#chat-text").value = h; sendChat(); } })))));
  }
  for (const m of S.messages) list.append(bubble(m));
  if (st.running === S.chat) list.append(el("div", { class: "waiting" }, el("div", { class: "spinner", role: "status", "aria-label": "Ждём ответ" }), "Помощник думает… Запрос с картинками может идти до трёх минут."));
  else if (st.error && st.error_chat === S.chat) {
    const lastUser = [...S.messages].reverse().find((m) => m.role === "user");
    list.append(el("div", { class: "chat-error" }, el("span", { text: st.error }),
      lastUser ? el("button", { class: "compact", text: "Повторить", onclick: () => { const p = splitImages(lastUser.body); sendChat(p.text, p.names); } }) : null));
  } else if (st.usage && st.usage_chat === S.chat && S.messages.length) list.append(el("div", { class: "chat-note", text: st.usage }));
  if (!aiReady()) list.append(el("div", { class: "chat-error" }, "Нет ключа Polza.AI или модели.", el("button", { class: "compact", text: "Настройки ИИ", onclick: () => { S.view = "settings"; showView(); } })));
  host.append(list);
  const text = el("textarea", { id: "chat-text", rows: 1, placeholder: "Сообщение", "aria-label": "Сообщение",
    oninput: (e) => { e.target.style.height = "auto"; e.target.style.height = Math.min(180, e.target.scrollHeight) + "px"; },
    onkeydown: (e) => { if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); sendChat(); } } });
  const pick = el("input", { type: "file", accept: "image/*", multiple: true, class: "hidden", onchange: (e) => { attachFiles([...e.target.files]); e.target.value = ""; } });
  const busy = !!st.running;
  host.append(el("div", { class: "chat-input" },
    el("div", { class: "strip", id: "chat-attach", style: "padding:0 0 8px" }),
    el("div", { class: "row2" },
      el("button", { class: "round attach", title: "Приложить картинки", "aria-label": "Приложить картинки", onclick: () => pick.click() }, "+"), pick,
      text,
      el("button", { class: "round send", id: "chat-send", title: "Отправить", "aria-label": "Отправить", disabled: busy, onclick: () => sendChat() }, "➤")),
    el("div", { class: "chat-note", style: "margin-top:4px", text: "Enter — отправить, Shift+Enter — новая строка. Картинки можно вставить или перетащить." })));
  host.onpaste = (e) => { const files = [...(e.clipboardData?.files || [])].filter((f) => f.type.startsWith("image/")); if (files.length) { e.preventDefault(); attachFiles(files); } };
  host.ondragover = (e) => e.preventDefault();
  host.ondrop = (e) => { e.preventDefault(); attachFiles([...e.dataTransfer.files]); };
  renderAttach();
  list.scrollTop = list.scrollHeight;
}
async function refreshChat() {
  if (S.chat === null) return;
  S.messages = await rpc("chat_messages", { chat: S.chat });
  const keep = $("#chat-text") ? $("#chat-text").value : "";
  renderChat();
  if (keep && $("#chat-text")) $("#chat-text").value = keep;
}
async function sendChat(textOverride, imagesOverride) {
  const box = $("#chat-text");
  const text = typeof textOverride === "string" ? textOverride : (box ? box.value : "");
  const images = imagesOverride || S.attach;
  if (!text.trim() && !images.length) return;
  try {
    await rpc("chat_send", { chat: S.chat, text, images });
    if (!imagesOverride) S.attach = [];
    if (box && typeof textOverride !== "string") box.value = "";
    await loadAi(); S.chats = await rpc("chats"); renderChatList(); await refreshChat();
    watchChat();
  } catch (e) { fail(e); }
}
function watchChat() {
  clearTimeout(watchChat.timer);
  watchChat.timer = setTimeout(async () => {
    try {
      const before = S.ai.state.created_note;
      await loadAi();
      if (S.ai.state.running) { watchChat(); return; }
      if (S.view === "chat") await refreshChat();
      if (S.ai.state.created_note !== before) await reload();
    } catch (_) { watchChat(); }
  }, 700);
}

// ---------- фоновый помощник ----------
const KINDS = { merge: "Объединение", link: "Стопка по теме", task: "Возможное дело", question: "Уточнение" };
const EVERY = [[1, "Каждый день"], [2, "Раз в 2 дня"], [3, "Раз в 3 дня"], [7, "Раз в неделю"]];
const hhmm = (m) => String(Math.floor(m / 60)).padStart(2, "0") + ":" + String(m % 60).padStart(2, "0");

async function toggleStack(top) { try { await rpc("stack_toggle", { top }); await reload(); } catch (e) { fail(e); } }
async function afterAssistChange() { await reload(); await renderAssist(true); }

async function renderAssist(force = false) {
  const root = $("#assistant"); if (!root) return;
  const a = await rpc("assist_state");
  S.assist = a;
  const sig = JSON.stringify(a);
  if (!force && sig === S.assistSig) return;
  // Не перерисовываем, пока пользователь правит текст предложения.
  const active = document.activeElement;
  if (!force && root.contains(active) && /^(INPUT|TEXTAREA|SELECT)$/.test(active.tagName)) return;
  S.assistSig = sig;
  root.replaceChildren();
  const run = a.running;
  root.append(el("h1", { text: "Помощник" }));
  root.append(el("div", { class: "note", text: "Помощник читает заметки через Polza.AI, чтобы подобрать названия, подсказки для поиска, найти даты для напоминаний и предложить объединения. Запросы платные (с вашего баланса); ничего не меняется без вашего подтверждения, кроме названий и подсказок для поиска. Результаты хранятся только на этом компьютере." }));
  if (!a.ready) root.append(el("div", { class: "chat-error", style: "margin:10px 0" }, "Нет ключа Polza.AI или модели.", el("button", { class: "compact", text: "Настройки ИИ", onclick: () => { S.view = "settings"; showView(); } })));

  // Проверка
  root.append(el("h2", { text: "Проверка заметок" }));
  const status = a.log.length ? a.log[0].text : "Проверок ещё не было.";
  root.append(el("div", { class: "status-line" }, run.review_running ? el("div", { class: "spinner", role: "status", "aria-label": "Идёт проверка" }) : null, el("span", { text: status })));
  root.append(el("div", { class: "row" },
    el("button", { class: "primary", text: "Проверить сейчас", disabled: !a.ready || !!run.review_running, onclick: async () => { try { await rpc("assist_review"); await renderAssist(true); } catch (e) { fail(e); } } }),
    a.failed > 0 ? el("button", { class: "secondary", text: "Повторить после ошибок (" + a.failed + ")", disabled: !a.ready || !!run.review_running, onclick: async () => { try { await rpc("assist_retry_failed"); await renderAssist(true); } catch (e) { fail(e); } } }) : null,
    el("span", { class: "note", text: a.unlimited ? "Запросов сегодня: " + a.used + " (лимит снят)" : "Запросов сегодня: " + a.used + " из " + a.limit })));
  const bg = el("input", { type: "checkbox", onchange: async (e) => { try { await rpc("setting_set", { key: "assistant.background", value: e.target.checked ? "1" : "0" }); toast(e.target.checked ? "Фоновая проверка включена" : "Фоновая проверка выключена"); } catch (er) { fail(er); } } });
  bg.checked = a.background;
  const unl = el("input", { type: "checkbox", onchange: async (e) => {
    if (e.target.checked && !(await confirmBox("Снять дневной лимит?", "Без лимита помощник может отправлять сколько угодно платных запросов проверки и выжимки.", "Снять"))) { e.target.checked = false; return; }
    try { await rpc("setting_set", { key: "ai.unlimited", value: e.target.checked ? "1" : "0" }); await renderAssist(true); } catch (er) { fail(er); }
  } });
  unl.checked = a.unlimited;
  root.append(el("div", { class: "row" }, el("label", { class: "toggle" }, bg, "Проверять новые и изменённые заметки в фоне (раз в 6 часов, пока приложение открыто)")),
    el("div", { class: "row" }, el("label", { class: "toggle" }, unl, "Снять дневной лимит (" + a.limit + " запроса)")));

  // Вопросы о планах
  root.append(el("h2", { text: "Вопросы о планах" }));
  root.append(el("div", { class: "note", text: "Помощник сам пишет в ИИ-чат: спрашивает, что у вас запланировано, и записывает итог заметкой. Если не отвечать на три вопроса подряд, он замолчит, пока вы не ответите." }));
  const ci = a.checkin;
  const on = el("input", { type: "checkbox", onchange: async (e) => { try { await rpc("checkin_set", { on: e.target.checked }); await renderAssist(true); } catch (er) { fail(er); } } });
  on.checked = ci.on;
  const time = el("input", { type: "time", class: "field", style: "min-width:0", value: hhmm(ci.minutes), onchange: async (e) => {
    const [h, m] = (e.target.value || "18:00").split(":").map(Number);
    try { await rpc("checkin_set", { on: ci.on, minutes: h * 60 + m }); await renderAssist(true); } catch (er) { fail(er); }
  } });
  const every = el("select", { class: "field", style: "min-width:0", onchange: async (e) => { try { await rpc("checkin_set", { on: ci.on, every: +e.target.value }); await renderAssist(true); } catch (er) { fail(er); } } },
    EVERY.map(([v, t]) => el("option", { value: String(v), text: t })));
  every.value = String(ci.every);
  root.append(el("div", { class: "row" }, el("label", { class: "toggle" }, on, "Задавать вопросы"), ci.on ? [el("span", { text: "в" }), time, every] : null,
    el("button", { class: "secondary", text: "Спросить сейчас", disabled: !a.ready || !!run.checkin_running, onclick: async () => { try { await rpc("checkin_now"); toast("Помощник готовит вопрос…"); await renderAssist(true); } catch (e) { fail(e); } } })));
  const ciNote = ci.paused ? "Помощник замолчал: вы не отвечали на последние вопросы. Ответьте на любой из них в чате или нажмите «Спросить сейчас»."
    : ci.on && ci.next > 0 ? "Следующий вопрос: " + fmtDate(ci.next) + (ci.status ? ". " + ci.status : "") : ci.status;
  if (ciNote) root.append(el("div", { class: "note", text: ciNote }));
  if (run.checkin_running) root.append(el("div", { class: "status-line" }, el("div", { class: "spinner" }), "Помощник составляет вопрос…"));

  // Напоминания, найденные в заметках
  if (a.suggestions.length) {
    root.append(el("h2", { text: "Напоминания из заметок (" + a.suggestions.length + ")" }));
    for (const s of a.suggestions) {
      root.append(el("div", { class: "offer assist-card" },
        el("div", { class: "kind plain", text: "Поставить напоминание на " + fmtDate(s.at) + "?" }),
        el("div", { class: "why", text: s.reason }),
        el("div", { class: "chips" }, el("button", { class: "chip", text: s.title, onclick: () => goNote(s.id) })),
        el("div", { class: "btns" },
          el("button", { class: "compact main", text: "Поставить", onclick: async () => { try { await rpc("suggestion_accept", { id: s.id }); toast("Напоминание поставлено"); await afterAssistChange(); } catch (e) { fail(e); await renderAssist(true); } } }),
          el("button", { class: "compact", text: "Скрыть", onclick: async () => { try { await rpc("suggestion_dismiss", { id: s.id }); await afterAssistChange(); } catch (e) { fail(e); } } }))));
    }
  }

  // Предложения менеджера
  if (a.proposals.length) {
    root.append(el("h2", { text: "Предложения по заметкам (" + a.proposals.length + ")" }));
    for (const p of a.proposals) root.append(proposalCard(p, a));
  }

  // Папки по темам
  if (a.folders.length) {
    root.append(el("h2", { text: "Папки по темам" }));
    root.append(el("div", { class: "note", text: "Темы взяты из подсказок помощника. Папка создаётся только после вашего согласия." }));
    for (const f of a.folders) {
      root.append(el("div", { class: "offer assist-card" },
        el("div", { class: "kind plain", text: (f.existing ? "Перенести в папку «" : "Создать папку «") + f.name + "»" }),
        el("div", { class: "why", text: "Заметок: " + f.notes.length }),
        el("div", { class: "btns" },
          el("button", { class: "compact main", text: f.existing ? "Перенести" : "Создать и перенести", onclick: async () => {
            try { const r = await rpc("folder_suggestion_accept", { name: f.name, notes: f.notes }); toast("Перенесено заметок: " + r.moved); await afterAssistChange(); } catch (e) { fail(e); }
          } }),
          el("button", { class: "compact", text: "Скрыть тему", onclick: async () => { try { await rpc("folder_suggestion_dismiss", { key: f.key }); await renderAssist(true); } catch (e) { fail(e); } } }))));
    }
  }

  // История
  if (a.log.length) {
    root.append(el("h2", { text: "История" }));
    const det = el("details", {}, el("summary", { class: "note", text: "Последние записи (" + a.log.length + ")" }));
    for (const l of a.log) det.append(el("div", { class: "assist-log" }, el("b", { text: fmtDate(l.time) + " · " }), l.text));
    root.append(det);
  }
}

function proposalCard(p, a) {
  const card = el("div", { class: "offer assist-card" });
  card.append(el("div", { class: "kind" + (p.type === "task" || p.type === "question" ? " plain" : ""), text: KINDS[p.type] || p.type }), el("div", { class: "why", text: p.reason }));
  card.append(el("div", { class: "chips" }, (p.source_notes || []).map((s) => el("button", { class: "chip", title: s.title, text: s.title, onclick: () => goNote(s.id) }))));
  const dismiss = el("button", { class: "compact", text: "Скрыть", onclick: async () => { try { await rpc("proposal_dismiss", { id: p.id }); await renderAssist(true); } catch (e) { fail(e); } } });
  const running = a.running.summary_running === p.id;
  const sum = p._summary;
  let draftTitle = p.title, draftBody = p.body, canCreate = p.type !== "link";
  if (p.type === "link") {
    if (sum && sum.ready) { draftTitle = sum.title; draftBody = sum.body; canCreate = true; }
    else {
      card.append(el("div", { class: "chat-note", text: running ? "Готовим выжимку… обработано фрагментов: " + ((sum && sum.cursor) || 0) : sum ? "Выжимка не закончена: обработано фрагментов " + sum.cursor + ". Готовая часть сохранена." : "Выжимка стоит платных запросов (по одному на несколько тысяч знаков). Из исходных заметок получится одна стопка." }));
      if (a.running.summary_error_id === p.id) card.append(el("div", { class: "chat-error", text: a.running.summary_error }));
      card.append(el("div", { class: "btns" },
        el("button", { class: "compact main", disabled: running || !a.ready, text: running ? "Готовим…" : sum ? "Продолжить выжимку" : "Подготовить выжимку", onclick: async () => { try { await rpc("proposal_summary", { id: p.id }); await renderAssist(true); } catch (e) { fail(e); } } }), dismiss));
      return card;
    }
  }
  const title = el("input", { value: draftTitle || "", "aria-label": "Название заметки" });
  const body = el("textarea", { "aria-label": "Текст заметки" }); body.value = draftBody || "";
  const stackNote = p.type === "merge" || p.type === "link" ? "Исходные заметки уйдут в стопку под новой; в списке останется итог." : "";
  card.append(title, body, stackNote ? el("div", { class: "chat-note", style: "margin-top:4px", text: stackNote }) : null,
    el("div", { class: "btns" },
      el("button", { class: "compact main", text: p.type === "merge" || p.type === "link" ? "Создать стопку" : "Сохранить заметку", disabled: !canCreate, onclick: async () => {
        try { await rpc("proposal_accept", { id: p.id, title: title.value, body: body.value }); toast("Заметка создана"); await afterAssistChange(); } catch (e) { fail(e); await renderAssist(true); }
      } }), dismiss));
  return card;
}

// ---------- настройки ИИ ----------
function renderAiSettings(root) {
  const ai = S.ai;
  root.append(el("h2", { text: "ИИ (Polza.AI)" }));
  root.append(el("div", { class: "note", text: "Чат отвечает по вашим заметкам через облако Polza.AI; запросы оплачиваются с вашего баланса. Ключ хранится в системном хранилище (Диспетчер учётных данных Windows или Связка ключей macOS), не попадает в экспорт и не передаётся на телефон." }));
  const key = el("input", { class: "field grow", type: "password", autocomplete: "off", placeholder: ai.hasKey ? "Ключ сохранён · введите новый, чтобы заменить" : "Ключ API Polza.AI" });
  root.append(el("div", { class: "row" }, key,
    el("button", { class: "secondary", text: "Сохранить ключ", onclick: async () => {
      if (!key.value.trim()) { toast("Введите ключ", true); return; }
      try { await rpc("ai_key_set", { key: key.value }); key.value = ""; toast("Ключ сохранён"); await loadAi(); renderSettings(); } catch (e) { fail(e); }
    } }),
    ai.hasKey ? el("button", { class: "danger", text: "Удалить ключ", onclick: async () => {
      if (await confirmBox("Удалить ключ?", "Чат перестанет работать, пока вы не введёте ключ снова.", "Удалить")) { await rpc("ai_key_set", { key: "" }); await loadAi(); renderSettings(); }
    } }) : null));
  const balance = el("span", { class: "note" });
  if (ai.hasKey) root.append(el("div", { class: "row" }, el("button", { class: "secondary", text: "Проверить баланс", onclick: async () => {
    balance.textContent = "Проверяю…";
    try { const b = await rpc("ai_balance"); balance.textContent = "Доступно " + b.available + " ₽ из " + b.amount + " ₽"; } catch (e) { balance.textContent = ""; fail(e); }
  } }), balance));
  const list = el("datalist", { id: "ai-models" });
  const model = el("input", { class: "field grow", list: "ai-models", value: ai.model, placeholder: "Например, openai/gpt-4o-mini" });
  const image = el("input", { class: "field grow", list: "ai-models", value: ai.imageModel, placeholder: ai.defaultImageModel });
  const video = el("input", { class: "field grow", list: "ai-models", value: ai.videoModel, placeholder: ai.defaultVideoModel });
  const save = async (k, v) => { try { await rpc("setting_set", { key: k, value: v.trim() }); await loadAi(); toast("Сохранено"); } catch (e) { fail(e); } };
  model.addEventListener("change", () => save("ai.model", model.value));
  image.addEventListener("change", () => save("ai.imageModel", image.value));
  video.addEventListener("change", () => save("ai.videoModel", video.value));
  root.append(list,
    el("div", { class: "row" }, el("span", { text: "Модель чата" }), model,
      ai.hasKey ? el("button", { class: "secondary", text: "Загрузить список", onclick: async () => {
        try { const ids = await rpc("ai_models"); list.replaceChildren(...ids.map((id) => el("option", { value: id }))); toast("Моделей: " + ids.length + ". Начните вводить название."); } catch (e) { fail(e); }
      } }) : null),
    el("div", { class: "row" }, el("span", { text: "Модель для картинок" }), image),
    el("div", { class: "note", text: "Если поле пустое — " + ai.defaultImageModel + ". Запросы с картинками идут этой моделью весь остаток чата, они дороже обычных." }),
    el("div", { class: "row" }, el("span", { text: "Модель для видео YouTube" }), video),
    el("div", { class: "note", text: "Если поле пустое — " + ai.defaultVideoModel + ". Модель сама смотрит ролик по ссылке, поэтому нужна модель Gemini; ролик на 10 минут обходится примерно в 2 ₽. Самые новые модели могут резервировать крупную сумму и отвечать «недостаточно средств»: тогда оставьте поле пустым." }));
  const tokens = el("select", { class: "field", onchange: (e) => save("ai.maxTokens", e.target.value) },
    [["300", "Короткие (300 токенов)"], ["600", "Обычные (600)"], ["1000", "Подробные (1000)"], ["2000", "Длинные (2000)"], ["4000", "Очень длинные (4000)"]].map(([v, t]) => el("option", { value: v, text: t })));
  tokens.value = String(ai.maxTokens);
  if (tokens.value !== String(ai.maxTokens)) tokens.append(el("option", { value: String(ai.maxTokens), text: ai.maxTokens + " токенов" })), tokens.value = String(ai.maxTokens);
  root.append(el("div", { class: "row" }, el("span", { text: "Длина ответа" }), tokens));
}

// ---------- календарь и сработавшие напоминания ----------
// Календарь показывает напоминания заметок по дням (серии развёрнуты на видимый месяц). Уведомления
// показывает система (оболочка), а здесь список сработавших: открыть, отложить, закрыть.
const DOW = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"];
const dayStart = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate());
const dayKey = (d) => d.getFullYear() * 10000 + (d.getMonth() + 1) * 100 + d.getDate();
const fmtTime = (ms) => new Date(ms).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
function updateCalBadge() {
  const b = $("#cal-badge"); if (!b) return;
  b.textContent = String(S.fired.length); b.classList.toggle("hidden", !S.fired.length);
}
async function snoozeFired(id, kind) {
  try { await rpc("reminder_snooze", { id, kind }); toast("Отложено"); await refreshFired(true); } catch (e) { fail(e); }
}
async function dismissFired(id) {
  try { await rpc("reminder_dismiss", { id }); await refreshFired(true); } catch (e) { fail(e); }
}
/** Забирает список сработавших; обновляет значок и, если открыт календарь, перерисовывает его. */
async function refreshFired(force = false) {
  const list = await rpc("reminders_active");
  const sig = JSON.stringify(list.map((f) => [f.id, f.reminder]));
  const changed = sig !== S.firedSig;
  S.fired = list; S.firedSig = sig; updateCalBadge();
  if ((changed || force) && S.view === "calendar") await renderCalendar();
}
function firedPanel() {
  if (!S.fired.length) return null;
  const box = el("div", { class: "fired" }, el("h2", { text: "Сработало: " + S.fired.length }));
  for (const f of S.fired) {
    box.append(el("div", { class: "fired-item" },
      el("div", { class: "what" }, el("b", { text: f.title }), el("span", { text: f.line })),
      el("button", { class: "compact main", text: "Открыть", onclick: () => goNote(f.id) }),
      el("button", { class: "compact", text: "Через 10 мин", onclick: () => snoozeFired(f.id, 0) }),
      el("button", { class: "compact", text: "Через час", onclick: () => snoozeFired(f.id, 1) }),
      el("button", { class: "compact", text: "Завтра", onclick: () => snoozeFired(f.id, 2) }),
      el("button", { class: "compact", text: "Готово", onclick: () => dismissFired(f.id) })));
  }
  return box;
}
async function newReminderOn(day) {
  S.view = "notes"; showView();
  await newNote();
  if (!S.draft) return;
  const now = new Date(), d = new Date(day.getFullYear(), day.getMonth(), day.getDate(), 9, 0);
  // На сегодня после девяти: ближайший час, чтобы напоминание не оказалось в прошлом.
  S.draft.reminder = (d.getTime() > now.getTime() ? d : new Date(now.getTime() + 3600000 - (now.getTime() % 3600000))).getTime();
  markDirty(); renderEditor();
}
async function renderCalendar() {
  const root = $("#calendar");
  const today = dayStart(new Date());
  if (!S.cal) S.cal = { month: new Date(today.getFullYear(), today.getMonth(), 1), day: today };
  const first = S.cal.month;
  const gridStart = new Date(first.getFullYear(), first.getMonth(), 1 - ((first.getDay() + 6) % 7));
  const gridEnd = new Date(gridStart.getFullYear(), gridStart.getMonth(), gridStart.getDate() + 42);
  const events = await rpc("calendar", { from: gridStart.getTime(), to: gridEnd.getTime() });
  if (S.view !== "calendar") return;
  const byDay = new Map();
  for (const e of events) { const k = dayKey(new Date(e.at)); if (!byDay.has(k)) byDay.set(k, []); byDay.get(k).push(e); }
  const go = (delta) => { S.cal.month = new Date(first.getFullYear(), first.getMonth() + delta, 1); renderCalendar().catch(fail); };
  const head = el("div", { class: "cal-head" },
    el("h1", { text: first.toLocaleString("ru-RU", { month: "long", year: "numeric" }) }),
    el("button", { class: "icon-btn", title: "Прошлый месяц", "aria-label": "Прошлый месяц", onclick: () => go(-1) }, "‹"),
    el("button", { class: "icon-btn", title: "Следующий месяц", "aria-label": "Следующий месяц", onclick: () => go(1) }, "›"),
    el("button", { class: "secondary", text: "Сегодня", onclick: () => { S.cal = null; renderCalendar().catch(fail); } }));
  const grid = el("div", { class: "cal-grid" }, DOW.map((d) => el("div", { class: "cal-dow", text: d })));
  for (let i = 0; i < 42; i++) {
    const d = new Date(gridStart.getFullYear(), gridStart.getMonth(), gridStart.getDate() + i);
    const list = byDay.get(dayKey(d)) || [];
    const cell = el("button", { class: "cal-day" + (d.getMonth() !== first.getMonth() ? " other" : "") + (dayKey(d) === dayKey(today) ? " today" : "") + (dayKey(d) === dayKey(S.cal.day) ? " sel" : ""),
      onclick: () => { S.cal.day = d; renderCalendar().catch(fail); }, ondblclick: () => newReminderOn(d) }, el("span", { class: "n", text: String(d.getDate()) }));
    for (const e of list.slice(0, 3)) cell.append(el("span", { class: "ev" + (e.repeat ? " series" : ""), title: e.title, text: fmtTime(e.at) + " " + e.title }));
    if (list.length > 3) cell.append(el("span", { class: "more", text: "и ещё " + (list.length - 3) }));
    grid.append(cell);
  }
  const sel = S.cal.day, items = byDay.get(dayKey(sel)) || [];
  const side = el("div", { class: "cal-side" }, el("h2", { text: sel.toLocaleString("ru-RU", { weekday: "long", day: "numeric", month: "long" }) }));
  if (!items.length) side.append(el("div", { class: "note", text: "На этот день напоминаний нет." }));
  for (const e of items) {
    side.append(el("button", { class: "cal-item", onclick: () => goNote(e.id) },
      el("span", { class: "time", text: fmtTime(e.at) }), el("span", { class: "t", text: e.title }),
      e.repeat ? el("span", { class: "tag", text: REPEATS.find(([v]) => v === String(e.repeat))?.[1] || "Повтор" }) : null));
  }
  side.append(el("div", { class: "row" }, el("button", { class: "primary", text: "Добавить напоминание", onclick: () => newReminderOn(sel) })),
    el("div", { class: "note", text: "Двойной щелчок по дню тоже создаёт заметку с напоминанием. Повторяющиеся события развёрнуты вперёд от ближайшего срока." }));
  root.replaceChildren(...[firedPanel(), head, el("div", { class: "cal-body" }, grid, side)].filter(Boolean));
}

// ---------- настройки и синхронизация ----------
async function renderSettings() {
  const st = await rpc("sync_state"); S.sync = st;
  await loadAi();
  const theme = (await rpc("setting_get", { key: "theme" })) || "system";
  const root = $("#settings"); root.replaceChildren();
  root.append(el("h1", { text: "Настройки" }));
  renderAiSettings(root);

  root.append(el("h2", { text: "Синхронизация" }));
  root.append(el("div", { class: "note", text: "Заметки, папки и картинки обмениваются с телефоном и другими компьютерами напрямую по Wi-Fi, без сервера. Устройства должны быть в одной сети." }));
  const tgl = el("input", { type: "checkbox", onchange: async (e) => { try { await rpc("sync_enable", { on: e.target.checked }); S.code = null; await renderSettings(); } catch (er) { fail(er); e.target.checked = !e.target.checked; } } });
  tgl.checked = st.enabled;
  root.append(el("div", { class: "row" }, el("label", { class: "toggle" }, tgl, "Включить синхронизацию на этом компьютере")));
  const nameInput = el("input", { class: "field", value: st.name });
  root.append(el("div", { class: "row" }, el("span", { text: "Имя устройства" }), nameInput,
    el("button", { class: "secondary", text: "Сохранить имя", onclick: async () => { try { await rpc("device_rename", { name: nameInput.value }); toast("Имя сохранено"); } catch (e) { fail(e); } } })));
  if (st.enabled) {
    root.append(el("div", { class: "note", text: st.running ? `Компьютер доступен по адресам: ${st.hosts.join(", ") || "нет сети"}` : "Сервер не запущен" }));
    root.append(el("h2", { text: "Подключить телефон или другой компьютер" }));
    const codeBox = el("div", { id: "code-box" });
    root.append(el("div", { class: "row" },
      el("button", { class: "primary", text: "Показать код сопряжения", onclick: showCode }),
      el("span", { class: "note", text: "Код действует 10 минут и срабатывает один раз." })), codeBox);
    if (S.code) paintCode(codeBox);
    const paste = el("input", { class: "field grow", placeholder: "mimohodom://pair?…" });
    root.append(el("div", { class: "note", style: "margin-top:12px", text: "Или введите код, показанный на другом компьютере:" }),
      el("div", { class: "row" }, paste, el("button", { class: "secondary", text: "Сопрячь", onclick: async () => {
        try { const n = await rpc("sync_accept_code", { text: paste.value }); toast("Подключаюсь к «" + n + "»…"); paste.value = ""; setTimeout(renderSettings, 1500); } catch (e) { fail(e); }
      } })));
  }
  root.append(el("h2", { text: "Устройства" }));
  if (!st.peers.length) root.append(el("div", { class: "note", text: "Пока нет сопряжённых устройств." }));
  for (const p of st.peers) {
    root.append(el("div", { class: "peer" },
      el("div", { class: "grow", style: "flex:1" }, el("div", { class: "name", text: p.name }),
        el("div", { class: "note", text: p.last_sync ? "Последний обмен: " + fmtDate(p.last_sync) : "Обмена ещё не было" })),
      p.hosts.length ? el("button", { class: "secondary", text: "Обменяться", onclick: () => rpc("sync_now", { device: p.device }).then(() => toast("Обмен запущен")).catch(fail) }) : null,
      el("button", { class: "danger", text: "Забыть", onclick: async () => {
        if (await confirmBox("Забыть устройство?", "Чтобы подключить его снова, понадобится новый код сопряжения.", "Забыть")) { await rpc("sync_remove_peer", { device: p.device }); renderSettings(); }
      } })));
  }
  if (st.peers.some((p) => p.hosts.length)) root.append(el("div", { class: "row" }, el("button", { class: "primary", text: "Обменяться со всеми", onclick: () => rpc("sync_now", {}).then(() => toast("Обмен запущен")).catch(fail) })));
  if (st.status) root.append(el("div", { class: "note", style: "white-space:pre-line", text: st.status }));

  await renderReminderSettings(root);

  root.append(el("h2", { text: "Оформление" }));
  const sel = el("select", { class: "field", onchange: async (e) => { await rpc("setting_set", { key: "theme", value: e.target.value }); applyTheme(e.target.value); } },
    el("option", { value: "system", text: "Как в системе" }), el("option", { value: "light", text: "Светлая" }), el("option", { value: "dark", text: "Тёмная" }));
  sel.value = theme;
  root.append(el("div", { class: "row" }, el("span", { text: "Тема" }), sel));

  root.append(el("h2", { text: "Данные" }));
  root.append(el("div", { class: "row" }, el("button", { class: "secondary", text: "Экспорт заметок в JSON", onclick: exportJson }),
    el("span", { class: "note", text: "Ключ ИИ и настройки в экспорт не попадают." })));
  root.append(el("h2", { text: "Горячие клавиши" }), el("div", { class: "keys" }, HOTKEYS.map(([k, t]) => el("div", { class: "key-row" }, el("kbd", { text: k }), el("span", { text: t })))));
  root.append(el("div", { class: "note", style: "margin-top:24px", text: "Мимоходом 0.10.5.7 для компьютера (Android — 0.10.5). Заметки, папки, картинки, чаты и стопки обмениваются с телефоном." }));
}
async function renderReminderSettings(root) {
  const get = async (k) => rpc("setting_get", { key: k }).catch(() => null);
  const popup = (await get("reminder.popup")) !== "0", tray = (await get("app.tray")) !== "0", lead = (await get("reminder.lead")) || "0";
  root.append(el("h2", { text: "Напоминания" }));
  root.append(el("div", { class: "note", text: "Уведомления приходят, пока приложение запущено; при закрытии окно уходит в трей, и напоминания продолжают работать. Время срабатывания и «за N минут» считает этот компьютер; сработавшие и отложенные напоминания на телефон не передаются." }));
  const mk = (label, on, apply) => {
    const c = el("input", { type: "checkbox", onchange: async (e) => { try { await apply(e.target.checked); } catch (er) { fail(er); e.target.checked = !e.target.checked; } } });
    c.checked = on; return el("div", { class: "row" }, el("label", { class: "toggle" }, c, label));
  };
  root.append(mk("Показывать уведомления о напоминаниях", popup, (v) => rpc("setting_set", { key: "reminder.popup", value: v ? "1" : "0" })));
  const sel = el("select", { class: "field", onchange: (e) => rpc("setting_set", { key: "reminder.lead", value: e.target.value }).catch(fail) },
    LEADS.filter(([v]) => v !== "-1").map(([v, t]) => el("option", { value: v, text: t })));
  sel.value = lead; if (sel.value !== lead) sel.value = "0";
  root.append(el("div", { class: "row" }, el("span", { text: "Напоминать (если у заметки не задано своё)" }), sel));
  root.append(mk("Закрытие окна сворачивает в трей (иначе приложение закрывается)", tray, (v) => rpc("setting_set", { key: "app.tray", value: v ? "1" : "0" })));
  if (tauri && tauri.core) {
    let auto = false;
    try { auto = await tauri.core.invoke("autostart_get"); } catch (_) { /* не поддерживается */ }
    root.append(mk("Запускать при входе в систему (сразу в трей)", auto, (v) => tauri.core.invoke("autostart_set", { on: v })));
  }
  const popupError = await get("reminder.popup_error");
  if (popupError) root.append(el("div", { class: "chat-error", style: "margin:6px 0" }, "Система не показала последнее уведомление: " + popupError));
  root.append(el("div", { class: "row" }, el("button", { class: "secondary", text: "Проверочное уведомление", onclick: () => rpc("reminder_test").then(() => toast("Отправлено. Если ничего не появилось, проверьте уведомления в настройках системы.")).catch(fail) })));
}
async function showCode() {
  try { S.code = await rpc("sync_pair_code"); paintCode($("#code-box")); } catch (e) { fail(e); }
}
function paintCode(box) {
  box.replaceChildren();
  if (!S.code) return;
  const qr = el("div", { class: "qr" }); qr.innerHTML = S.code.svg;
  box.append(el("div", { class: "row" }, qr), el("div", { class: "note", text: "На телефоне: Настройки → Синхронизация → Подключить компьютер, затем наведите камеру на код или вставьте строку ниже." }),
    el("div", { class: "code", text: S.code.uri }),
    el("div", { class: "row" }, el("button", { class: "secondary", text: "Копировать строку", onclick: () => navigator.clipboard.writeText(S.code.uri).then(() => toast("Скопировано")).catch(fail) }),
      el("button", { class: "danger", text: "Скрыть код", onclick: async () => { await rpc("sync_cancel_code"); S.code = null; box.replaceChildren(); } })));
}
async function exportJson() {
  try {
    const name = "mimohodom-" + new Date().toISOString().slice(0, 10) + ".json";
    if (tauri && tauri.dialog) {
      const path = await tauri.dialog.save({ defaultPath: name, filters: [{ name: "JSON", extensions: ["json"] }] });
      if (!path) return;
      await rpc("export_to", { path }); toast("Экспорт сохранён");
      return;
    }
    const text = await rpc("export");
    const a = el("a", { href: URL.createObjectURL(new Blob([text], { type: "application/json" })), download: name });
    document.body.append(a); a.click(); a.remove();
  } catch (e) { fail(e); }
}
function applyTheme(t) {
  if (t === "light" || t === "dark") document.documentElement.dataset.theme = t; else delete document.documentElement.dataset.theme;
}

// ---------- состояние синхронизации в боковой панели ----------
async function pollSync() {
  try {
    const st = await rpc("sync_state"); S.sync = st;
    const pill = $("#sync-pill");
    pill.className = "pill" + (st.enabled ? " on" : "");
    pill.textContent = st.busy ? "Синхронизация…" : st.enabled ? (st.peers.length ? "Синхронизация включена\nустройств: " + st.peers.length : "Синхронизация включена") : "Синхронизация выключена";
    const latest = Math.max(0, ...st.peers.map((p) => p.last_sync));
    if (latest !== S.lastPeerSync || st.status !== S.lastStatus) {
      const first = S.lastPeerSync === 0 && S.lastStatus === "";
      S.lastPeerSync = latest; S.lastStatus = st.status;
      if (!first && !st.busy) {
        if (!S.dirty) { await reload(); if (S.draft && S.draft.id) refreshOpen(); }
        if (S.view === "settings") renderSettings();
        if (S.view === "calendar") renderCalendar().catch(fail);
      }
    }
  } catch (_) { /* окно закрывается или ядро занято */ }
  try { await refreshFired(); } catch (_) { /* то же */ }
  try {
    const b = await rpc("assist_badge");
    const count = b.suggestions + b.proposals, badge = $("#assist-badge");
    badge.textContent = String(count); badge.classList.toggle("hidden", !count);
    // Помощник мог переименовать заметки или найти даты: обновляем список, когда в истории появилась новая запись.
    if (S.assistLog !== null && b.status !== S.assistLog && !S.dirty) await reload();
    S.assistLog = b.status;
    if (S.view === "assistant") await renderAssist();
  } catch (_) { /* то же */ }
}
async function refreshOpen() {
  const n = await rpc("note", { id: S.draft.id });
  if (!n) { closeEditor(); return; }
  if (!S.dirty && n.sync_ts !== S.draft.stamp) {
    S.draft = { id: n.id, title: n.title_auto ? "" : n.title, body: n.body, reminder: n.reminder, repeat: n.repeat, lead: n.lead, images: [...n.images], folder: n.folder, updated: n.updated, stamp: n.sync_ts, suggestion: n.suggestion || 0, reason: n.suggestion_reason || "", stack: n.stack };
    renderEditor();
  }
}

// ---------- горячие клавиши ----------
// Сочетания сравниваются по коду клавиши (KeyS), а не по символу: так они работают и на русской раскладке.
const VIEW_KEYS = { Digit1: "notes", Digit2: "chat", Digit3: "assistant", Digit4: "settings", Digit5: "calendar", Numpad1: "notes", Numpad2: "chat", Numpad3: "assistant", Numpad4: "settings", Numpad5: "calendar" };
const HOTKEYS = [
  ["Esc", "закрыть окно; очистить поиск; закрыть открытую заметку (несохранённые правки сохранятся)"],
  ["Ctrl+S", "сохранить заметку"],
  ["Ctrl+N", "новая заметка"],
  ["Ctrl+F или Ctrl+K", "поиск по заметкам"],
  ["Ctrl+1 … Ctrl+5", "заметки, ИИ-чат, помощник, настройки, календарь"],
  ["Enter в чате", "отправить сообщение (Shift+Enter — новая строка)"],
];
/** Esc закрывает самое верхнее: окно, затем поиск, затем открытую заметку. */
async function onEscape(e) {
  if (S.modalClose) { e.preventDefault(); S.modalClose(null); return; }
  const search = $("#search");
  if (document.activeElement === search) {
    e.preventDefault();
    if (search.value) { search.value = ""; S.query = ""; await loadNotes(); renderList(); } else search.blur();
    return;
  }
  if (S.view === "notes" && S.draft) {
    e.preventDefault();
    if (await leaveEditor()) closeEditor();
  }
}
function onHotkey(e) {
  if (e.isComposing || e.altKey) return;
  if (e.key === "Escape") { onEscape(e).catch(fail); return; }
  if (!(e.ctrlKey || e.metaKey) || S.modalClose) return;
  const k = e.code;
  if (k === "KeyS") { e.preventDefault(); if (S.draft) saveDraft().catch(fail); }
  else if (k === "KeyN") { e.preventDefault(); S.view = "notes"; showView(); newNote(); }
  else if (k === "KeyF" || k === "KeyK") { e.preventDefault(); S.view = "notes"; showView(); $("#search").focus(); $("#search").select(); }
  else if (VIEW_KEYS[k]) { e.preventDefault(); document.querySelector('.nav[data-view="' + VIEW_KEYS[k] + '"]').click(); }
}

// ---------- запуск ----------
function showView() {
  for (const v of ["notes", "calendar", "chat", "assistant", "settings"]) $("#view-" + v).classList.toggle("hidden", S.view !== v);
  renderSide();
  if (S.view === "settings") renderSettings().catch(fail);
  if (S.view === "calendar") refreshFired(true).catch(fail);
  if (S.view === "chat") renderChatView().catch(fail);
  if (S.view === "assistant") renderAssist(true).catch(fail);
}
async function init() {
  applyTheme((await rpc("setting_get", { key: "theme" }).catch(() => null)) || "system");
  S.grid = (await rpc("setting_get", { key: "grid" }).catch(() => null)) === "1";
  for (const b of document.querySelectorAll(".nav")) b.addEventListener("click", async () => {
    if (!(await leaveEditor())) return;
    S.view = b.dataset.view; showView();
  });
  $("#new-note").addEventListener("click", newNote);
  watchVideo();
  $("#new-chat").addEventListener("click", () => newChat());
  $("#new-folder").addEventListener("click", async () => {
    const name = await askText("Новая папка", "", "Создать");
    if (!name) return;
    try { const id = await rpc("create_folder", { name }); S.folder = id; await reload(); } catch (e) { fail(e); }
  });
  $("#toggle-layout").addEventListener("click", async () => { S.grid = !S.grid; await rpc("setting_set", { key: "grid", value: S.grid ? "1" : "0" }); renderList(); });
  let timer;
  $("#search").addEventListener("input", (e) => { clearTimeout(timer); timer = setTimeout(async () => { S.query = e.target.value; await loadNotes(); renderList(); }, 180); });
  $("#sync-pill").addEventListener("click", () => { S.view = "settings"; showView(); });
  document.addEventListener("keydown", onHotkey);
  rpc("app_version").then((v) => { $("#app-version").textContent = "Версия " + v; }).catch(() => {});
  showView(); renderEditor(); await reload(); await pollSync();
  setInterval(pollSync, 3000);
}
init().catch(fail);
