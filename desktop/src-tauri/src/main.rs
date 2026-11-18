// В релизе на Windows не показываем окно консоли.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use mimo_core::{api, app::App};
use std::sync::Arc;
use tauri::Manager;

/// Единая команда интерфейса: вся логика — в `mimo_core::api::dispatch`.
#[tauri::command]
async fn rpc(app: tauri::State<'_, Arc<App>>, cmd: String, args: serde_json::Value) -> Result<serde_json::Value, String> {
    let core = app.inner().clone();
    tauri::async_runtime::spawn_blocking(move || api::dispatch(&core, &cmd, args).map_err(|e| e.to_string()))
        .await
        .map_err(|e| e.to_string())?
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let dir = app.path().app_data_dir()?;
            let core = App::open(&dir)?;
            api::start_background(&core);
            app.manage(core);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![rpc])
        .run(tauri::generate_context!())
        .expect("не удалось запустить «Мимоходом»");
}
