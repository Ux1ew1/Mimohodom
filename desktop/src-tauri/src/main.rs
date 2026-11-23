// В релизе на Windows не показываем окно консоли.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use mimo_core::{api, app::App, reminders};
use std::sync::Arc;
use std::time::Duration;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};
use tauri_plugin_notification::NotificationExt;

/// Единая команда интерфейса: вся логика — в `mimo_core::api::dispatch`.
#[tauri::command]
async fn rpc(app: tauri::State<'_, Arc<App>>, cmd: String, args: serde_json::Value) -> Result<serde_json::Value, String> {
    let core = app.inner().clone();
    tauri::async_runtime::spawn_blocking(move || api::dispatch(&core, &cmd, args).map_err(|e| e.to_string()))
        .await
        .map_err(|e| e.to_string())?
}

/// Запускается ли приложение при входе в систему.
#[tauri::command]
fn autostart_get(app: AppHandle) -> Result<bool, String> {
    app.autolaunch().is_enabled().map_err(|e| e.to_string())
}

#[tauri::command]
fn autostart_set(app: AppHandle, on: bool) -> Result<(), String> {
    let launch = app.autolaunch();
    if on { launch.enable() } else { launch.disable() }.map_err(|e| e.to_string())
}

/// Сворачивать ли окно в трей при закрытии (по умолчанию да: так работают напоминания и синхронизация).
fn tray_on(core: &Arc<App>) -> bool {
    core.store.lock().unwrap().setting("app.tray").as_deref() != Some("0")
}

fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

/// Значок в трее: левая кнопка открывает окно, меню — «Открыть» и «Выйти».
fn build_tray(app: &tauri::App) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Открыть Мимоходом", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Выйти", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &quit])?;
    let mut tray = TrayIconBuilder::with_id("main")
        .tooltip("Мимоходом")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                show_main(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
    Ok(())
}

/// Показывает уведомления о напоминаниях, которые накопило ядро.
fn spawn_notifier(app: AppHandle, core: Arc<App>) {
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(2));
        for n in reminders::take_popups(&core) {
            // Если система не показала уведомление, причина видна в настройках напоминаний.
            let shown = app.notification().builder().title(n.title).body(n.text).show();
            let st = core.store.lock().unwrap();
            match shown {
                Ok(()) => {
                    let _ = st.delete_setting("reminder.popup_error");
                }
                Err(e) => {
                    let _ = st.set_setting("reminder.popup_error", &e.to_string());
                }
            }
        }
    });
}

fn main() {
    tauri::Builder::default()
        // Второй запуск только поднимает окно первого: порт синхронизации и база нужны одному экземпляру.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_main(app)))
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec!["--background"])))
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            // MIMO_DATA_DIR — отдельная папка данных для проверок, чтобы не трогать настоящую базу.
            let dir = match std::env::var_os("MIMO_DATA_DIR") {
                Some(d) => std::path::PathBuf::from(d),
                None => app.path().app_data_dir()?,
            };
            let core = App::open(&dir)?;
            api::start_background(&core);
            app.manage(core.clone());
            build_tray(app)?;
            spawn_notifier(app.handle().clone(), core.clone());
            // Запуск при входе в систему стартует свернутым в трей (только если трей включён: иначе окно не вернуть).
            if std::env::args().any(|a| a == "--background") && tray_on(&core) {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.hide();
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                let core = window.app_handle().state::<Arc<App>>().inner().clone();
                if tray_on(&core) {
                    api.prevent_close();
                    let _ = window.hide();
                    // Один раз объясняем, почему приложение не закрылось.
                    let hinted = core.store.lock().unwrap().setting("app.tray.hinted").is_some();
                    if !hinted {
                        let _ = core.store.lock().unwrap().set_setting("app.tray.hinted", "1");
                        let _ = window
                            .app_handle()
                            .notification()
                            .builder()
                            .title("Мимоходом работает в трее")
                            .body("Напоминания и синхронизация продолжаются. Выйти можно из меню значка в трее.")
                            .show();
                    }
                }
            }
        })
        .invoke_handler(tauri::generate_handler![rpc, autostart_get, autostart_set])
        .run(tauri::generate_context!())
        .expect("не удалось запустить «Мимоходом»");
}
