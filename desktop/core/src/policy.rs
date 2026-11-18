//! Чистая логика без базы и сети: порт Java-классов `FolderPolicy`, `ImageList`, `Note.autoTitle`,
//! `Retrieval` (часть), `SearchQuery` и `Metadata`. Поведение должно совпадать с Android.

use serde_json::{json, Map, Value};
use std::collections::HashSet;

// ---------- Папки ----------

pub const MAX_FOLDER_NAME: usize = 40;

/// Убирает лишние пробелы и управляющие символы, обрезает до 40 символов.
pub fn folder_clean(name: &str) -> String {
    let mut out = String::new();
    let mut gap = false;
    for ch in name.chars() {
        if ch.is_control() || ch.is_whitespace() {
            gap = true;
        } else {
            if gap && !out.is_empty() {
                out.push(' ');
            }
            gap = false;
            out.push(ch);
        }
    }
    let s: String = out.chars().take(MAX_FOLDER_NAME).collect();
    s.trim().to_string()
}

pub fn folder_key(name: &str) -> String {
    folder_clean(name).to_lowercase()
}

pub fn folder_valid(name: &str) -> bool {
    let key = folder_key(name);
    !key.is_empty() && key != "все заметки"
}

// ---------- Картинки ----------

pub const MAX_IMAGES: usize = 8;

/// Имена через запятую без пустых и повторов.
pub fn image_parse(value: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for part in value.split(',') {
        let name = part.trim();
        if !name.is_empty() && !out.iter().any(|n| n == name) {
            out.push(name.to_string());
        }
    }
    out
}

pub fn image_join(names: &[String]) -> String {
    let mut seen: Vec<&String> = Vec::new();
    for n in names {
        if !seen.contains(&n) {
            seen.push(n);
        }
    }
    seen.iter().map(|s| s.as_str()).collect::<Vec<_>>().join(",")
}

/// Безопасное имя файла картинки (без путей).
pub fn safe_file_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 80
        && !name.contains("..")
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-')
}

// ---------- Название ----------

/// Название по умолчанию: первая строка текста до 52 символов.
pub fn auto_title(title: &str, body: &str) -> String {
    if !title.trim().is_empty() {
        return title.trim().to_string();
    }
    let line = body.trim().lines().next().unwrap_or("").trim().to_string();
    if line.is_empty() {
        return "Новая заметка".into();
    }
    let count = line.chars().count();
    if count > 52 {
        let cut: String = line.chars().take(52).collect();
        format!("{}…", cut)
    } else {
        line
    }
}

// ---------- Поиск ----------

const STOP: [&str; 16] = [
    "как", "какой", "какие", "есть", "мне", "для", "это", "что", "все", "про", "мои", "или", "его",
    "где", "они", "the",
];

pub fn normalize(s: &str) -> String {
    s.to_lowercase().replace('ё', "е")
}

fn split_words(s: &str) -> Vec<String> {
    s.split(|c: char| !(c.is_alphabetic() || c.is_numeric()))
        .filter(|w| !w.is_empty())
        .map(|w| w.to_string())
        .collect()
}

/// Слова запроса с обрезанными окончаниями (порт `Retrieval.terms`).
pub fn terms(text: &str) -> Vec<String> {
    let norm = normalize(text);
    let mut out: Vec<String> = Vec::new();
    for w in split_words(&norm) {
        let len = w.chars().count();
        if len > 2 && !STOP.contains(&w.as_str()) && w != "and" {
            if len > 5 {
                out.push(w.chars().take(len - 2).collect());
            } else {
                out.push(w);
            }
        }
    }
    if norm.contains("рецепт") || norm.contains("приготов") {
        for s in ["ингредиент", "мука", "сахар", "духовк", "варить", "жарить", "салат", "суп", "грамм"] {
            out.push(s.into());
        }
    }
    if norm.contains("праздник") {
        for s in ["рождени", "юбилей", "годовщин", "праздник", "новый год", "свадьб"] {
            out.push(s.into());
        }
    }
    out
}

/// Строка для `MATCH` в FTS: `слово* OR слово*`, не больше 20 слов (порт `SearchQuery.build`).
pub fn search_query(query: &str) -> String {
    let mut list = terms(query);
    if list.is_empty() {
        list = split_words(&normalize(query));
    }
    let mut seen: HashSet<String> = HashSet::new();
    let mut out = String::new();
    for term in list {
        for token in split_words(&normalize(&term)) {
            if token.chars().count() < 2 || !seen.insert(token.clone()) {
                continue;
            }
            if !out.is_empty() {
                out.push_str(" OR ");
            }
            out.push_str(&token);
            out.push('*');
            if seen.len() >= 20 {
                return out;
            }
        }
    }
    out
}

// ---------- Метаданные ----------

