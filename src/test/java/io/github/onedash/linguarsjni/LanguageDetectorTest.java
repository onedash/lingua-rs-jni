package io.github.onedash.linguarsjni;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.SortedMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class LanguageDetectorTest {
    @Test
    void detectsLanguagesAndReturnsSortedConfidences() {
        try (LanguageDetector detector = LanguageDetectorBuilder.fromLanguages(
                Language.ENGLISH, Language.FRENCH, Language.GERMAN
        ).build()) {
            assertThat(detector.detectLanguageOf("The quick brown fox jumps over the lazy dog"))
                    .isEqualTo(Language.ENGLISH);

            SortedMap<Language, Double> values = detector.computeLanguageConfidenceValues(
                    "Bonjour tout le monde, comment allez-vous aujourd'hui?"
            );
            assertThat(values.firstKey()).isEqualTo(Language.FRENCH);
            assertThat(values.values().stream().mapToDouble(Double::doubleValue).sum())
                    .isCloseTo(1.0, offset(1e-9));
            assertThat(List.copyOf(values.values())).isSortedAccordingTo((left, right) -> Double.compare(right, left));
        }
    }

    @Test
    void handlesInputWithoutLetters() {
        try (LanguageDetector detector = LanguageDetectorBuilder.fromLanguages(
                Language.ENGLISH, Language.FRENCH
        ).build()) {
            assertThat(detector.computeLanguageConfidenceValues("123 !!!")).isEmpty();
            assertThat(detector.detectLanguageOf("")).isEqualTo(Language.UNKNOWN);
            assertThat(detector.computeLanguageConfidence("123 !!!", Language.ENGLISH)).isZero();
        }
    }

    /**
     * The confidence map is sorted by value, so its comparator has to answer for languages that
     * are not in the map at all. Looking one up used to unbox a null confidence.
     */
    @Test
    void answersForLanguagesTheDetectorWasNotBuiltWith() {
        try (LanguageDetector detector = LanguageDetectorBuilder.fromLanguages(
                Language.ENGLISH, Language.FRENCH
        ).build()) {
            assertThat(detector.computeLanguageConfidence("This is English", Language.SPANISH)).isZero();

            SortedMap<Language, Double> values = detector.computeLanguageConfidenceValues("This is English");
            assertThat(values.containsKey(Language.SPANISH)).isFalse();
            assertThat(values.get(Language.SPANISH)).isNull();
            assertThat(values.getOrDefault(Language.SPANISH, 0.0)).isZero();
            assertThat(values.get(Language.ENGLISH)).isNotNull();
        }
    }

    @Test
    void singleLanguageConfidenceMatchesTheFullMap() {
        try (LanguageDetector detector = LanguageDetectorBuilder.fromLanguages(
                Language.ENGLISH, Language.FRENCH, Language.GERMAN
        ).build()) {
            String text = "Der schnelle braune Fuchs springt uber den faulen Hund";
            SortedMap<Language, Double> values = detector.computeLanguageConfidenceValues(text);
            for (Language language : detector.getLanguages()) {
                assertThat(detector.computeLanguageConfidence(text, language))
                        .isCloseTo(values.get(language), offset(1e-12));
            }
        }
    }

    /**
     * Language names used to be lower-cased with the default locale before crossing into Rust.
     * In a Turkish locale "ENGLISH" lower-cases with a dotless i, which no longer parses.
     */
    @Test
    void worksUnderALocaleWithNonAsciiCaseRules() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.of("tr", "TR"));
            try (LanguageDetector detector = LanguageDetectorBuilder.fromLanguages(
                    Language.ENGLISH, Language.TURKISH
            ).build()) {
                assertThat(detector.detectLanguageOf("The quick brown fox jumps over the lazy dog"))
                        .isEqualTo(Language.ENGLISH);
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void preservesTextOutsideTheBasicMultilingualPlane() {
        try (LanguageDetector detector = LanguageDetectorBuilder.fromLanguages(
                Language.ENGLISH, Language.JAPANESE
        ).build()) {
            // Emoji are supplementary-plane code points, so they exercise the surrogate handling
            // in the JNI modified-UTF-8 conversion.
            assertThat(detector.detectLanguageOf("Hello 👋 world, this is an English sentence"))
                    .isEqualTo(Language.ENGLISH);
            assertThat(detector.detectLanguageOf("これは日本語の文章です 🎉"))
                    .isEqualTo(Language.JAPANESE);
        }
    }

    @Test
    void exposesItsLanguagesInOrdinalOrder() {
        try (LanguageDetector detector = LanguageDetectorBuilder.fromLanguages(
                Language.GERMAN, Language.ENGLISH, Language.FRENCH
        ).build()) {
            assertThat(detector.getLanguages())
                    .containsExactly(Language.ENGLISH, Language.FRENCH, Language.GERMAN);
        }
    }

    @Test
    void rejectsNullText() {
        try (LanguageDetector detector = LanguageDetectorBuilder.fromLanguages(Language.ENGLISH, Language.FRENCH).build()) {
            assertThatThrownBy(() -> detector.detectLanguageOf(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> detector.computeLanguageConfidenceValues(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> detector.computeLanguageConfidence(null, Language.ENGLISH))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void reportsUseAfterCloseInsteadOfCrashing() {
        LanguageDetector detector = LanguageDetectorBuilder.fromLanguages(Language.ENGLISH, Language.FRENCH).build();
        detector.close();
        detector.close(); // idempotent

        assertThatThrownBy(() -> detector.detectLanguageOf("This is English"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void validatesBuilderArguments() {
        assertThatThrownBy(() -> LanguageDetectorBuilder.fromLanguages(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LanguageDetectorBuilder.fromLanguages(Language.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LanguageDetectorBuilder.fromLanguages(Language.ENGLISH)
                .withMinimumRelativeDistance(1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LanguageDetectorBuilder.fromLanguages(Language.ENGLISH)
                .withMinimumRelativeDistance(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsEveryLanguageToADistinctIsoCode() {
        for (Language language : Language.all()) {
            assertThat(Language.getByIsoCode639_1(language.getIsoCode639_1())).isEqualTo(language);
        }
        assertThatThrownBy(() -> Language.getByIsoCode639_1(IsoCode639_1.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsFromIsoCodes() {
        try (LanguageDetector detector = LanguageDetectorBuilder
                .fromIsoCodes639_1(IsoCode639_1.EN, IsoCode639_1.FR)
                .build()) {
            assertThat(detector.getLanguages()).containsExactly(Language.ENGLISH, Language.FRENCH);
        }
    }

    @Test
    void excludesLanguagesOnRequest() {
        try (LanguageDetector detector = LanguageDetectorBuilder
                .fromAllLanguagesWithout(Language.LATIN, Language.ESPERANTO)
                .build()) {
            assertThat(detector.getLanguages())
                    .hasSize(Language.all().size() - 2)
                    .doesNotContain(Language.LATIN, Language.ESPERANTO, Language.UNKNOWN);
        }
    }

    @Test
    void honoursMinimumRelativeDistance() {
        // "hi" is ambiguous; a large required margin has to push the answer to UNKNOWN.
        try (LanguageDetector strict = LanguageDetectorBuilder
                .fromLanguages(Language.ENGLISH, Language.GERMAN, Language.DUTCH)
                .withMinimumRelativeDistance(0.99)
                .build()) {
            assertThat(strict.detectLanguageOf("hi")).isEqualTo(Language.UNKNOWN);
        }
    }
}
