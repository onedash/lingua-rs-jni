# Third-party notices

The native library in this project statically links the following Rust crates. Exact
transitive versions are pinned in `native/Cargo.lock`.

## Lingua

[Lingua 1.8.0](https://github.com/pemistahl/lingua-rs) and its `lingua-*-language-model`
crates, copyright Peter M. Stahl, licensed under the Apache License 2.0. The language models
compiled into the native library are part of that project and carry the same license.

## jni

[jni 0.21.1](https://github.com/jni-rs/jni-rs), dual-licensed under the Apache License 2.0
and the MIT License. This project uses it under the Apache License 2.0.

## Transitive dependencies

The remaining crates reachable from those two are permissively licensed (Apache-2.0, MIT,
Unicode-3.0 or BSD variants). Run `cargo tree` or `cargo license` in `native/` for the full
list at a given lock revision.

## GCC runtime library

The Alpine native library statically links GCC's exception-handling runtime under the
[GCC Runtime Library Exception](https://www.gnu.org/licenses/gcc-exception-3.1.html).
