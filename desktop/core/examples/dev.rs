//! Отладочный сервер: отдаёт `ui/` и принимает команды `/rpc`, чтобы смотреть интерфейс в обычном
//! браузере без Tauri. Не входит в сборку приложения.
//!
//!   cargo run -p mimo-core --example dev -- [папка данных] [порт]

use mimo_core::{api, app::App};
use std::path::PathBuf;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let dir = PathBuf::from(args.get(1).cloned().unwrap_or_else(|| "target/dev-data".into()));
    let port: u16 = args.get(2).and_then(|p| p.parse().ok()).unwrap_or(5173);
    let ui = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../ui");
    let app = App::open_dev(&dir).expect("не удалось открыть данные");
    // Заглушка Polza.AI для проверки чата без платных запросов (tests/fake-polza.cjs).
    if let Ok(base) = std::env::var("MIMO_AI_BASE") {
        *app.ai_base.lock().unwrap() = base;
    }
    api::start_background(&app);
    let server = tiny_http::Server::http(("127.0.0.1", port)).expect("порт занят");
    eprintln!("http://127.0.0.1:{}/  (данные: {})", port, dir.display());
    for mut req in server.incoming_requests() {
        let url = req.url().split('?').next().unwrap_or("/").to_string();
        if req.method().as_str() == "POST" && url == "/rpc" {
            let mut body = String::new();
            let _ = req.as_reader().read_to_string(&mut body);
            let v: serde_json::Value = serde_json::from_str(&body).unwrap_or_default();
            let cmd = v["cmd"].as_str().unwrap_or("").to_string();
            let out = match api::dispatch(&app, &cmd, v["args"].clone()) {
                Ok(ok) => serde_json::json!({ "ok": ok }),
                Err(e) => serde_json::json!({ "error": e.to_string() }),
            };
            let _ = req.respond(
                tiny_http::Response::from_string(out.to_string())
                    .with_header(tiny_http::Header::from_bytes("Content-Type", "application/json").unwrap()),
            );
            continue;
        }
        let rel = if url == "/" { "index.html".to_string() } else { url.trim_start_matches('/').to_string() };
        let path = ui.join(&rel);
        let ctype = match path.extension().and_then(|e| e.to_str()) {
            Some("html") => "text/html; charset=utf-8",
            Some("css") => "text/css; charset=utf-8",
            Some("js") => "text/javascript; charset=utf-8",
            _ => "application/octet-stream",
        };
        match std::fs::read(&path) {
            Ok(bytes) if !rel.contains("..") => {
                let _ = req.respond(
                    tiny_http::Response::from_data(bytes)
                        .with_header(tiny_http::Header::from_bytes("Content-Type", ctype).unwrap())
                        .with_header(tiny_http::Header::from_bytes("Cache-Control", "no-store").unwrap()),
                );
            }
            _ => {
                let _ = req.respond(tiny_http::Response::from_string("not found").with_status_code(404));
            }
        }
    }
}