/// Отрывок без запроса: начало текста, не длиннее `limit` символов (порт `Retrieval.excerpt`).
pub fn excerpt(body: &str, limit: usize) -> String {
    if limit < 3 {
        return String::new();
    }
    let chars: Vec<char> = body.chars().collect();
    if chars.len() <= limit {
        return body.to_string();
    }
    let end = (limit - 2).min(chars.len());
    let mut s: String = chars[..end].iter().collect();
    s.push('…');
    s
}

/// Основные метаданные заметки: описание и до 16 частых слов (порт `Metadata.base`).
pub fn metadata_base(title: &str, body: &str, created: i64, updated: i64) -> Value {
    let text = format!("{} {}", title, excerpt(body, 5000));
    let lowered = normalize(&text);
    let mut order: Vec<String> = Vec::new();
    let mut counts: std::collections::HashMap<String, i32> = std::collections::HashMap::new();
    for word in split_words(&lowered) {
        let n = word.chars().count();
        if (4..=48).contains(&n) {
            let e = counts.entry(word.clone()).or_insert(0);
            if *e == 0 {
                order.push(word);
            }
            *e += 1;
        }
    }
    order.sort_by(|a, b| counts[b].cmp(&counts[a])); // sort_by стабилен, как List.sort
    let keywords: Vec<Value> = order.into_iter().take(16).map(Value::String).collect();
    json!({
        "version": 1,
        "title": title,
        "description": excerpt(body, 280),
        "created_at": created,
        "updated_at": updated,
        "keywords": keywords,
    })
}

const SEARCH_KEYS: [&str; 10] = [
    "title", "description", "keywords", "search_title", "summary", "aliases", "topics", "people",
    "places", "dates",
];

fn append_search(out: &mut String, data: &Map<String, Value>) {
    for key in SEARCH_KEYS {
        match data.get(key) {
            Some(Value::String(s)) => {
                out.push_str(s);
                out.push(' ');
            }
            Some(Value::Array(a)) => {
                for v in a {
                    if let Value::String(s) = v {
                        out.push_str(s);
                        out.push(' ');
                    }
                }
            }
            _ => {}
        }
    }
}

/// Текст для индекса поиска из метаданных (порт `Metadata.searchText`).
pub fn search_text(metadata: &Value) -> String {
    let mut out = String::new();
    if let Some(obj) = metadata.as_object() {
        append_search(&mut out, obj);
        if let Some(Value::Object(ai)) = obj.get("ai") {
            append_search(&mut out, ai);
        }
    }
    out.trim().replace('ё', "е").replace('Ё', "Е")
}

pub fn metadata_read(json_text: &str) -> Value {
    match serde_json::from_str::<Value>(json_text) {
        Ok(v @ Value::Object(_)) => v,
        _ => Value::Object(Map::new()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn folder_names() {
        assert_eq!(folder_clean("  Работа \n  дом "), "Работа дом");
        assert_eq!(folder_key("РАБОТА"), "работа");
        assert!(!folder_valid("Все заметки"));
        assert!(!folder_valid("   "));
        assert_eq!(folder_clean(&"я".repeat(60)).chars().count(), 40);
    }

    #[test]
    fn images() {
        assert_eq!(image_parse("a.jpg, b.jpg,,a.jpg"), vec!["a.jpg", "b.jpg"]);
        assert_eq!(image_join(&["a".into(), "b".into(), "a".into()]), "a,b");
        assert!(safe_file_name("3f2a-1.jpg"));
        assert!(!safe_file_name("../x.jpg"));
        assert!(!safe_file_name("a/b.jpg"));
    }

    #[test]
    fn titles() {
        assert_eq!(auto_title("  Привет ", "x"), "Привет");
        assert_eq!(auto_title("", ""), "Новая заметка");
        assert_eq!(auto_title("", "первая строка\nвторая"), "первая строка");
        let long = "а".repeat(60);
        let t = auto_title("", &long);
        assert_eq!(t.chars().count(), 53);
        assert!(t.ends_with('…'));
    }

    #[test]
    fn query() {
        // «рецепты» (7) -> «рецеп»; слова короче 3 отбрасываются; ё -> е.
        assert_eq!(search_query("рецепты салат"), "рецеп* OR салат* OR ингредиент* OR мука* OR сахар* OR духовк* OR варить* OR жарить* OR суп* OR грамм*");
        assert_eq!(search_query("ёлка"), "елка*");
        assert_eq!(search_query("и"), "");
    }

    #[test]
    fn metadata() {
        let m = metadata_base("Купить молоко", "молоко молоко хлеб", 1, 2);
        let kw = m["keywords"].as_array().unwrap();
        assert_eq!(kw[0], "молоко");
        assert_eq!(m["created_at"], 1);
        let st = search_text(&m);
        assert!(st.contains("Купить молоко"));
        assert!(st.contains("хлеб"));
    }
}
