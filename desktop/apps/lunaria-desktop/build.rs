//! Build script compiling Lunaria's Slint Material Design 3 UI and Windows icon resource.

use std::path::{Path, PathBuf};
use std::process::Command;

fn resolve_msvc_rc() -> PathBuf {
    let kits_root = Path::new(r"C:\Program Files (x86)\Windows Kits\10\bin");
    if let Ok(entries) = std::fs::read_dir(kits_root) {
        let mut versions: Vec<PathBuf> = entries
            .filter_map(Result::ok)
            .map(|e| e.path().join("x64").join("rc.exe"))
            .filter(|p| p.is_file())
            .collect();
        versions.sort();
        if let Some(latest) = versions.pop() {
            return latest;
        }
    }
    PathBuf::from("rc.exe")
}

fn compile_windows_icon(manifest_dir: &Path, out_dir: &Path) {
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target_os != "windows" {
        return;
    }

    let assets_dir = manifest_dir.join("assets");
    let rc_path = assets_dir.join("lunaria.rc");
    let target_env = std::env::var("CARGO_CFG_TARGET_ENV").unwrap_or_default();

    let (tool, args, out_obj) = if target_env == "msvc" {
        let res_path = out_dir.join("lunaria_res.res");
        let fo_arg = format!("/fo{}", res_path.display());
        let inc_arg = format!("/I{}", assets_dir.display());
        let rc_tool = resolve_msvc_rc();
        (
            rc_tool.display().to_string(),
            vec![fo_arg, inc_arg, rc_path.display().to_string()],
            res_path,
        )
    } else {
        let obj_path = out_dir.join("lunaria_res.o");
        (
            "windres".to_owned(),
            vec![
                "-I".to_owned(),
                assets_dir.display().to_string(),
                "-i".to_owned(),
                rc_path.display().to_string(),
                "-O".to_owned(),
                "coff".to_owned(),
                "-o".to_owned(),
                obj_path.display().to_string(),
            ],
            obj_path,
        )
    };

    match Command::new(&tool).args(&args).status() {
        Ok(status) if status.success() => {
            println!(
                "cargo::rustc-link-arg-bin=lunaria-desktop={}",
                out_obj.display()
            );
        }
        Ok(status) => {
            eprintln!("resource compiler `{tool}` failed with exit status: {status}");
            std::process::exit(1);
        }
        Err(err) => {
            eprintln!("failed to invoke resource compiler `{tool}`: {err}");
            std::process::exit(1);
        }
    }
}

fn main() {
    println!("cargo::rerun-if-changed=build.rs");
    println!("cargo::rerun-if-changed=ui/theme.slint");
    println!("cargo::rerun-if-changed=ui/types.slint");
    println!("cargo::rerun-if-changed=ui/widgets.slint");
    println!("cargo::rerun-if-changed=ui/supporting_pane.slint");
    println!("cargo::rerun-if-changed=ui/tag_editor.slint");
    println!("cargo::rerun-if-changed=ui/views.slint");
    println!("cargo::rerun-if-changed=ui/main.slint");
    println!("cargo::rerun-if-changed=assets/lunaria.rc");
    println!("cargo::rerun-if-changed=assets/lunaria.ico");
    println!("cargo::rerun-if-changed=assets/icon.svg");

    let manifest_dir = match std::env::var_os("CARGO_MANIFEST_DIR") {
        Some(dir) => PathBuf::from(dir),
        None => {
            eprintln!("CARGO_MANIFEST_DIR is not set");
            std::process::exit(1);
        }
    };
    let out_dir = match std::env::var_os("OUT_DIR") {
        Some(dir) => PathBuf::from(dir),
        None => {
            eprintln!("OUT_DIR is not set");
            std::process::exit(1);
        }
    };

    compile_windows_icon(&manifest_dir, &out_dir);

    let config = slint_build::CompilerConfiguration::new().with_style("material-dark".to_owned());

    if let Err(err) = slint_build::compile_with_config("ui/main.slint", config) {
        eprintln!("failed to compile Slint UI: {err}");
        std::process::exit(1);
    }
}
