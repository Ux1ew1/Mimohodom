//! Клиент синхронизации: обмен с одним узлом (компьютером) по протоколу docs/SYNC.md.

use super::crypto::{self, derive_key, request_aad, response_aad};
use super::engine::{self, Record, Stats};
use super::DISCOVERY_PORT;
use crate::app::App;
use crate::store::{now_ms, random_hex};
use anyhow::{anyhow, Result};
use base64::Engine;
use serde::Serialize;
use serde_json::{json, Value};
use std::io::Read;
use std::net::UdpSocket;
use std::sync::Arc;
use std::time::Duration;

const BATCH: usize = 200;

#[derive(Serialize, Debug, Default, Clone)]
pub struct Report {
    pub peer: String,
    pub sent: usize,
    pub received: usize,
    pub conflicts: usize,
    pub images_down: usize,
    pub images_up: usize,
    pub host: String,
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(4))
        .timeout_read(Duration::from_secs(90))
        .timeout_write(Duration::from_secs(90))
        .build()
}

struct Link {
    agent: ureq::Agent,
    base: String,
    own_device: String,
    key: [u8; 32],
}

enum Failure {
    Unreachable(String),
    Rejected(u16),
    Other(String),
}

impl Link {
    fn post(&self, path: &str, mut body: Value) -> std::result::Result<Vec<u8>, Failure> {
        body["v"] = json!(1);
        body["ts"] = json!(now_ms());
        body["rid"] = json!(random_hex(8));
        let plain = serde_json::to_vec(&body).map_err(|e| Failure::Other(e.to_string()))?;
        let sealed = crypto::seal(&self.key, &request_aad(&self.own_device), &plain);
        let url = format!("http://{}{}", self.base, path);
        match self.agent.post(&url).set("X-Mimo-Device", &self.own_device).send_bytes(&sealed) {
            Ok(resp) => {
                let mut buf = Vec::new();
                resp.into_reader()
                    .take(super::MAX_BODY as u64 + 1)
                    .read_to_end(&mut buf)
                    .map_err(|e| Failure::Unreachable(e.to_string()))?;
                crypto::open(&self.key, &response_aad(&self.own_device), &buf)
                    .ok_or_else(|| Failure::Other("ответ не удалось расшифровать".into()))
            }
            Err(ureq::Error::Status(code, _)) => Err(Failure::Rejected(code)),
            Err(e) => Err(Failure::Unreachable(e.to_string())),
        }
    }
}

/// Ищет устройство в сети по UDP-широковещанию.
pub fn discover(device: &str, wait: Duration) -> Vec<String> {
    let mut out = Vec::new();
    let Ok(sock) = UdpSocket::bind(("0.0.0.0", 0)) else { return out };
    let _ = sock.set_broadcast(true);
    let _ = sock.set_read_timeout(Some(Duration::from_millis(300)));
    let msg = format!("MIMO?{}", device);
    let _ = sock.send_to(msg.as_bytes(), ("255.255.255.255", DISCOVERY_PORT));
    let end = std::time::Instant::now() + wait;
    let mut buf = [0u8; 128];
    while std::time::Instant::now() < end {
        if let Ok((n, src)) = sock.recv_from(&mut buf) {
            let text = String::from_utf8_lossy(&buf[..n]).to_string();
            if let Some(rest) = text.strip_prefix("MIMO!") {
                if let Some((dev, port)) = rest.split_once(':') {
                    if dev == device {
                        let host = format!("{}:{}", src.ip(), port.trim());
                        if !out.contains(&host) {
                            out.push(host);
                        }
                    }
                }
            }
        }
    }
    out
}

