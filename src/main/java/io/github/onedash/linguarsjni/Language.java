package io.github.onedash.linguarsjni;

import java.util.Arrays;
import java.util.List;

/** Languages supported by Lingua 1.8.0. */
public enum Language {
    AFRIKAANS(IsoCode639_1.AF),
    ALBANIAN(IsoCode639_1.SQ),
    ARABIC(IsoCode639_1.AR),
    ARMENIAN(IsoCode639_1.HY),
    AZERBAIJANI(IsoCode639_1.AZ),
    BASQUE(IsoCode639_1.EU),
    BELARUSIAN(IsoCode639_1.BE),
    BENGALI(IsoCode639_1.BN),
    BOKMAL(IsoCode639_1.NB),
    BOSNIAN(IsoCode639_1.BS),
    BULGARIAN(IsoCode639_1.BG),
    CATALAN(IsoCode639_1.CA),
    CHINESE(IsoCode639_1.ZH),
    CROATIAN(IsoCode639_1.HR),
    CZECH(IsoCode639_1.CS),
    DANISH(IsoCode639_1.DA),
    DUTCH(IsoCode639_1.NL),
    ENGLISH(IsoCode639_1.EN),
    ESPERANTO(IsoCode639_1.EO),
    ESTONIAN(IsoCode639_1.ET),
    FINNISH(IsoCode639_1.FI),
    FRENCH(IsoCode639_1.FR),
    GANDA(IsoCode639_1.LG),
    GEORGIAN(IsoCode639_1.KA),
    GERMAN(IsoCode639_1.DE),
    GREEK(IsoCode639_1.EL),
    GUJARATI(IsoCode639_1.GU),
    HEBREW(IsoCode639_1.HE),
    HINDI(IsoCode639_1.HI),
    HUNGARIAN(IsoCode639_1.HU),
    ICELANDIC(IsoCode639_1.IS),
    INDONESIAN(IsoCode639_1.ID),
    IRISH(IsoCode639_1.GA),
    ITALIAN(IsoCode639_1.IT),
    JAPANESE(IsoCode639_1.JA),
    KAZAKH(IsoCode639_1.KK),
    KOREAN(IsoCode639_1.KO),
    LATIN(IsoCode639_1.LA),
    LATVIAN(IsoCode639_1.LV),
    LITHUANIAN(IsoCode639_1.LT),
    MACEDONIAN(IsoCode639_1.MK),
    MALAY(IsoCode639_1.MS),
    MAORI(IsoCode639_1.MI),
    MARATHI(IsoCode639_1.MR),
    MONGOLIAN(IsoCode639_1.MN),
    NYNORSK(IsoCode639_1.NN),
    PERSIAN(IsoCode639_1.FA),
    POLISH(IsoCode639_1.PL),
    PORTUGUESE(IsoCode639_1.PT),
    PUNJABI(IsoCode639_1.PA),
    ROMANIAN(IsoCode639_1.RO),
    RUSSIAN(IsoCode639_1.RU),
    SERBIAN(IsoCode639_1.SR),
    SHONA(IsoCode639_1.SN),
    SLOVAK(IsoCode639_1.SK),
    SLOVENE(IsoCode639_1.SL),
    SOMALI(IsoCode639_1.SO),
    SOTHO(IsoCode639_1.ST),
    SPANISH(IsoCode639_1.ES),
    SWAHILI(IsoCode639_1.SW),
    SWEDISH(IsoCode639_1.SV),
    TAGALOG(IsoCode639_1.TL),
    TAMIL(IsoCode639_1.TA),
    TELUGU(IsoCode639_1.TE),
    THAI(IsoCode639_1.TH),
    TSONGA(IsoCode639_1.TS),
    TSWANA(IsoCode639_1.TN),
    TURKISH(IsoCode639_1.TR),
    UKRAINIAN(IsoCode639_1.UK),
    URDU(IsoCode639_1.UR),
    VIETNAMESE(IsoCode639_1.VI),
    WELSH(IsoCode639_1.CY),
    XHOSA(IsoCode639_1.XH),
    YORUBA(IsoCode639_1.YO),
    ZULU(IsoCode639_1.ZU),
    UNKNOWN(IsoCode639_1.NONE);

    private static final List<Language> ALL = Arrays.stream(values())
            .filter(language -> language != UNKNOWN)
            .toList();

    private static final Language[] BY_ISO_CODE = byIsoCode();

    private final IsoCode639_1 isoCode639_1;

    Language(IsoCode639_1 isoCode639_1) {
        this.isoCode639_1 = isoCode639_1;
    }

    public IsoCode639_1 getIsoCode639_1() {
        return isoCode639_1;
    }

    public static List<Language> all() {
        return ALL;
    }

    public static List<Language> allSpokenOnes() {
        return ALL.stream().filter(language -> language != LATIN).toList();
    }

    /** @throws IllegalArgumentException for {@link IsoCode639_1#NONE}, which no language uses */
    public static Language getByIsoCode639_1(IsoCode639_1 code) {
        Language language = BY_ISO_CODE[code.ordinal()];
        if (language == null) {
            throw new IllegalArgumentException("Unsupported ISO 639-1 code: " + code);
        }
        return language;
    }

    private static Language[] byIsoCode() {
        Language[] table = new Language[IsoCode639_1.values().length];
        for (Language language : ALL) {
            table[language.isoCode639_1.ordinal()] = language;
        }
        return table;
    }
}
