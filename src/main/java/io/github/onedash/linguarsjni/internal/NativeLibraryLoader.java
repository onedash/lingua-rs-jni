package io.github.onedash.linguarsjni.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Extracts the bundled native library to a content-addressed cache directory and loads it.
 *
 * <p>The cache path embeds a SHA-256 of the library, and the cached file's digest is verified
 * on every start. That gives three properties a plain "copy to a fresh temp file" loader does
 * not have:
 *
 * <ul>
 *   <li>The ~290 MB library is written once per machine per version instead of once per JVM
 *       start. {@code deleteOnExit} cannot remove a loaded library on Windows, so the naive
 *       approach leaks the full library into the temp directory on every restart.</li>
 *   <li>A file planted by another local user in a shared temp directory fails verification and
 *       is overwritten rather than loaded.</li>
 *   <li>A file left half-written by a killed JVM fails verification and is rewritten.</li>
 * </ul>
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code lingua.native.path} &mdash; absolute path of a library to load directly,
 *       skipping extraction entirely. Use this on hosts whose temp directory is mounted
 *       {@code noexec}, or to share one copy across containers.</li>
 *   <li>{@code lingua.native.cacheDir} &mdash; directory to extract into.
 *       Defaults to {@code java.io.tmpdir}.</li>
 * </ul>
 */
final class NativeLibraryLoader {
    private static final String PATH_PROPERTY = "lingua.native.path";
    private static final String CACHE_DIR_PROPERTY = "lingua.native.cacheDir";

    private static boolean loaded;

    private NativeLibraryLoader() {}

    static synchronized void load() {
        if (loaded) {
            return;
        }

        String explicitPath = System.getProperty(PATH_PROPERTY);
        if (explicitPath != null && !explicitPath.isBlank()) {
            loadFrom(Path.of(explicitPath).toAbsolutePath());
        } else {
            loadFrom(extract(platform(), fileName()));
        }
        loaded = true;
    }

