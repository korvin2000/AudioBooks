package com.fanok.audiobooks.region;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Where the user's internet connection comes out ({@link AppRegion}). The order is the code of the region in the
 * app's IP table ({@link IpRegions}, tools/region/build_ip_regions.py).
 */
public enum Region {
    OTHER, RUSSIA, UKRAINE, WEST;

    /**
     * The west: the EU (with its outermost regions and Åland), the UK (with its crown dependencies and Gibraltar), the
     * US (with its territories), Japan, Switzerland, Norway, Iceland, Liechtenstein, Canada, Australia and Israel.
     * Keep in step with tools/region/build_ip_regions.py.
     */
    static final Set<String> WEST_COUNTRIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "IE", "IT", "LV", "LT", "LU", "MT",
            "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "HU",
            "AX", "GF", "GP", "MQ", "RE", "YT", "MF",
            "GB", "US", "JP", "CH", "NO", "IS", "LI", "CA", "AU", "IL",
            "PR", "GU", "VI", "AS", "MP", "UM",
            "JE", "GG", "IM", "GI")));

    /** Two letters that name no country: the EU, Asia-Pacific, unknown, user-assigned "unknown". */
    private static final Set<String> NO_COUNTRY = new HashSet<>(Arrays.asList("EU", "AP", "ZZ", "XX"));

    /**
     * The region of an ISO 3166-1 alpha-2 code, in any case ("UK" as GB); null when it names no country: empty, not
     * two Latin letters (A1, T1, a Russian name), or {@link #NO_COUNTRY}.
     */
    public static Region ofCountry(String code) {
        String cc = country(code);
        if (cc == null) {
            return null;
        }
        switch (cc) {
            case "RU":
                return RUSSIA;
            case "UA":
                return UKRAINE;
            default:
                return WEST_COUNTRIES.contains(cc) ? WEST : OTHER;
        }
    }

    /** {@code code} as an upper-case ISO country code; null when it names no country (see {@link #ofCountry}). */
    static String country(String code) {
        if (code == null) {
            return null;
        }
        String cc = code.trim().toUpperCase(Locale.ROOT);
        if (cc.length() != 2 || !isLatin(cc.charAt(0)) || !isLatin(cc.charAt(1)) || NO_COUNTRY.contains(cc)) {
            return null;
        }
        return cc.equals("UK") ? "GB" : cc;
    }

    private static boolean isLatin(char c) {
        return c >= 'A' && c <= 'Z';
    }
}
