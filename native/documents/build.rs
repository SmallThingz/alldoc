use std::{env, path::PathBuf, process::Command};
fn run(command: &mut Command) {
    assert!(
        command
            .status()
            .expect("native compiler unavailable")
            .success(),
        "native bridge compile failed"
    );
}
fn main() {
    for var in ["ALLDOC_DOC_CC", "ALLDOC_DOC_AR", "ALLDOC_DOC_JNI_INCLUDE"] {
        println!("cargo:rerun-if-env-changed={var}");
    }
    println!("cargo:rerun-if-changed=bridge.c");
    let out = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    let object = out.join("bridge.o");
    let mut cc = Command::new(env::var("ALLDOC_DOC_CC").unwrap_or_else(|_| "cc".into()));
    cc.args([
        "-std=c11",
        "-O2",
        "-fPIC",
        "-fvisibility=hidden",
        "-fstack-protector-strong",
        "-D_FORTIFY_SOURCE=2",
        "-Wall",
        "-Wextra",
        "-Werror",
        "-c",
        "bridge.c",
        "-o",
    ])
    .arg(&object);
    if let Some(include) = env::var_os("ALLDOC_DOC_JNI_INCLUDE") {
        cc.arg("-I")
            .arg(&include)
            .arg("-I")
            .arg(PathBuf::from(include).join("linux"));
    }
    run(&mut cc);
    run(
        Command::new(env::var("ALLDOC_DOC_AR").unwrap_or_else(|_| "ar".into()))
            .arg("crs")
            .arg(out.join("libzfmbridge.a"))
            .arg(object),
    );
    println!("cargo:rustc-link-search=native={}", out.display());
    println!("cargo:rustc-link-lib=static=zfmbridge");
}
