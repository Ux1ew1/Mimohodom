//! Сопряжение: одноразовый код (строка и QR), разбор кода, адреса этого компьютера.

use crate::app::{App, Pending};
use crate::store::{now_ms, Peer};
use anyhow::{anyhow, Result};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::RngCore;
use serde::Serialize;
use std::sync::Arc;

pub const CODE_TTL_MS: i64 = 10 * 60 * 1000;

#[derive(Serialize, Clone, Debug)]
pub struct PairCode {
    pub uri: String,
    pub svg: String,
    pub expires: i64,
}

pub struct ParsedCode {
    pub device: String,
    pub name: String,
    pub secret: Vec<u8>,
    pub hosts: Vec<String>,
}

pub fn percent_encode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b == b'-' || b == b'_' || b == b'.' || b == b'~' {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{:02X}", b));
        }
    }
    out
}

pub fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out: Vec<u8> = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("zz");
            if let Ok(v) = u8::from_str_radix(hex, 16) {
                out.push(v);
                i += 3;
                continue;
            }
        } else if bytes[i] == b'+' {
            out.push(b' ');
            i += 1;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}

/// Адреса этого компьютера в локальной сети (IPv4, не loopback).
pub fn local_hosts(port: u16) -> Vec<String> {
    let mut out = Vec::new();
    if let Ok(list) = local_ip_address::list_afinet_netifas() {
        for (_name, ip) in list {
            if let std::net::IpAddr::V4(v4) = ip {
                if !v4.is_loopback() && !v4.is_link_local() && !v4.is_unspecified() {
                    out.push(format!("{}:{}", v4, port));
                }
            }
        }
    }
    out.sort();
    out.dedup();
    out
}

pub fn build_uri(device: &str, name: &str, secret: &[u8], hosts: &[String]) -> String {
    format!(
        "mimohodom://pair?v=1&id={}&n={}&s={}&h={}",
        device,
        percent_encode(name),
        URL_SAFE_NO_PAD.encode(secret),
        hosts.join(",")
    )
}

pub fn parse_uri(text: &str) -> Result<ParsedCode> {
    let t = text.trim();
    let q = t.strip_prefix("mimohodom://pair?").ok_or_else(|| anyhow!("Это не код сопряжения «Мимоходом»"))?;
    let (mut v, mut id, mut n, mut s, mut h) = (String::new(), String::new(), String::new(), String::new(), String::new());
    for part in q.split('&') {
        if let Some((k, val)) = part.split_once('=') {
            match k {
                "v" => v = val.to_string(),
                "id" => id = val.to_string(),
                "n" => n = percent_decode(val),
                "s" => s = val.to_string(),
                "h" => h = val.to_string(),
                _ => {}
            }
        }
    }
    if v != "1" {
        return Err(anyhow!("Версия кода сопряжения не поддерживается: обновите приложение"));
    }
    let secret = URL_SAFE_NO_PAD.decode(s.as_bytes()).map_err(|_| anyhow!("Код сопряжения повреждён"))?;
    if id.len() != 32 || secret.len() < 16 {
        return Err(anyhow!("Код сопряжения повреждён"));
    }
    let hosts: Vec<String> = h.split(',').filter(|x| !x.is_empty()).map(|x| x.to_string()).collect();
    Ok(ParsedCode { device: id, name: if n.is_empty() { "Устройство".into() } else { n }, secret, hosts })
}

/// Создаёт одноразовый код на 10 минут. Сервер должен быть запущен (знает порт).
pub fn new_code(app: &Arc<App>) -> Result<PairCode> {
    let port = *app.sync.port.lock().unwrap();
    if port == 0 {
        return Err(anyhow!("Синхронизация выключена: включите её, чтобы показать код"));
    }
    let mut secret = vec![0u8; 16];
    rand::thread_rng().fill_bytes(&mut secret);
    let (device, name) = app.store.lock().unwrap().device()?;
    let hosts = local_hosts(port);
    let uri = build_uri(&device, &name, &secret, &hosts);
    let expires = now_ms() + CODE_TTL_MS;
    *app.sync.pending.lock().unwrap() = Some(Pending { secret, expires, fails: 0 });
    let svg = qr_svg(&uri)?;
    Ok(PairCode { uri, svg, expires })
}

pub fn cancel_code(app: &Arc<App>) {
    *app.sync.pending.lock().unwrap() = None;
}

pub fn qr_svg(text: &str) -> Result<String> {
    let code = qrcode::QrCode::with_error_correction_level(text.as_bytes(), qrcode::EcLevel::M)
        .map_err(|e| anyhow!("QR: {}", e))?;
    Ok(code.render::<qrcode::render::svg::Color>().min_dimensions(240, 240).quiet_zone(true).build())
}

/// Сохраняет соседа по введённому коду (мы — клиент). Первый обмен подтвердит сопряжение.
pub fn accept_code(app: &Arc<App>, text: &str) -> Result<Peer> {
    let p = parse_uri(text)?;
    let store = app.store.lock().unwrap();
    let (own, _) = store.device()?;
    if p.device == own {
        return Err(anyhow!("Это код этого же устройства"));
    }
    let peer = Peer {
        device: p.device,
        name: p.name,
        platform: String::new(),
        hosts: p.hosts,
        sent_seq: 0,
        got_seq: 0,
        last_sync: 0,
        secret: p.secret,
    };
    store.save_peer(&peer)?;
    Ok(peer)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uri_roundtrip() {
        let secret: Vec<u8> = (0u8..16).collect();
        let dev = "0123456789abcdef0123456789abcdef";
        let uri = build_uri(dev, "Мой ПК (Windows)", &secret, &["192.168.1.5:47616".into(), "10.0.0.2:47616".into()]);
        let p = parse_uri(&uri).unwrap();
        assert_eq!(p.device, dev);
        assert_eq!(p.name, "Мой ПК (Windows)");
        assert_eq!(p.secret, secret);
        assert_eq!(p.hosts.len(), 2);
        assert!(parse_uri("http://x").is_err());
        assert!(parse_uri("mimohodom://pair?v=1&id=1&s=!!").is_err());
    }

    #[test]
    fn qr_is_svg() {
        let svg = qr_svg("mimohodom://pair?v=1").unwrap();
        assert!(svg.contains("<svg"));
    }
}
