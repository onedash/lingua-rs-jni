# lingua-rs-jni

[![Build](https://github.com/onedash/lingua-rs-jni/actions/workflows/build.yml/badge.svg)](https://github.com/onedash/lingua-rs-jni/actions/workflows/build.yml)

Java bindings for [lingua-rs 1.8.0](https://github.com/pemistahl/lingua-rs), the Rust
rewrite of the Lingua language detector.

The language models live in the native library's read-only data segment, not on the JVM
heap, so a detector covering all 75 languages adds a few megabytes of Java heap instead of
gigabytes. Applications need no Rust toolchain and no system-installed native library at
runtime.

## Supported platforms

| Platform | Artifact classifier | Runtime requirement |
| --- | --- | --- |
| Alpine Linux x86-64 | `linux-x86_64-musl` | musl (`eclipse-temurin:21-jre-alpine`) |
| Windows x86-64 | `windows-x86_64` | Windows 10 / Server 2016 or newer. The CRT is linked statically, so no Visual C++ Redistributable is needed. |
| macOS ARM64 | `macos-aarch64` | macOS 11 or newer |

CI asserts each of those requirements on every build, so they cannot regress silently.
Other targets (Linux with glibc, Linux ARM64 and macOS x86-64) are not published; see
[Building from source](#building-from-source).

The Alpine library dynamically uses the JVM process's musl libc. Its Rust dependencies,
language models and GCC unwinder are embedded, so the runtime image does not need `libgcc`.

## Installing

Two artifacts exist for every release:

* the **universal JAR** (no classifier) bundles all three supported native libraries — one
  dependency for Windows x86-64, Alpine Linux x86-64 and macOS ARM64, below 500 MB;
* three **per-platform JARs** carry the same Java classes plus one native library, at
  roughly 165 MB each.

Depend on exactly one of them. A production service should normally take the per-platform
JAR so its container image does not carry two unused native libraries.

### From GitHub Packages (recommended)

GitHub Packages is a real Maven repository, so Maven resolves it like any other. It requires
authentication **even though this repository is public** — that is a GitHub restriction, not
a choice made here.

**1. Create a token.** On GitHub, *Settings → Developer settings → Personal access tokens →
Tokens (classic)*, with the `read:packages` scope. Fine-grained tokens do not work with the
Maven registry.

**2. Add the server to `~/.m2/settings.xml`.** Keep the token out of the file itself by
reading it from the environment:

```xml
<settings>
  <servers>
    <server>
      <id>github-onedash</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>${env.GITHUB_PACKAGES_TOKEN}</password>
    </server>
  </servers>
</settings>
```

Then export `GITHUB_PACKAGES_TOKEN` in your shell, your CI secrets, and your Docker build
args. On a CI runner belonging to your own GitHub organisation, the built-in `GITHUB_TOKEN`
works and no personal token is needed.

**3. Add the repository and the dependency to your application's `pom.xml`.** The
`<repository>` id must match the `<server>` id above.

```xml
<repositories>
  <repository>
    <id>github-onedash</id>
    <url>https://maven.pkg.github.com/onedash/lingua-rs-jni</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>io.github.onedash</groupId>
    <artifactId>lingua-rs-jni</artifactId>
    <version>1.8.0-1</version>
    <!-- Drop <classifier> to get the universal JAR instead. -->
    <classifier>${lingua.classifier}</classifier>
  </dependency>
</dependencies>
```

**4. Choose the classifier.** If you develop on one platform and deploy to another, let the
build host decide, and override it wherever the two differ:

```xml
<properties>
  <!-- Used when no profile below matches, and by `mvn -Dlingua.classifier=...`. -->
  <lingua.classifier>linux-x86_64-musl</lingua.classifier>
</properties>

<profiles>
  <profile>
    <id>lingua-windows</id>
    <activation><os><family>windows</family><arch>amd64</arch></os></activation>
    <properties><lingua.classifier>windows-x86_64</lingua.classifier></properties>
  </profile>
  <profile>
    <id>lingua-macos</id>
    <activation><os><family>mac</family><arch>aarch64</arch></os></activation>
    <properties><lingua.classifier>macos-aarch64</lingua.classifier></properties>
  </profile>
  <profile>
    <id>lingua-linux</id>
    <activation><os><family>unix</family><name>Linux</name><arch>amd64</arch></os></activation>
    <properties><lingua.classifier>linux-x86_64-musl</lingua.classifier></properties>
  </profile>
</profiles>
```

> These profiles are evaluated on the **build** host. If you build your deployable JAR on
> Windows and run it in an Alpine container, pass `-Dlingua.classifier=linux-x86_64-musl` to that
> build, or use the universal JAR. Building inside the Docker image, which is the usual
> setup, needs no override.

### From GitHub Releases

Every tag also attaches the JARs and a `SHA256SUMS` file to a GitHub Release. Releases are
plain file hosting, not a Maven repository: Maven cannot resolve a release asset by URL. To
use one, install it into the local repository first. This has to be repeated on every
developer machine and every fresh CI runner, which is why GitHub Packages is preferred.

```bash
VERSION=1.8.0-1
JAR=lingua-rs-jni-${VERSION}-linux-x86_64-musl.jar

curl -fL -o "$JAR" \
  "https://github.com/onedash/lingua-rs-jni/releases/download/v${VERSION}/${JAR}"

mvn org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file \
  -Dfile="$JAR" \
  -DgroupId=io.github.onedash \
  -DartifactId=lingua-rs-jni \
  -Dversion="$VERSION" \
  -Dclassifier=linux-x86_64-musl \
  -Dpackaging=jar \
  -DgeneratePom=true
```

```powershell
$version = "1.8.0-1"
$jar = "lingua-rs-jni-$version-windows-x86_64.jar"
Invoke-WebRequest "https://github.com/onedash/lingua-rs-jni/releases/download/v$version/$jar" -OutFile $jar

mvn org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file `
    "-Dfile=$jar" -DgroupId=io.github.onedash -DartifactId=lingua-rs-jni `
    "-Dversion=$version" -Dclassifier=windows-x86_64 -Dpackaging=jar -DgeneratePom=true
```

Omit `-Dclassifier` when installing the universal JAR.

## Usage

```java
import io.github.onedash.linguarsjni.Language;
import io.github.onedash.linguarsjni.LanguageDetector;
import io.github.onedash.linguarsjni.LanguageDetectorBuilder;

public class Example {
    // Build once, at startup, and share it. Detectors are thread-safe.
    private static final LanguageDetector DETECTOR = LanguageDetectorBuilder
            .fromLanguages(Language.ENGLISH, Language.FRENCH, Language.GERMAN, Language.UKRAINIAN)
            .withPreloadedLanguageModels()
            .build();

    public static void main(String[] args) {
        Language language = DETECTOR.detectLanguageOf("The quick brown fox jumps over the lazy dog");
        System.out.println(language);                                        // ENGLISH
        System.out.println(DETECTOR.computeLanguageConfidenceValues("Bonjour"));
        System.out.println(DETECTOR.computeLanguageConfidence("Bonjour", Language.FRENCH));
    }
}
```

`detectLanguageOf` returns `Language.UNKNOWN` when the text contains no letters or no
language wins by a clear enough margin. `computeLanguageConfidenceValues` returns a
`SortedMap` ordered by descending confidence; looking up a language the detector was not
built with returns `null`, and `computeLanguageConfidence` returns `0.0` for it.

`LanguageDetector` implements `AutoCloseable`. Closing is optional — a `Cleaner` releases the
native detector when the object becomes unreachable — but closing explicitly frees it at a
predictable point. Calling a detection method after `close()` throws `IllegalStateException`,
including when the close races an in-flight detection on another thread; it never corrupts
the process.

### Builder API

```
fromAllLanguages()                fromLanguages(Language...)
fromAllSpokenLanguages()          fromLanguages(Collection<Language>)
fromAllLanguagesWithout(Language...)   fromIsoCodes639_1(IsoCode639_1...)

withMinimumRelativeDistance(double)    withLowAccuracyMode()    withPreloadedLanguageModels()
```

Not wrapped from lingua-rs: `detect_multiple_languages_of` (mixed-language segmentation), the
`*_in_parallel` batch methods, the script-based factories
(`from_all_languages_with_{arabic,cyrillic,devanagari,latin}_script`), and everything ISO
639-3. Open an issue if you need one.

## Migrating from `com.github.pemistahl:lingua:1.2.2`

The API is deliberately close to the JVM library. In most projects the change is the import
package plus the dependency coordinates:

```diff
-import com.github.pemistahl.lingua.api.Language;
-import com.github.pemistahl.lingua.api.LanguageDetector;
-import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
+import io.github.onedash.linguarsjni.Language;
+import io.github.onedash.linguarsjni.LanguageDetector;
+import io.github.onedash.linguarsjni.LanguageDetectorBuilder;
```

Behavioural differences worth knowing:

* Language models are off-heap. You can usually cut a large chunk from `-Xmx`.
* `LanguageDetector` is now `AutoCloseable`.
* The detection results come from lingua-rs 1.8.0, not Lingua 1.2.2, so individual
  classifications can differ. Re-check any accuracy thresholds your service relies on.
* The API surface listed above is what exists; anything else from 1.2.2 is not wrapped yet.

## Performance notes

Everything below was measured with one shared detector across many threads.

* **The size of the language set dominates cost.** Restricting a detector to the handful of
  languages you actually expect was roughly an order of magnitude faster per call than
  `fromAllLanguages()` in local measurements. This is by far the biggest lever.
* `withLowAccuracyMode()` gives a further speedup at some accuracy cost, and is most useful
  for longer texts.
* `withPreloadedLanguageModels()` moves lazy model loading off the first requests. Use it in
  a service so that startup, not the first user, pays for it.
* **Sharing one detector across threads costs the same as one detector per thread** — the
  handle registry uses a read-write lock that is only held long enough to look the detector
  up, and detection itself runs lock-free. Build one detector and share it.
* If several confidence values are needed for the same text, call
  `computeLanguageConfidenceValues(text)` once and reuse the returned map.

Absolute throughput depends heavily on hardware, text length and language set; benchmark on
your own hosts rather than trusting a number from someone else's laptop.

## Runtime behaviour

On first use the bundled native library is extracted to a content-addressed cache directory
under `java.io.tmpdir`:

```
<tmpdir>/lingua-rs-jni-<user>/<platform>-<hash>/liblingua_rs_jni.so
```

The path embeds a SHA-256 of the library and the cached file is verified against the
build-time checksum shipped in the JAR on every start. So the ~290 MB library is written once
per machine per version rather than once per JVM start, a half-written file from a killed JVM
is detected and rewritten, and a file planted by another local user in a shared temp
directory fails verification instead of being loaded.

System properties:

| Property | Effect |
| --- | --- |
| `lingua.native.path` | Absolute path of a library to load directly, skipping extraction. Use it when the temp directory is mounted `noexec`, or to share one copy across containers. |
| `lingua.native.cacheDir` | Directory to extract into. Defaults to `java.io.tmpdir`. |

```bash
java -Dlingua.native.path=/opt/lingua/liblingua_rs_jni.so -jar application.jar
java -Dlingua.native.cacheDir=/var/cache/lingua -jar application.jar
```

A JVM can map a native library into one classloader only. In an application server that
deploys several applications, put this library on a shared parent classloader rather than
inside each deployment, or point every deployment at one `lingua.native.path`.

## Building from source

Requirements: JDK 21, Maven 3.9+, a stable Rust toolchain, and a C toolchain for the linker
(MSVC Build Tools on Windows, Alpine `build-base` on Linux, Xcode Command Line Tools on macOS).

```bash
mvn clean verify      # builds the native library for this machine and runs all tests
cd native && cargo test --release --locked
```

The result is `target/lingua-rs-jni-<version>.jar`, containing the Java classes and the
native library for the current machine.

To build the Alpine Linux artifact from any Docker host:

```bash
docker build --target artifacts --output dist .
```

### Smaller libraries

Most of the ~290 MB is the finite-state-transducer models for all 75 languages. Build only
the ones you need using lingua's per-language cargo features:

```bash
mvn clean verify -Dnative.cargo.args="--no-default-features --features english,french,german,ukrainian"
```

The Java `Language` enum still lists all 75; asking such a build for a language it was not
compiled with fails at `build()` with a clear message. Feature names are the lowercase
language names, listed in [lingua's `Cargo.toml`](https://github.com/pemistahl/lingua-rs/blob/main/Cargo.toml).

### Unsupported targets

To run on Linux with glibc, Linux ARM64 or macOS x86-64, build the crate for that target on such a host
and point the JVM at the result:

```bash
cd native && cargo build --release --locked
java -Dlingua.native.path=$PWD/target/release/liblingua_rs_jni.so -jar application.jar
```

Adding a target to the published set means adding a matrix entry in
`.github/workflows/build.yml`, a case in `NativeLibraryLoader.platform`, and a JAR execution
in the `dist` profile.

## Releasing

```bash
git tag v1.8.0-2
git push origin v1.8.0-2
```

The workflow then builds and tests on all three platforms, assembles the universal and
per-platform JARs plus sources and javadoc, publishes them to GitHub Packages, and attaches
them with a `SHA256SUMS` file to a GitHub Release. No secrets are needed: the built-in
`GITHUB_TOKEN` covers both.

Version numbers are `<lingua version>-<wrapper revision>`, so `1.8.0-2` is the second wrapper
release against lingua-rs 1.8.0. GitHub Packages refuses to overwrite an existing version, so
re-running a tag build that already published will fail; publish a new revision instead.

Ordinary pushes and pull requests run the whole build without publishing anything. Trigger
the workflow manually (*Actions → build → Run workflow*) to get the JARs as workflow
artifacts without cutting a release.

## Artifact sizes

| Artifact | Size |
| --- | --- |
| Native library, uncompressed | ~290 MB |
| Per-platform JAR | ~165 MB |
| Universal JAR | ~490 MB |

This is the trade the design makes: a large binary in exchange for models that cost no JVM
heap and need no loading at runtime. Cut it down with per-language cargo features if the size
matters more than covering every language.

## License

Apache License 2.0. Lingua and its language models are also Apache 2.0; see
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
