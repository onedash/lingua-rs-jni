package io.github.onedash.linguarsjni.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Extracts the bundled native library once and loads it. */
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
        Path library = explicitPath == null || explicitPath.isBlank()
                ? extract(platform(), fileName())
                : Path.of(explicitPath).toAbsolutePath();
        System.load(library.toString());
        loaded = true;
    }

    private static Path extract(String platform, String fileName) {
        String resource = "/native/" + platform + "/" + fileName;
        byte[] expectedDigest = expectedDigest(resource);
        String version = platform + "-" + HexFormat.of().formatHex(expectedDigest, 0, 16);
        Path directory = cacheDirectory(version);
        Path library = directory.resolve(fileName);
        if (digestMatches(library, expectedDigest)) {
            return library;
        }

        Path staging;
        try {
            staging = Files.createTempFile(directory, fileName + ".", ".tmp");
        } catch (IOException error) {
            throw extractionFailure(resource, directory, error);
        }

        try (InputStream input = open(resource)) {
            byte[] actualDigest = copyAndDigest(input, staging);
            if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                throw new IllegalStateException("Native library " + resource + " does not match its checksum");
            }
            publish(staging, library, expectedDigest);
            return library;
        } catch (IOException error) {
            throw extractionFailure(resource, directory, error);
        } finally {
            deleteQuietly(staging);
        }
    }

    /** Another JVM may have published the same library while this one was copying it. */
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
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = sha256();
            byte[] buffer = new byte[1 << 16];
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                digest.update(buffer, 0, read);
            }
            return MessageDigest.isEqual(expected, digest.digest());
        } catch (IOException error) {
            return false;
        }
    }

    private static byte[] expectedDigest(String resource) {
        String sidecar = resource + ".sha256";
        try (InputStream input = NativeLibraryLoader.class.getResourceAsStream(sidecar)) {
            if (input == null) {
                throw new IllegalStateException("This JAR does not bundle " + sidecar);
            }
            String value = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
            if (value.length() != 64) {
                throw new IllegalStateException("Invalid checksum in " + sidecar);
            }
            return HexFormat.of().parseHex(value);
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("Cannot read checksum " + sidecar, error);
        }
    }

    private static InputStream open(String resource) {
        InputStream input = NativeLibraryLoader.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException("This JAR does not bundle " + resource
                    + "; use the matching platform JAR or set -D" + PATH_PROPERTY);
        }
        return input;
    }

    private static Path cacheDirectory(String version) {
        String root = System.getProperty(CACHE_DIR_PROPERTY);
        if (root == null || root.isBlank()) {
            root = System.getProperty("java.io.tmpdir");
        }
        Path directory = Path.of(root)
                .toAbsolutePath()
                .resolve("lingua-rs-jni-" + sanitize(System.getProperty("user.name", "unknown")))
                .resolve(version);
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot create native library cache " + directory, error);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(directory + " is not a directory");
        }
        return directory;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    private static IllegalStateException extractionFailure(String resource, Path directory, IOException error) {
        return new IllegalStateException("Cannot extract " + resource + " to " + directory
                + "; set -D" + CACHE_DIR_PROPERTY + " or -D" + PATH_PROPERTY, error);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A staging file is safe to leave behind.
        }
    }

    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^\\p{L}\\p{N}]", "_");
        return sanitized.isEmpty() ? "unknown" : sanitized;
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
            return "linux-x86_64-musl";
        }
        throw new IllegalStateException("Unsupported platform: " + osName + " / " + architecture
                + "; set -D" + PATH_PROPERTY + " to a compatible library");
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
