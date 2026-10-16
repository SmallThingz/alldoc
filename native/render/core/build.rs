fn main() {
    if std::env::var("TARGET")
        .unwrap_or_default()
        .ends_with("linux-android")
    {
        println!("cargo:rustc-link-arg-bin=render=-Wl,-z,max-page-size=16384");
    }
}
