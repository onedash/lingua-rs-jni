package io.github.onedash.linguarsjni;

import java.util.Locale;

/** ISO 639-1 codes supported by Lingua. */
public enum IsoCode639_1 {
    AF, AR, AZ, BE, BG, BN, BS, CA, CS, CY, DA, DE, EL, EN, EO, ES, ET, EU,
    FA, FI, FR, GA, GU, HE, HI, HR, HU, HY, ID, IS, IT, JA, KA, KK, KO, LA,
    LG, LT, LV, MI, MK, MN, MR, MS, NB, NL, NN, PA, PL, PT, RO, RU, SK, SL,
    SN, SO, SQ, SR, ST, SV, SW, TA, TE, TH, TL, TN, TR, TS, UK, UR, VI, XH,
    YO, ZH, ZU, NONE;

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
