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
    const close = (v) => { m.classList.add("hidden"); m.replaceChildren(); resolve(v); };
    m.replaceChildren(el("div", { class: "box" }, build(close)));
    m.classList.remove("hidden");
    m.onclick = (ev) => { if (ev.target === m) close(null); };
  });
}
function askText(title, initial = "", ok = "Готово") {
  return modal((close) => {
    const input = el("input", { class: "field", value: initial, style: "width:100%" });
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") close(input.value); if (e.key === "Escape") close(null); });
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
const LEADS = [["-1", "Как в настройках"], ["0", "В момент события"], ["5", "За 5 минут"], ["15", "За 15 минут"], ["30", "За 30 минут"], ["60", "За час"], ["1440", "За день"]];

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
async function loadNotes() {
  S.notes = await rpc("notes", { query: S.query, folder: S.folder });
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
  for (const n of S.notes) {
    const card = el("button", { class: "card" + (S.draft && S.draft.id === n.id ? " active" : ""), onclick: () => openNote(n.id) });
    if (n.images.length) {
      const img = el("img", { class: "thumb", alt: "" });
      imageUrl(n.images[0]).then((u) => { if (u) img.src = u; else img.remove(); });
      card.append(img);
    }
    const meta = el("div", { class: "m" }, el("span", { text: fmtDate(n.updated) }));
    if (n.reminder) meta.append(el("span", { class: "tag", text: "⏰ " + fmtDate(n.reminder) }));
    if (n.images.length > 1) meta.append(el("span", { text: n.images.length + " фото" }));
    card.append(el("div", { class: "t", text: n.title }), el("div", { class: "b", text: n.body }), meta);
    box.append(card);
  }
}

// ---------- редактор ----------
async function openNote(id) {
  if (!(await leaveEditor())) return;
  const n = await rpc("note", { id });
  if (!n) { await reload(); return; }
  S.draft = { id: n.id, title: n.title_auto ? "" : n.title, body: n.body, reminder: n.reminder, repeat: n.repeat, lead: n.lead, images: [...n.images], folder: n.folder, updated: n.updated, stamp: n.sync_ts };
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
  const body = el("textarea", { id: "e-body", placeholder: "Что записать?", oninput: (e) => { d.body = e.target.value; markDirty(); } });
  body.value = d.body;
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
    d.id ? el("button", { class: "danger", text: "Удалить", onclick: async () => {
      if (await confirmBox("Удалить заметку?", "Заметка исчезнет и на синхронизированных устройствах.")) {
        try { await rpc("delete_note", { id: d.id }); closeEditor(); await reload(); } catch (e) { fail(e); }
      }
    } }) : null,
    el("button", { class: "secondary", text: "Закрыть", onclick: async () => { if (await leaveEditor()) closeEditor(); } }),
    el("button", { class: "save", text: "Сохранить", onclick: () => saveDraft().catch(fail) }));
  host.replaceChildren(title, el("hr"), body, el("div", { class: "strip", id: "e-strip" }),
    el("div", { class: "opts" },
      el("button", { class: "secondary", text: "+ Картинка", onclick: () => pick.click() }), pick,
      el("label", {}, "Напомнить", rem, clearRem), el("label", {}, rep), el("label", {}, lead),
      el("label", {}, "Папка", fold)),
    foot);
  syncOpts(); renderStrip(); updateFoot();
  host.onpaste = (e) => { const files = [...(e.clipboardData?.files || [])].filter((f) => f.type.startsWith("image/")); if (files.length) { e.preventDefault(); addImages(files); } };
  host.ondragover = (e) => e.preventDefault();
  host.ondrop = (e) => { e.preventDefault(); addImages([...e.dataTransfer.files]); };
}

// ---------- настройки и синхронизация ----------
async function renderSettings() {
  const st = await rpc("sync_state"); S.sync = st;
  const theme = (await rpc("setting_get", { key: "theme" })) || "system";
  const root = $("#settings"); root.replaceChildren();
  root.append(el("h1", { text: "Настройки" }));

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

  root.append(el("h2", { text: "Оформление" }));
  const sel = el("select", { class: "field", onchange: async (e) => { await rpc("setting_set", { key: "theme", value: e.target.value }); applyTheme(e.target.value); } },
    el("option", { value: "system", text: "Как в системе" }), el("option", { value: "light", text: "Светлая" }), el("option", { value: "dark", text: "Тёмная" }));
  sel.value = theme;
  root.append(el("div", { class: "row" }, el("span", { text: "Тема" }), sel));

  root.append(el("h2", { text: "Данные" }));
  root.append(el("div", { class: "row" }, el("button", { class: "secondary", text: "Экспорт заметок в JSON", onclick: exportJson }),
    el("span", { class: "note", text: "Ключ ИИ и настройки в экспорт не попадают." })));
  root.append(el("div", { class: "note", style: "margin-top:24px", text: "Мимоходом 0.10.0 для компьютера. ИИ-чат и фоновый помощник появятся в следующем этапе." }));
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
      }
    }
  } catch (_) { /* окно закрывается или ядро занято */ }
}
async function refreshOpen() {
  const n = await rpc("note", { id: S.draft.id });
  if (!n) { closeEditor(); return; }
  if (!S.dirty && n.sync_ts !== S.draft.stamp) {
    S.draft = { id: n.id, title: n.title_auto ? "" : n.title, body: n.body, reminder: n.reminder, repeat: n.repeat, lead: n.lead, images: [...n.images], folder: n.folder, updated: n.updated, stamp: n.sync_ts };
    renderEditor();
  }
}

// ---------- запуск ----------
function showView() {
  for (const v of ["notes", "chat", "settings"]) $("#view-" + v).classList.toggle("hidden", S.view !== v);
  renderSide();
  if (S.view === "settings") renderSettings().catch(fail);
}
async function init() {
  applyTheme((await rpc("setting_get", { key: "theme" }).catch(() => null)) || "system");
  S.grid = (await rpc("setting_get", { key: "grid" }).catch(() => null)) === "1";
  for (const b of document.querySelectorAll(".nav")) b.addEventListener("click", async () => {
    if (!(await leaveEditor())) return;
    S.view = b.dataset.view; showView();
  });
  $("#new-note").addEventListener("click", newNote);
  $("#new-folder").addEventListener("click", async () => {
    const name = await askText("Новая папка", "", "Создать");
    if (!name) return;
    try { const id = await rpc("create_folder", { name }); S.folder = id; await reload(); } catch (e) { fail(e); }
  });
  $("#toggle-layout").addEventListener("click", async () => { S.grid = !S.grid; await rpc("setting_set", { key: "grid", value: S.grid ? "1" : "0" }); renderList(); });
  let timer;
  $("#search").addEventListener("input", (e) => { clearTimeout(timer); timer = setTimeout(async () => { S.query = e.target.value; await loadNotes(); renderList(); }, 180); });
  $("#sync-pill").addEventListener("click", () => { S.view = "settings"; showView(); });
  document.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") { e.preventDefault(); if (S.draft) saveDraft().catch(fail); }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "n") { e.preventDefault(); S.view = "notes"; showView(); newNote(); }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "f") { e.preventDefault(); S.view = "notes"; showView(); $("#search").focus(); }
  });
  showView(); renderEditor(); await reload(); await pollSync();
  setInterval(pollSync, 3000);
}
init().catch(fail);
