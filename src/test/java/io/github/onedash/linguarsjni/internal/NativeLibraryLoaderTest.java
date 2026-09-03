package io.github.onedash.linguarsjni.internal;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeLibraryLoaderTest {
    @Test
    void supportsReleaseTargets() {
        assertThat(NativeLibraryLoader.platform("Windows 11", "amd64")).isEqualTo("windows-x86_64");
        assertThat(NativeLibraryLoader.platform("Windows Server 2022", "x86_64")).isEqualTo("windows-x86_64");
        assertThat(NativeLibraryLoader.platform("Linux", "x86_64")).isEqualTo("linux-x86_64-musl");
        assertThat(NativeLibraryLoader.platform("Linux", "amd64")).isEqualTo("linux-x86_64-musl");
        assertThat(NativeLibraryLoader.platform("Mac OS X", "aarch64")).isEqualTo("macos-aarch64");
        assertThat(NativeLibraryLoader.platform("Darwin", "arm64")).isEqualTo("macos-aarch64");
    }

    @Test
    void rejectsUnsupportedTargets() {
        assertThatThrownBy(() -> NativeLibraryLoader.platform("Windows 11", "aarch64"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lingua.native.path");
        assertThatThrownBy(() -> NativeLibraryLoader.platform("Linux", "aarch64"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NativeLibraryLoader.platform("Mac OS X", "x86_64"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NativeLibraryLoader.platform("Linux", "ppc64le"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mapsPlatformsToLibraryFileNames() {
        assertThat(NativeLibraryLoader.fileName("Windows 11")).isEqualTo("lingua_rs_jni.dll");
        assertThat(NativeLibraryLoader.fileName("Mac OS X")).isEqualTo("liblingua_rs_jni.dylib");
        assertThat(NativeLibraryLoader.fileName("Linux")).isEqualTo("liblingua_rs_jni.so");
    }

    /**
     * The loader trusts the build-time checksum to decide whether its extraction cache is
     * usable, so a stale or missing sidecar would either reject a good cache forever or accept
     * a tampered library.
     */
    @Test
    void shipsAChecksumThatMatchesTheBundledLibrary() throws Exception {
        String platform = NativeLibraryLoader.platform(
                System.getProperty("os.name"), System.getProperty("os.arch"));
        String resource = "/native/" + platform + "/" + NativeLibraryLoader.fileName(System.getProperty("os.name"));

        String expected;
        try (InputStream sidecar = NativeLibraryLoaderTest.class.getResourceAsStream(resource + ".sha256")) {
            assertThat(sidecar).as("checksum sidecar for %s", resource).isNotNull();
            expected = new String(sidecar.readAllBytes(), StandardCharsets.US_ASCII).trim();
        }
        assertThat(expected).hasSize(64);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream library = NativeLibraryLoaderTest.class.getResourceAsStream(resource)) {
            assertThat(library).as("bundled library %s", resource).isNotNull();
            byte[] buffer = new byte[1 << 16];
            for (int read = library.read(buffer); read >= 0; read = library.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        assertThat(HexFormat.of().formatHex(digest.digest())).isEqualTo(expected);
    }
}
