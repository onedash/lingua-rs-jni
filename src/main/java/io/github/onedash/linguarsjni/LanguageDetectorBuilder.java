package io.github.onedash.linguarsjni;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;

/** Builds a native Lingua 1.8.0 detector. */
public final class LanguageDetectorBuilder {
    private final EnumSet<Language> languages;
    private double minimumRelativeDistance;
    private boolean lowAccuracy;
    private boolean preload;

    private LanguageDetectorBuilder(Collection<Language> languages) {
        if (languages.isEmpty()) {
            throw new IllegalArgumentException("LanguageDetector needs at least 1 language to choose from");
        }
        this.languages = EnumSet.copyOf(languages);
        this.languages.remove(Language.UNKNOWN);
        if (this.languages.isEmpty()) {
            throw new IllegalArgumentException("UNKNOWN cannot be the only language");
        }
    }

    public static LanguageDetectorBuilder fromAllLanguages() {
        return new LanguageDetectorBuilder(Language.all());
    }

    public static LanguageDetectorBuilder fromAllSpokenLanguages() {
        return new LanguageDetectorBuilder(Language.allSpokenOnes());
    }

    /** All supported languages except the given ones. */
    public static LanguageDetectorBuilder fromAllLanguagesWithout(Language... languages) {
        EnumSet<Language> remaining = EnumSet.copyOf(Language.all());
        remaining.removeAll(Arrays.asList(languages));
        return new LanguageDetectorBuilder(remaining);
    }

    public static LanguageDetectorBuilder fromLanguages(Language... languages) {
        return new LanguageDetectorBuilder(Arrays.asList(languages));
    }

    public static LanguageDetectorBuilder fromLanguages(Collection<Language> languages) {
        return new LanguageDetectorBuilder(languages);
    }

    public static LanguageDetectorBuilder fromIsoCodes639_1(IsoCode639_1... codes) {
        return new LanguageDetectorBuilder(Arrays.stream(codes)
                .map(Language::getByIsoCode639_1)
                .toList());
    }

    public LanguageDetectorBuilder withMinimumRelativeDistance(double distance) {
        // Negated comparison so that NaN is rejected too.
        if (!(distance >= 0.0 && distance <= 0.99)) {
            throw new IllegalArgumentException("Minimum relative distance must be between 0.0 and 0.99");
        }
        minimumRelativeDistance = distance;
        return this;
    }

    public LanguageDetectorBuilder withLowAccuracyMode() {
        lowAccuracy = true;
        return this;
    }

    public LanguageDetectorBuilder withPreloadedLanguageModels() {
        preload = true;
        return this;
    }

    public LanguageDetector build() {
        return new LanguageDetector(languages, minimumRelativeDistance, lowAccuracy, preload);
    }
}
