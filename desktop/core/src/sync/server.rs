//! Сервер синхронизации (компьютер слушает, телефон и другие компьютеры приходят сами).

use super::crypto::{self, derive_key, request_aad, response_aad};
use super::engine::{self, Record};
use super::{DISCOVERY_PORT, HTTP_PORT, MAX_BODY};
use crate::app::App;
use crate::store::{now_ms, Peer};
use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::io::Read;
use std::net::UdpSocket;
use std::sync::Arc;
use std::thread;

const TS_WINDOW_MS: i64 = 10 * 60 * 1000;
const RID_TTL_MS: i64 = 15 * 60 * 1000;
const MAX_FAILS: u32 = 3;

#[derive(Deserialize)]
struct SyncRequest {
    #[serde(default)]
    name: String,
    #[serde(default)]
    hosts: Vec<String>,
    #[serde(default)]
    since: i64,
    #[serde(default = "default_limit")]
    limit: usize,
    #[serde(default)]
    first: bool,
    #[serde(default)]
    records: Vec<Record>,
}

fn default_limit() -> usize {
    300
}

#[derive(Serialize)]
struct SyncResponse {
    v: i32,
    records: Vec<Value>,
    next: i64,
    more: bool,
    want: Vec<String>,
    name: String,
    device: String,
}

pub fn is_running(app: &Arc<App>) -> bool {
    *app.sync.port.lock().unwrap() != 0
}

/// Запускает сервер и обнаружение. Возвращает порт. Повторный вызов возвращает текущий порт.
pub fn start(app: &Arc<App>, preferred_port: u16) -> Result<u16> {
    {
        let p = *app.sync.port.lock().unwrap();
        if p != 0 {
            return Ok(p);
        }
    }
    let server = match tiny_http::Server::http(("0.0.0.0", preferred_port)) {
        Ok(s) => s,
        Err(_) => tiny_http::Server::http(("0.0.0.0", 0)).map_err(|e| anyhow!("Не удалось открыть порт: {}", e))?,
    };
    let port = server.server_addr().to_ip().map(|a| a.port()).ok_or_else(|| anyhow!("нет порта"))?;
    let server = Arc::new(server);
    *app.sync.port.lock().unwrap() = port;
    *app.sync.server.lock().unwrap() = Some(server.clone());
    let app2 = app.clone();
    thread::spawn(move || {
        for req in server.incoming_requests() {
            let a = app2.clone();
            thread::spawn(move || handle(a, req));
        }
    });
    discovery(app, port);
    Ok(port)
}

pub fn stop(app: &Arc<App>) {
    if let Some(s) = app.sync.server.lock().unwrap().take() {
        s.unblock();
    }
    *app.sync.port.lock().unwrap() = 0;
    *app.sync.pending.lock().unwrap() = None;
}

pub fn default_port() -> u16 {
    HTTP_PORT
}

/// Отвечает на UDP-вопрос «где устройство X?» своим портом (без секретов).
fn discovery(app: &Arc<App>, port: u16) {
    let Ok(sock) = UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)) else { return };
    let _ = sock.set_read_timeout(Some(std::time::Duration::from_millis(500)));
    let app = app.clone();
    thread::spawn(move || {
        let mut buf = [0u8; 256];
        loop {
            if *app.sync.port.lock().unwrap() != port {
                break; // сервер остановлен или перезапущен
            }
            match sock.recv_from(&mut buf) {
                Ok((n, src)) => {
                    let text = String::from_utf8_lossy(&buf[..n]).to_string();
                    if let Some(want) = text.strip_prefix("MIMO?") {
                        let own = app.store.lock().unwrap().device().map(|d| d.0).unwrap_or_default();
                        if want.trim() == own {
                            let _ = sock.send_to(format!("MIMO!{}:{}", own, port).as_bytes(), src);
                        }
                    }
                }
                Err(_) => continue,
            }
        }
    });
}

fn respond(req: tiny_http::Request, code: u16, body: Vec<u8>) {
    let resp = tiny_http::Response::from_data(body).with_status_code(code);
    let _ = req.respond(resp);
}

fn header(req: &tiny_http::Request, name: &str) -> Option<String> {
    req.headers().iter().find(|h| h.field.as_str().as_str().eq_ignore_ascii_case(name)).map(|h| h.value.as_str().to_string())
}

