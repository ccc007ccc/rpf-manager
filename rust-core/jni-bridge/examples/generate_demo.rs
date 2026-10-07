fn main() {
    let path = std::env::args()
        .nth(1)
        .expect("usage: generate_demo OUTPUT.rpf");
    std::fs::write(path, rpf_jni::fixtures::archive()).unwrap();
}