/// Обмен с узлом `device`. Блокирующий; вызывать из потока.
pub fn sync_peer(app: &Arc<App>, device: &str) -> Result<Report> {
    let (own_device, own_name, peer) = {
        let s = app.store.lock().unwrap();
        let (d, n) = s.device()?;
        (d, n, s.peer(device)?.ok_or_else(|| anyhow!("Устройство не найдено"))?)
    };
    let key = derive_key(&peer.secret);
    let my_hosts = if super::server::is_running(app) {
        super::pairing::local_hosts(*app.sync.port.lock().unwrap())
    } else {
        vec![]
    };
    let mut report = Report { peer: peer.name.clone(), ..Default::default() };

    // Пробуем адреса: сохранённые, затем найденные широковещанием.
    let mut candidates = peer.hosts.clone();
    let mut tried_discovery = false;
    let mut link: Option<Link> = None;
    let mut first_reply: Option<Vec<u8>> = None;
    let first_req = |rec_batch: Vec<Value>, since: i64| {
        json!({"device": own_device, "name": own_name, "hosts": my_hosts, "since": since,
               "limit": 300, "first": true, "features": ["chats"], "records": rec_batch})
    };
    // Первая пачка отправки собирается один раз и используется при выборе адреса.
    let (batch0, more0, next0) = {
        let s = app.store.lock().unwrap();
        engine::collect(&s, peer.sent_seq, &peer.device, BATCH, true, true)?
    };
    let wire0: Vec<Value> = batch0.iter().map(|r| r.to_wire()).collect();
    let needs_incognito = batch0.iter().any(|r| r.incognito == Some(true));
    let mut last_error = String::from("Устройство не найдено в сети");
    loop {
        while let Some(host) = candidates.pop() {
            let l = Link { agent: agent(), base: host.clone(), own_device: own_device.clone(), key };
            if needs_incognito {
                match l.post("/v1/incognito", json!({})) {
                    Ok(reply) if !reply.is_empty() => {},
                    _ => {
                        last_error = format!("{}: обновите Мимоходом для обмена папками инкогнито", host);
                        continue;
                    }
                }
            }
            match l.post("/v1/sync", first_req(wire0.clone(), peer.got_seq)) {
                Ok(reply) => {
                    report.host = host;
                    link = Some(l);
                    first_reply = Some(reply);
                    break;
                }
                Err(Failure::Rejected(401)) => {
                    return Err(anyhow!("«{}» не принимает это устройство: сопряжение удалено или код устарел. Сопрягите заново.", peer.name));
                }
                Err(Failure::Rejected(c)) => last_error = format!("Ответ {} от {}", c, host),
                Err(Failure::Unreachable(e)) => last_error = format!("{}: {}", host, e),
                Err(Failure::Other(e)) => last_error = e,
            }
        }
        if link.is_some() || tried_discovery {
            break;
        }
        tried_discovery = true;
        candidates = discover(&peer.device, Duration::from_millis(1500));
    }
    let link = link.ok_or_else(|| anyhow!("Не удалось подключиться: {}", last_error))?;
    // Запоминаем рабочий адрес первым.
    {
        let mut hosts: Vec<String> = peer.hosts.iter().filter(|h| **h != report.host).cloned().collect();
        hosts.push(report.host.clone());
        let _ = app.store.lock().unwrap().set_peer_hosts(&peer.device, &hosts);
    }

    let mut want: Vec<String> = Vec::new();
    let mut since = peer.got_seq;
    let mut sent = peer.sent_seq;
    let mut pending_out: (Vec<Value>, bool, i64) = (wire0, more0, next0);
    let mut reply = first_reply;
    let mut first = true;
    let mut total = Stats::default();
    loop {
        let bytes = match reply.take() {
            Some(b) => b,
            None => {
                let body = json!({"device": own_device, "name": own_name, "hosts": my_hosts, "since": since,
                                  "limit": 300, "first": first, "features": ["chats"], "records": pending_out.0});
                link.post("/v1/sync", body).map_err(describe)?
            }
        };
        let v: Value = serde_json::from_slice(&bytes)?;
        // Сервер принял нашу пачку, раз вернул ответ.
        report.sent += pending_out.0.iter().filter(|r| r["kind"] != "folder").count();
        sent = pending_out.2.max(sent);
        let recs: Vec<Record> = serde_json::from_value(v["records"].clone())?;
        report.received += recs.iter().filter(|r| r.kind == "note").count();
        {
            let s = app.store.lock().unwrap();
            let st = engine::apply(&s, &peer.device, sent, &recs)?;
            total.add(&st);
            s.update_peer_progress(&peer.device, Some(sent), None, None)?;
        }
        since = v["next"].as_i64().unwrap_or(since);
        let more_in = v["more"].as_bool().unwrap_or(false);
        if let Some(w) = v["want"].as_array() {
            for n in w.iter().filter_map(|x| x.as_str()) {
                if !want.iter().any(|x| x == n) {
                    want.push(n.to_string());
                }
            }
        }
        let more_out = pending_out.1;
        first = false;
        if !more_in && !more_out {
            break;
        }
        pending_out = if more_out {
            let s = app.store.lock().unwrap();
            let (b, m, n) = engine::collect(&s, sent, &peer.device, BATCH, false, true)?;
            (b.iter().map(|r| r.to_wire()).collect(), m, n)
        } else {
            (vec![], false, sent)
        };
    }
    report.conflicts = total.conflicts;
    {
        let s = app.store.lock().unwrap();
        s.update_peer_progress(&peer.device, Some(sent), Some(since), Some(now_ms()))?;
    }

    // Картинки: скачиваем недостающие, отдаём запрошенные.
    for name in app.missing_images() {
        if let Ok(bytes) = link.post("/v1/blob/get", json!({"name": name})) {
            if !bytes.is_empty() {
                if let Some(p) = app.image_path(&name) {
                    if std::fs::write(p, bytes).is_ok() {
                        report.images_down += 1;
                    }
                }
            }
        }
    }
    for name in want {
        let Some(path) = app.image_path(&name) else { continue };
        let Ok(bytes) = std::fs::read(&path) else { continue };
        let data = base64::engine::general_purpose::STANDARD.encode(bytes);
        if link.post("/v1/blob/put", json!({"name": name, "data": data})).is_ok() {
            report.images_up += 1;
        }
    }
    Ok(report)
}

fn describe(f: Failure) -> anyhow::Error {
    match f {
        Failure::Unreachable(e) => anyhow!("Связь прервалась: {}", e),
        Failure::Rejected(401) => anyhow!("Устройство не принимает сопряжение. Сопрягите заново."),
        Failure::Rejected(c) => anyhow!("Ответ сервера {}", c),
        Failure::Other(e) => anyhow!(e),
    }
}