fn handle(app: Arc<App>, mut req: tiny_http::Request) {
    let url = req.url().to_string();
    let method = req.method().as_str().to_string();
    if method == "GET" && url == "/v1/ping" {
        return respond(req, 200, b"mimohodom".to_vec());
    }
    if method != "POST" {
        return respond(req, 404, vec![]);
    }
    let Some(device) = header(&req, "X-Mimo-Device") else { return respond(req, 401, vec![]) };
    if device.len() != 32 || !device.chars().all(|c| c.is_ascii_hexdigit()) {
        return respond(req, 401, vec![]);
    }
    let mut body = Vec::new();
    if req.body_length().map(|l| l > MAX_BODY).unwrap_or(false)
        || req.as_reader().take(MAX_BODY as u64 + 1).read_to_end(&mut body).is_err()
        || body.len() > MAX_BODY
    {
        return respond(req, 400, vec![]);
    }
    let aad = request_aad(&device);
    let (plain, key, new_peer) = match authenticate(&app, &device, &aad, &body) {
        Some(x) => x,
        None => return respond(req, 401, vec![]),
    };
    let Ok(v) = serde_json::from_slice::<Value>(&plain) else { return respond(req, 400, vec![]) };
    // Свежесть и повтор
    let ts = v.get("ts").and_then(|x| x.as_i64()).unwrap_or(0);
    let rid = v.get("rid").and_then(|x| x.as_str()).unwrap_or("").to_string();
    if (now_ms() - ts).abs() > TS_WINDOW_MS || rid.is_empty() || rid.len() > 64 {
        return respond(req, 400, b"time".to_vec());
    }
    {
        let mut rids = app.sync.rids.lock().unwrap();
        let now = now_ms();
        rids.retain(|_, t| now - *t < RID_TTL_MS);
        let id = format!("{}|{}", device, rid);
        if rids.contains_key(&id) {
            return respond(req, 400, b"replay".to_vec());
        }
        rids.insert(id, now);
    }
    if let Some(p) = new_peer {
        // Первый обмен подтвердил одноразовый код: узел сохраняется навсегда.
        let name = v.get("name").and_then(|x| x.as_str()).unwrap_or("Устройство").to_string();
        let hosts: Vec<String> = v
            .get("hosts")
            .and_then(|x| x.as_array())
            .map(|a| a.iter().filter_map(|h| h.as_str().map(|s| s.to_string())).collect())
            .unwrap_or_default();
        let peer = Peer { name, hosts, ..p };
        let _ = app.store.lock().unwrap().save_peer(&peer);
        *app.sync.pending.lock().unwrap() = None;
    }
    let out: Result<Vec<u8>> = match url.as_str() {
        "/v1/sync" => handle_sync(&app, &device, &v),
        "/v1/blob/get" => handle_blob_get(&app, &v),
        "/v1/blob/put" => handle_blob_put(&app, &v),
        _ => return respond(req, 404, vec![]),
    };
    match out {
        Ok(bytes) if bytes.is_empty() && url == "/v1/blob/get" => respond(req, 404, vec![]),
        Ok(bytes) => respond(req, 200, crypto::seal(&key, &response_aad(&device), &bytes)),
        Err(_) => respond(req, 500, vec![]),
    }
}

/// Находит ключ: известный узел или ожидающий код. Для нового узла возвращает заготовку.
fn authenticate(app: &Arc<App>, device: &str, aad: &[u8], body: &[u8]) -> Option<(Vec<u8>, [u8; 32], Option<Peer>)> {
    let known = app.store.lock().unwrap().peer(device).ok().flatten();
    if let Some(p) = known {
        let key = derive_key(&p.secret);
        return crypto::open(&key, aad, body).map(|pl| (pl, key, None));
    }
    let mut pending = app.sync.pending.lock().unwrap();
    let pd = pending.as_mut()?;
    if now_ms() > pd.expires {
        *pending = None;
        return None;
    }
    let key = derive_key(&pd.secret);
    match crypto::open(&key, aad, body) {
        Some(pl) => {
            let peer = Peer {
                device: device.to_string(),
                name: "Устройство".into(),
                platform: String::new(),
                hosts: vec![],
                sent_seq: 0,
                got_seq: 0,
                last_sync: now_ms(),
                secret: pd.secret.clone(),
            };
            Some((pl, key, Some(peer)))
        }
        None => {
            pd.fails += 1;
            if pd.fails >= MAX_FAILS {
                *pending = None;
            }
            None
        }
    }
}

fn handle_sync(app: &Arc<App>, device: &str, v: &Value) -> Result<Vec<u8>> {
    let req: SyncRequest = serde_json::from_value(v.clone())?;
    let (own_device, own_name) = app.store.lock().unwrap().device()?;
    let (records, more, next) = {
        let store = app.store.lock().unwrap();
        engine::apply(&store, device, req.since, &req.records)?;
        if !req.name.is_empty() {
            let _ = store.conn.execute("UPDATE peers SET name=?2,last_sync=?3 WHERE device=?1", rusqlite::params![device, req.name, now_ms()]);
        }
        if !req.hosts.is_empty() {
            let _ = store.set_peer_hosts(device, &req.hosts);
        }
        engine::collect(&store, req.since, device, req.limit.clamp(1, 1000), req.first)?
    };
    let mut want = app.missing_images();
    want.truncate(200);
    let resp = SyncResponse {
        v: 1,
        records: records.iter().map(|r| r.to_wire()).collect(),
        next,
        more,
        want,
        name: own_name,
        device: own_device,
    };
    Ok(serde_json::to_vec(&resp)?)
}

fn handle_blob_get(app: &Arc<App>, v: &Value) -> Result<Vec<u8>> {
    let name = v.get("name").and_then(|x| x.as_str()).unwrap_or("");
    let Some(path) = app.image_path(name) else { return Ok(vec![]) };
    Ok(std::fs::read(path).unwrap_or_default())
}

fn handle_blob_put(app: &Arc<App>, v: &Value) -> Result<Vec<u8>> {
    use base64::Engine;
    let name = v.get("name").and_then(|x| x.as_str()).unwrap_or("");
    let data = v.get("data").and_then(|x| x.as_str()).unwrap_or("");
    let path = app.image_path(name).ok_or_else(|| anyhow!("плохое имя"))?;
    let bytes = base64::engine::general_purpose::STANDARD.decode(data)?;
    if bytes.is_empty() || bytes.len() > 20 * 1024 * 1024 {
        return Err(anyhow!("плохой размер"));
    }
    // Принимаем только то, на что ссылается заметка: иначе файл — мусор.
    let used = app.store.lock().unwrap().image_names()?;
    if !used.contains(name) {
        return Ok(serde_json::to_vec(&json!({"ok": false}))?);
    }
    std::fs::write(path, bytes)?;
    Ok(serde_json::to_vec(&json!({"ok": true}))?)
}
