//! HTTP-клиент Polza.AI (порт `Polza.request`, `models`, `balance`). Адрес можно подменить для
//! тестов (`App::ai_base`), поэтому проверки обходятся без платных запросов.

use anyhow::{anyhow, Result};
use serde_json::Value;
use std::io::Read;
use std::time::Duration;

pub const DEFAULT_BASE: &str = "https://polza.ai/api";

/// Ошибка с текстом для пользователя. `timeout` — ответ не пришёл, запрос мог быть оплачен.
#[derive(Debug)]
pub struct AiError {
    pub message: String,
    pub timeout: bool,
}

impl std::fmt::Display for AiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}
impl std::error::Error for AiError {}

fn fail(message: &str) -> anyhow::Error {
    anyhow::Error::new(AiError { message: message.into(), timeout: false })
}

pub fn request(base: &str, version: &str, path: &str, key: &str, body: Option<&Value>, read_timeout: Duration) -> Result<Value> {
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(15))
        .timeout_read(read_timeout)
        .timeout_write(Duration::from_secs(60))
        .redirects(0)
        .build();
    let url = format!("{}/{}/{}", base.trim_end_matches('/'), version, path);
    let req = agent.request(if body.is_some() { "POST" } else { "GET" }, &url)
        .set("Authorization", &format!("Bearer {}", key))
        .set("Accept", "application/json");
    let result = match body {
        Some(b) => req.set("Content-Type", "application/json; charset=utf-8").send_string(&b.to_string()),
        None => req.call(),
    };
    let resp = match result {
        Ok(r) => r,
        Err(ureq::Error::Status(code, _)) => {
            return Err(fail(&match code {
                401 | 403 => "Проверьте API-ключ и доступ к модели в настройках.".to_string(),
                402 => "Недостаточно средств на балансе Polza.AI.".to_string(),
                429 => "Превышен лимит запросов. Попробуйте позже.".to_string(),
                _ => format!("Polza.AI вернул ошибку {}. Повторите запрос позже.", code),
            }))
        }
        Err(ureq::Error::Transport(t)) => {
            let text = t.to_string().to_lowercase();
            if text.contains("timed out") || text.contains("timeout") {
                return Err(anyhow::Error::new(AiError {
                    message: "Время ожидания истекло. Ответ не получен; запрос мог быть обработан и оплачен. Повтор — только вручную.".into(),
                    timeout: true,
                }));
            }
            return Err(fail("Нет связи с Polza.AI. Проверьте подключение к интернету."));
        }
    };
    let mut buf = Vec::new();
    resp.into_reader().take(4_000_001).read_to_end(&mut buf)?;
    if buf.len() > 4_000_000 {
        return Err(fail("Слишком большой ответ сервера"));
    }
    serde_json::from_slice(&buf).map_err(|_| fail("Polza.AI вернул непонятный ответ. Повторите запрос позже."))
}

pub fn complete(base: &str, key: &str, payload: &Value, timeout_secs: u64) -> Result<Value> {
    request(base, "v1", "chat/completions", key, Some(payload), Duration::from_secs(timeout_secs))
}

pub fn models(base: &str, key: &str) -> Result<Vec<String>> {
    let v = request(base, "v1", "models?type=chat", key, None, Duration::from_secs(60))?;
    let mut ids: Vec<String> = v
        .get("data")
        .and_then(|d| d.as_array())
        .ok_or_else(|| anyhow!("Нет списка моделей"))?
        .iter()
        .filter_map(|m| m.get("id").and_then(|i| i.as_str()).map(String::from))
        .collect();
    ids.sort();
    Ok(ids)
}

/// Баланс: (всего, доступно) строками, как их отдаёт сервер.
pub fn balance(base: &str, key: &str) -> Result<(String, String)> {
    let v = request(base, "v2", "balance", key, None, Duration::from_secs(60))?;
    let get = |k: &str| match v.get(k) {
        Some(Value::String(s)) => s.clone(),
        Some(other) => other.to_string(),
        None => "?".into(),
    };
    Ok((get("amount"), get("available")))
}
