package io.github.onedash.linguarsjni;

/** Runs without test dependencies in the same minimal Alpine JRE image as BackendMono. */
public final class AlpineRuntimeSmoke {
    private AlpineRuntimeSmoke() {
    }

    public static void main(String[] args) {
        try (LanguageDetector detector = LanguageDetectorBuilder.fromLanguages(
                Language.ENGLISH, Language.GERMAN
        ).build()) {
            Language actual = detector.detectLanguageOf("The quick brown fox jumps over the lazy dog");
            if (actual != Language.ENGLISH) {
                throw new AssertionError("Expected ENGLISH, got " + actual);
            }
        }
    }
}