    /** Returns the cached library path, extracting it first if the cache misses. */
    private static Path extract(String platform, String fileName) {
        String resource = "/native/" + platform + "/" + fileName;
        byte[] expectedDigest = expectedDigest(resource);

        Path directory = cacheDirectory(platform + "-" + HexFormat.of().formatHex(expectedDigest, 0, 16));
        Path library = directory.resolve(fileName);
        if (digestMatches(library, expectedDigest)) {
            return library;
        }

        // Write to a private name first so a concurrent JVM never observes a partial file, and
        // so a crash here leaves the previous good copy (if any) untouched.
        Path staging = directory.resolve(fileName + "." + ProcessHandle.current().pid() + ".tmp");
        try (InputStream input = open(resource)) {
            byte[] actualDigest = copyAndDigest(input, staging);
            if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                throw new IllegalStateException("Native library " + resource + " is corrupt: expected SHA-256 "
                        + HexFormat.of().formatHex(expectedDigest) + " but the JAR contains "
                        + HexFormat.of().formatHex(actualDigest));
            }
            makeExecutable(staging);
            publish(staging, library, expectedDigest);
            return library;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot extract " + resource + " to " + directory
                    + "; set -D" + CACHE_DIR_PROPERTY + " to a writable directory"
                    + " or -D" + PATH_PROPERTY + " to a pre-extracted library", error);
        } finally {
            deleteQuietly(staging);
        }
    }

    /**
     * Moves the staged file into place. Windows refuses to replace a library another process
     * has mapped, which is fine: that process already published an identical file, so the
     * failure is only interesting if the target still does not verify.
     */
    private static void publish(Path staging, Path library, byte[] expectedDigest) throws IOException {
        try {
            Files.move(staging, library, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            if (!digestMatches(library, expectedDigest)) {
                throw error;
            }
        }
    }

    private static byte[] copyAndDigest(InputStream input, Path target) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[1 << 16];
        try (OutputStream output = Files.newOutputStream(target)) {
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static boolean digestMatches(Path file, byte[] expected) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        MessageDigest digest = sha256();
        byte[] buffer = new byte[1 << 16];
        try (InputStream input = Files.newInputStream(file)) {
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException error) {
            return false;
        }
        return MessageDigest.isEqual(expected, digest.digest());
    }

    /**
     * Reads the digest written next to the library at build time. Falls back to hashing the
     * resource itself so a hand-assembled JAR still works, at the cost of inflating the whole
     * library twice on a cache miss.
     */
    private static byte[] expectedDigest(String resource) {
        try (InputStream sidecar = NativeLibraryLoader.class.getResourceAsStream(resource + ".sha256")) {
            if (sidecar != null) {
                String hex = new String(sidecar.readAllBytes(), StandardCharsets.US_ASCII).trim();
                if (hex.length() == 64) {
                    return HexFormat.of().parseHex(hex);
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Fall through to hashing the library itself.
        }
        try (InputStream input = open(resource)) {
            MessageDigest digest = sha256();
            byte[] buffer = new byte[1 << 16];
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read " + resource, error);
        }
    }

    private static InputStream open(String resource) {
        InputStream input = NativeLibraryLoader.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException("This JAR does not bundle " + resource
                    + ". Use the universal JAR, the JAR whose classifier matches this platform,"
                    + " or set -D" + PATH_PROPERTY + " to an externally provided library.");
        }
        return input;
    }

    /** Per-user so that the owner-only permissions below cannot lock out a second account. */
    private static Path cacheDirectory(String leaf) {
        String root = System.getProperty(CACHE_DIR_PROPERTY);
        if (root == null || root.isBlank()) {
            root = System.getProperty("java.io.tmpdir");
        }
        Path directory = Path.of(root)
                .resolve("lingua-rs-jni-" + sanitize(System.getProperty("user.name", "unknown")))
                .resolve(leaf);
        try {
            createPrivateDirectories(directory);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot create " + directory
                    + "; set -D" + CACHE_DIR_PROPERTY + " to a writable directory", error);
        }
        // createDirectories follows symlinks, so a pre-planted link could silently redirect the
        // extraction. Verifying the final component is a real directory closes that.
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(directory + " is not a directory");
        }
        return directory;
    }

    private static void createPrivateDirectories(Path directory) throws IOException {
        if (supportsPosixPermissions(directory)) {
            Files.createDirectories(directory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(directory);
        }
    }

    private static void makeExecutable(Path file) throws IOException {
        if (supportsPosixPermissions(file)) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static boolean supportsPosixPermissions(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing != null
                && Files.getFileAttributeView(existing, PosixFileAttributeView.class) != null;
    }

    private static void loadFrom(Path library) {
        try {
            System.load(library.toString());
        } catch (UnsatisfiedLinkError error) {
            UnsatisfiedLinkError enriched = new UnsatisfiedLinkError(
                    "Cannot load " + library + ": " + error.getMessage() + hint(error, library));
            enriched.initCause(error);
            throw enriched;
        }
    }

    private static String hint(UnsatisfiedLinkError error, Path library) {
        String message = String.valueOf(error.getMessage());
        if (message.contains("another classloader")) {
            return ". A JVM can map a native library into one classloader only. Load lingua-rs-jni"
                    + " from a shared parent classloader instead of from each deployed application.";
        }
        if (message.contains("failed to map segment") || message.contains("Permission denied")) {
            return ". " + library.getParent() + " is probably mounted noexec; set -D" + CACHE_DIR_PROPERTY
                    + " to an executable directory or -D" + PATH_PROPERTY + " to a pre-extracted library.";
        }
        if (message.contains("GLIBC")) {
            return ". This host's glibc is older than the one the release was built against;"
                    + " build the native library locally with `mvn -pl . package` on this host.";
        }
        return "";
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by every Java SE implementation", error);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The staging file is disposable; a leftover is harmless.
        }
    }

    private static String sanitize(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length() && index < 64; index++) {
            char character = value.charAt(index);
            builder.append(Character.isLetterOrDigit(character) ? character : '_');
        }
        return builder.isEmpty() ? "unknown" : builder.toString();
    }

    private static String platform() {
        return platform(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static String platform(String osName, String architecture) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        boolean x86_64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.contains("win") && x86_64) {
            return "windows-x86_64";
        }
        if ((os.contains("mac") || os.contains("darwin")) && arm64) {
            return "macos-aarch64";
        }
        if (os.contains("linux") && x86_64) {
            return "linux-x86_64";
        }
        throw new IllegalStateException("Unsupported platform: " + osName + " / " + architecture
                + ". Prebuilt libraries exist for windows-x86_64, linux-x86_64 and macos-aarch64;"
                + " build one from source and point -D" + PATH_PROPERTY + " at it.");
    }

    private static String fileName() {
        return fileName(System.getProperty("os.name"));
    }

    static String fileName(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "lingua_rs_jni.dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "liblingua_rs_jni.dylib";
        }
        return "liblingua_rs_jni.so";
    }
}
