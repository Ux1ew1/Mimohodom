//! Предложения папок по темам, которые ИИ уже записал в подсказки заметок (порт
//! `FolderSuggestion`). Новых запросов к модели нет; ничего не создаётся без подтверждения.

use crate::policy;
use crate::store::Note;
use serde::Serialize;
use std::collections::{HashMap, HashSet};

pub const MIN_NOTES: usize = 2;
pub const MAX_SUGGESTIONS: usize = 5;
const TOO_GENERAL: [&str; 8] = ["видео", "youtube", "ютуб", "заметка", "заметки", "разное", "другое", "общее"];

#[derive(Serialize, Debug, Clone, PartialEq)]
pub struct Suggestion {
    pub name: String,
    /// Номер уже существующей папки с таким названием или 0, если папку надо создать.
    pub existing: i64,
    pub notes: Vec<i64>,
    /// Ключ темы: по нему предложение скрывается.
    pub key: String,
}

/// Название папки из темы: лишнее убрано, первая буква заглавная (названия вроде iPhone не трогаем).
pub fn display(topic: &str) -> String {
    let name = policy::folder_clean(topic);
    let Some(word) = name.split(' ').next() else { return String::new() };
    if name.is_empty() || word != word.to_lowercase() {
        return name;
    }
    let mut chars = name.chars();
    let first = chars.next().unwrap();
    first.to_uppercase().chain(chars).collect()
}

/// Тема годится для названия папки: не слишком общая, 3–40 символов, без лишних пробелов.
pub fn usable(topic: &str) -> bool {
    let name = policy::folder_clean(topic);
    let collapsed = topic.split_whitespace().collect::<Vec<_>>().join(" ");
    policy::folder_valid(&name) && name.chars().count() >= 3 && name == collapsed && !TOO_GENERAL.contains(&policy::folder_key(&name).as_str())
}

fn topics(n: &Note) -> Vec<String> {
    let meta = policy::metadata_read(&n.metadata);
    let mut out = Vec::new();
    if let Some(list) = meta.pointer("/ai/topics").and_then(|t| t.as_array()) {
        for t in list {
            let t = t.as_str().unwrap_or("").trim();
            if usable(t) {
                out.push(t.to_string());
            }
        }
    }
    out
}

/// Папки для заметок без папки: тема, общая у двух и более заметок, или тема, совпавшая с названием
/// существующей папки (хватает одной заметки). Каждая заметка — не более чем в одном предложении.
pub fn suggest(notes: &[Note], folders: &HashMap<String, i64>, dismissed: &HashSet<String>) -> Vec<Suggestion> {
    let mut names: HashMap<String, String> = HashMap::new();
    let mut members: HashMap<String, Vec<i64>> = HashMap::new();
    let mut order: Vec<String> = Vec::new();
    for n in notes {
        if n.folder != 0 {
            continue;
        }
        for topic in topics(n) {
            let key = policy::folder_key(&topic);
            if dismissed.contains(&key) {
                continue;
            }
            if !members.contains_key(&key) {
                order.push(key.clone());
                names.insert(key.clone(), display(&topic));
            }
            let list = members.entry(key).or_default();
            if !list.contains(&n.id) {
                list.push(n.id);
            }
        }
    }
    order.sort_by(|a, b| members[b].len().cmp(&members[a].len()).then_with(|| names[a].cmp(&names[b])));
    let mut used: HashSet<i64> = HashSet::new();
    let mut out = Vec::new();
    for key in order {
        let free: Vec<i64> = members[&key].iter().copied().filter(|id| !used.contains(id)).collect();
        let existing = folders.get(&key).copied().unwrap_or(0);
        if free.len() < if existing > 0 { 1 } else { MIN_NOTES } {
            continue;
        }
        used.extend(free.iter().copied());
        out.push(Suggestion { name: names[&key].clone(), existing, notes: free, key });
        if out.len() == MAX_SUGGESTIONS {
            break;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn note(id: i64, topics: &str, folder: i64) -> Note {
        Note { id, folder, metadata: format!("{{\"ai\":{{\"topics\":{}}}}}", topics), ..Default::default() }
    }

    #[test]
    fn names_and_filters() {
        assert_eq!(display("кулинария"), "Кулинария");
        assert_eq!(display("iPhone"), "iPhone");
        assert!(usable("Кулинария"));
        assert!(!usable("видео"));
        assert!(!usable("ИИ"), "слишком короткое");
        assert!(!usable("Все заметки"));
        assert!(!usable(&"я".repeat(45)), "длиннее сорока символов");
    }

    #[test]
    fn suggestions_follow_the_rules() {
        let notes = vec![
            note(1, r#"["кулинария","рецепты"]"#, 0),
            note(2, r#"["кулинария"]"#, 0),
            note(3, r#"["машина"]"#, 0),
            note(4, r#"["работа"]"#, 0),
            note(5, r#"["кулинария"]"#, 7),
            note(6, r#"["видео","разное"]"#, 0),
        ];
        let mut folders = HashMap::new();
        folders.insert("работа".to_string(), 3);
        let s = suggest(&notes, &folders, &HashSet::new());
        assert_eq!(s.len(), 2);
        assert_eq!((s[0].name.as_str(), s[0].notes.clone(), s[0].existing), ("Кулинария", vec![1, 2], 0));
        assert_eq!((s[1].name.as_str(), s[1].notes.clone(), s[1].existing), ("Работа", vec![4], 3), "одной заметки хватает для существующей папки");
        // Скрытая тема больше не предлагается.
        let mut hidden = HashSet::new();
        hidden.insert("кулинария".to_string());
        let s = suggest(&notes, &folders, &hidden);
        assert_eq!(s.len(), 1);
        assert_eq!(s[0].name, "Работа");
    }
}
