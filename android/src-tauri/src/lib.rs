// Suji: posture loads computed and drawn in the WebView (cloud-itonami/suji
// physics, kotoba-lang/webgpu rendering). This host exposes only its version.
// No network, publishing, payment, filesystem, installer or shell commands.
#[tauri::command]
fn awai_host_info() -> serde_json::Value {
    serde_json::json!({"schema":"awai.shell.host.v1", "agendaProjection":false})
}
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .runtime(tauri_runtime_wry::Wry::default())
        .invoke_handler(tauri::generate_handler![awai_host_info])
        .run(tauri::generate_context!())
        .expect("Suji initialization failed");
}
