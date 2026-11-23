//! Сквозная проверка по сети: два «компьютера» в одном процессе, настоящий сервер и клиент.

use mimo_core::app::App;
use mimo_core::store::SaveInput;
use mimo_core::sync::{client, pairing, server};
use std::path::PathBuf;
use std::sync::Arc;

fn temp(name: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("mimo-e2e-{}-{}", name, std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    d
}

fn note(title: &str, body: &str) -> SaveInput {
    SaveInput { id: None, title: title.into(), body: body.into(), reminder: 0, repeat: 0, lead: -1, images: None, folder: None }
}

fn pair(a: &Arc<App>, b: &Arc<App>) -> String {
    let port = server::start(a, 0).unwrap();
    let code = pairing::new_code(a).unwrap();
    // В тесте адрес один — локальная петля.
    let uri = code.uri.split("&h=").next().unwrap().to_string() + &format!("&h=127.0.0.1:{}", port);
    let peer = pairing::accept_code(b, &uri).unwrap();
    peer.device
}

#[test]
fn two_computers_sync_notes_folders_images() {
    let a = App::open_memory(&temp("a")).unwrap();
    let b = App::open_memory(&temp("b")).unwrap();
    let a_device = pair(&a, &b);

    // У A: папка, заметка с картинкой. У B: своя заметка.
    let (fid, n1) = {
        let st = a.store.lock().unwrap();
        let fid = st.create_folder("Работа", false).unwrap();
        let mut i = note("Отчёт", "квартальный отчёт");
        i.folder = Some(fid);
        i.images = Some(vec!["aaaa-bb-cc.jpg".into()]);
        (fid, st.save_note(&i).unwrap())
    };
    std::fs::write(a.images_dir().join("aaaa-bb-cc.jpg"), b"fake-jpeg-bytes").unwrap();
    b.store.lock().unwrap().save_note(&note("Покупки", "молоко")).unwrap();

    let r = client::sync_peer(&b, &a_device).unwrap();
    assert_eq!(r.received, 1, "{:?}", r);
    assert_eq!(r.sent, 1, "{:?}", r);
    assert_eq!(r.images_down, 1, "{:?}", r);

    // B получил заметку, папку и файл.
    {
        let st = b.store.lock().unwrap();
        let got = st.note_by_uid(&n1.uid).unwrap().unwrap();
        assert_eq!(got.title, "Отчёт");
        assert_eq!(st.folder(got.folder).unwrap().unwrap().name, "Работа");
        assert_eq!(st.notes("отчёт", None).unwrap().len(), 1);
    }
    assert_eq!(std::fs::read(b.images_dir().join("aaaa-bb-cc.jpg")).unwrap(), b"fake-jpeg-bytes");
    // A получила заметку B.
    assert_eq!(a.store.lock().unwrap().notes("молоко", None).unwrap().len(), 1);
    // Устройство B записано у A как узел (сопряжение подтверждено).
    assert_eq!(a.store.lock().unwrap().peers().unwrap().len(), 1);

    // Правка на B и удаление на A доезжают при следующем обмене.
    {
        let st = b.store.lock().unwrap();
        let got = st.note_by_uid(&n1.uid).unwrap().unwrap();
        let mut e = note("Отчёт", "итоговый отчёт");
        e.id = Some(got.id);
        st.save_note(&e).unwrap();
    }
    client::sync_peer(&b, &a_device).unwrap();
    assert_eq!(a.store.lock().unwrap().note_by_uid(&n1.uid).unwrap().unwrap().body, "итоговый отчёт");

    {
        let st = a.store.lock().unwrap();
        let id = st.note_by_uid(&n1.uid).unwrap().unwrap().id;
        st.delete_note(id).unwrap();
        st.delete_folder(fid).unwrap();
    }
    let r = client::sync_peer(&b, &a_device).unwrap();
    eprintln!("REPORT {:?}", r);
    eprintln!("A tombs: {:?}", a.store.lock().unwrap().conn.prepare("select kind,ts,seq,from_peer from tombstones").unwrap().query_map([], |r| Ok((r.get::<_,String>(0)?, r.get::<_,i64>(1)?, r.get::<_,i64>(2)?, r.get::<_,String>(3)?))).unwrap().collect::<Vec<_>>());
    eprintln!("B peers: {:?}", b.store.lock().unwrap().peers().unwrap());
    eprintln!("B note: {:?}", b.store.lock().unwrap().note_by_uid(&n1.uid).unwrap());
    assert_eq!(r.conflicts, 0);
    let st = b.store.lock().unwrap();
    assert!(st.note_by_uid(&n1.uid).unwrap().is_none());
    assert!(st.folders().unwrap().is_empty());
    assert_eq!(st.count_notes().unwrap(), 1);
    server::stop(&a);
}

#[test]
fn wrong_code_is_rejected_and_code_burns() {
    let a = App::open_memory(&temp("a2")).unwrap();
    let b = App::open_memory(&temp("b2")).unwrap();
    let port = server::start(&a, 0).unwrap();
    let code = pairing::new_code(&a).unwrap();
    // Подделанный секрет: меняем s=...
    let bad = code.uri.replacen("&s=", "&s=AAAA", 1).split("&h=").next().unwrap().to_string() + &format!("&h=127.0.0.1:{}", port);
    // секрет стал длиннее, ключ другой: обмен отвергается
    let peer = pairing::accept_code(&b, &bad).unwrap();
    assert!(client::sync_peer(&b, &peer.device).is_err());
    assert_eq!(a.store.lock().unwrap().peers().unwrap().len(), 0);
    server::stop(&a);
}

#[test]
fn removed_peer_cannot_sync() {
    let a = App::open_memory(&temp("a3")).unwrap();
    let b = App::open_memory(&temp("b3")).unwrap();
    let dev = pair(&a, &b);
    client::sync_peer(&b, &dev).unwrap();
    let b_device = b.store.lock().unwrap().device().unwrap().0;
    a.store.lock().unwrap().delete_peer(&b_device).unwrap();
    let err = client::sync_peer(&b, &dev).unwrap_err().to_string();
    assert!(err.contains("Сопрягите заново") || err.contains("сопряжение"), "{}", err);
    server::stop(&a);
}

#[test]
fn app_version_is_shown_with_four_numbers() {
    let a = App::open_memory(&temp("version")).unwrap();
    let v = mimo_core::api::dispatch(&a, "app_version", serde_json::json!({})).unwrap();
    let text = v.as_str().unwrap();
    assert!(!text.contains('-'), "semver-версия показывается через точку: {}", text);
    assert_eq!(text.split('.').count(), 4, "{}", text);
    assert_eq!(text, env!("CARGO_PKG_VERSION").replace('-', "."));
}
