package com.fanok.audiobooks.fragment;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fanok.audiobooks.BuildConfig;
import com.fanok.audiobooks.R;
import com.fanok.audiobooks.Url;
import com.fanok.audiobooks.net.AppProxy;
import com.fanok.audiobooks.net.ProxyConfig;
import com.fanok.audiobooks.net.ProxyFinder;
import com.fanok.audiobooks.region.AppRegion;
import com.fanok.audiobooks.region.Region;
import com.fanok.audiobooks.search.Provider;
import com.fanok.audiobooks.search.SourceHealth;
import com.fanok.audiobooks.search.SourceInfo;
import com.fanok.audiobooks.search.SourceProbe;
import com.fanok.audiobooks.search.Sources;
import com.fanok.audiobooks.source.Sort;
import com.fanok.audiobooks.util.RussianResources;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * What the source screens say about a site, shared by the sources dialog, the source list, the card and the position
 * sheet: the status of the last check and its colour, the book count, the server, the age, how long ago a check was,
 * the region, whether "через прокси" goes through a proxy now. The rules (rounding, units, joining) are pure functions
 * that take their words as arguments, so JVM tests check them without resources; the {@code Context} forms only fetch
 * the words.
 */
public final class SourceTexts {

    /** Fixed, not the theme's: they must read on the light, the dark and the black background alike. */
    public static final int COLOR_UP = 0xFF4CAF50;
    public static final int COLOR_DOWN = 0xFFE53935;
    /** The status of a site never checked. */
    public static final String UNKNOWN = "—";
    static final String SEP = " · ";
    /** A book count below this is shown in full ("≈ 2 271"); from it on, in thousands. */
    static final int FULL_BELOW = 5000;
    /** From here the thousands are whole ("≈ 10 тыс."), below it they have tenths ("≈ 5,8 тыс."). */
    static final int WHOLE_FROM = 9950;
    /** Between the groups of digits of a number: a space that never breaks the line. */
    static final char GROUP = ' ';
    private static final Locale RUSSIAN = new Locale("ru");

    private SourceTexts() {
    }

    // ---------------------------------------------------------------- the last check

    /** The status column: the time of a site that answers, the kind of failure, "—" when unknown. */
    @NonNull
    public static String statusText(@NonNull Context context, @Nullable SourceProbe.Result result) {
        if (result == null) {
            return UNKNOWN;
        }
        switch (result.status) {
            case OK: // an older version kept no time
                return result.millis > 0 ? context.getString(R.string.sources_seconds, result.millis / 1000f) : UNKNOWN;
            case EMPTY:
                return context.getString(R.string.sources_status_empty);
            case BLOCKED:
                return context.getString(R.string.sources_status_blocked);
            case RATE_LIMITED:
                return context.getString(R.string.sources_status_rate_limited);
            case TIMEOUT:
                return context.getString(R.string.sources_status_timeout);
            default:
                return context.getString(R.string.sources_status_error);
        }
    }

    /** Pure: the color of a result; 0 (none) when unknown. */
    public static int statusColor(@Nullable SourceProbe.Result result) {
        return result == null ? 0 : result.isUp() ? COLOR_UP : COLOR_DOWN;
    }

    /** "0,8 с · 2 ч назад · через прокси": the site's last check, when, and by which route; null when never checked. */
    @Nullable
    public static String checkLine(@NonNull Context context, @Nullable SourceProbe.Result result, long now) {
        if (result == null) {
            return null;
        }
        String route = result.proxied == null ? null : context.getString(result.proxied
                ? R.string.sources_route_proxy : R.string.sources_route_direct);
        return join(statusText(context, result), ago(context, result.at, now), route);
    }

    /** "2 ч назад"; null for no time. */
    @Nullable
    public static String ago(@NonNull Context context, long at, long now) {
        return ago(at, now, context.getResources().getStringArray(R.array.sources_ago));
    }

    /**
     * Pure: how long ago {@code at} was, in the largest whole unit: {@code units} are "just now" (under a minute, or a
     * time ahead of the clock), then the patterns of minutes, hours and days. Null when {@code at} is unknown (0).
     */
    @Nullable
    static String ago(long at, long now, @NonNull String[] units) {
        if (at <= 0) {
            return null;
        }
        long minutes = TimeUnit.MILLISECONDS.toMinutes(now - at);
        if (minutes < 1) {
            return units[0];
        }
        if (minutes < 60) {
            return String.format(RUSSIAN, units[1], minutes);
        }
        long hours = minutes / 60;
        return hours < 24 ? String.format(RUSSIAN, units[2], hours) : String.format(RUSSIAN, units[3], hours / 24);
    }

    // ---------------------------------------------------------------- the site's facts

    /** «≈ 72 тыс. книг», «≈ 2 271 книга». */
    @NonNull
    public static String booksLine(@NonNull Context context, int count) {
        Resources res = RussianResources.of(context);
        String number = books(count, res.getString(R.string.sources_books_thousands),
                res.getString(R.string.sources_books_units));
        return count < FULL_BELOW ? res.getQuantityString(R.plurals.sources_books_count, count, number)
                : res.getString(R.string.sources_books_many, number);
    }

    /**
     * Pure: an estimate of {@code count} books: «≈ 72 тыс.» in whole thousands from {@link #WHOLE_FROM}, «≈ 5,8 тыс.» in
     * tenths from {@link #FULL_BELOW}, «≈ 2 271» in full below; {@code thousands} and {@code units} take the number.
     */
    @NonNull
    static String books(int count, @NonNull String thousands, @NonNull String units) {
        if (count < FULL_BELOW) {
            return String.format(RUSSIAN, units, grouped(count));
        }
        String number = count >= WHOLE_FROM ? grouped(Math.round(count / 1000.0))
                : String.format(RUSSIAN, "%.1f", count / 1000.0);
        return String.format(RUSSIAN, thousands, number);
    }

    /** Pure: {@code n} in groups of three digits ({@link #GROUP} between): 2271 → "2 271". */
    @NonNull
    static String grouped(long n) {
        String digits = Long.toString(n);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && digits.charAt(i - 1) != '-' && (digits.length() - i) % 3 == 0) {
                text.append(GROUP);
            }
            text.append(digits.charAt(i));
        }
        return text.toString();
    }

    /** Pure: the year of {@code since} ("2014" or "2014-03"); null for anything else. */
    @Nullable
    static String year(@Nullable String since) {
        return since != null && since.matches("\\d{4}(-\\d{2})?") ? since.substring(0, 4) : null;
    }

    /** "ноябрь 2026" for "2026-11"; null for anything else. */
    @Nullable
    public static String month(@NonNull Context context, @Nullable String yearMonth) {
        return month(yearMonth, context.getResources().getStringArray(R.array.sources_months));
    }

    /** Pure: "2026-11" as "ноябрь 2026" with the twelve {@code months}; null for anything else. */
    @Nullable
    static String month(@Nullable String yearMonth, @NonNull String[] months) {
        if (yearMonth == null || !yearMonth.matches("\\d{4}-\\d{2}")) {
            return null;
        }
        int month = Integer.parseInt(yearMonth.substring(5));
        return month >= 1 && month <= 12 ? months[month - 1] + " " + yearMonth.substring(0, 4) : null;
    }

    /** "Нидерланды", "за DDoS-Guard"; null when unknown. */
    @Nullable
    public static String server(@NonNull Context context, @NonNull SourceInfo info) {
        return server(info.country, info.cdn, context.getString(R.string.sources_behind));
    }

    /**
     * Pure: where the site answers from: the country's name in Russian (its code when the name is unknown), "за
     * DDoS-Guard" ({@code behind}) when a network answers for it, both when both are known; null when neither is.
     */
    @Nullable
    static String server(@Nullable String country, @Nullable String cdn, @NonNull String behind) {
        String name = null;
        if (country != null) {
            name = new Locale("", country).getDisplayCountry(RUSSIAN);
            if (name.isEmpty()) {
                name = country;
            }
        }
        String text = join(name, cdn == null ? null : String.format(RUSSIAN, behind, cdn));
        return text.isEmpty() ? null : text;
    }

    /** Pure: true when the site came with this version of the app ({@code added} "8.0", {@code version} "8.0.1"). */
    static boolean isNew(@Nullable String added, @NonNull String version) {
        return added != null && version.startsWith(added);
    }

    /** True when the site came with the installed version: the "новый" badge. */
    public static boolean isNew(@Nullable SourceInfo info) {
        return info != null && isNew(info.added, BuildConfig.VERSION_NAME);
    }

    /** "Россия", "Украина", "Запад", "другой регион"; "регион не определён" before the first check. */
    @NonNull
    public static String regionName(@NonNull Context context, @Nullable Region region) {
        if (region == null) {
            return context.getString(R.string.sources_region_unknown);
        }
        switch (region) {
            case RUSSIA:
                return context.getString(R.string.sources_region_russia);
            case UKRAINE:
                return context.getString(R.string.sources_region_ukraine);
            case WEST:
                return context.getString(R.string.sources_region_west);
            default:
                return context.getString(R.string.sources_region_other);
        }
    }

    /** The region of the user's connection; null before the first check and when the last one could not tell. */
    @Nullable
    public static Region region(@NonNull Context context) {
        return region(AppRegion.last(context));
    }

    /** Pure: the region of a check's {@code result}; null for none and for one that could not tell (stored as OTHER). */
    @Nullable
    static Region region(@Nullable com.fanok.audiobooks.region.RegionCheck.Result result) {
        return result == null || result.source == com.fanok.audiobooks.region.RegionCheck.Source.NONE ? null
                : result.region;
    }

    /** The sorts of the site's catalog (its Order menu) as one line; null when it has none. */
    @Nullable
    public static String sorts(@NonNull Context context, @NonNull Provider provider) {
        if (provider.index == null) {
            return null;
        }
        List<String> titles = new ArrayList<>();
        for (Sort.Entry entry : provider.parser.sorts(Url.canonical(provider.index))) {
            titles.add(context.getString(entry.title));
        }
        return sorts(titles);
    }

    /** Pure: the sort titles, each once, in lower case: "новое, популярное, рейтинг"; null for none. */
    @Nullable
    static String sorts(@NonNull List<String> titles) {
        Set<String> line = new LinkedHashSet<>();
        for (String title : titles) {
            line.add(title.toLowerCase(RUSSIAN));
        }
        if (line.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (String title : line) {
            text.append(text.length() == 0 ? "" : ", ").append(title);
        }
        return text.toString();
    }

    /** Pure: the names, three at most, the rest counted: "A, B, C", or "A, B и ещё 3" through {@code more}. */
    @NonNull
    static String names(@NonNull List<String> names, @NonNull String more) {
        if (names.size() > 3) {
            return String.format(RUSSIAN, more, names.get(0), names.get(1), names.size() - 2);
        }
        StringBuilder text = new StringBuilder();
        for (String name : names) {
            text.append(text.length() == 0 ? "" : ", ").append(name);
        }
        return text.toString();
    }

    /** Pure: the parts that are not empty, with " · " between. */
    @NonNull
    static String join(@Nullable String... parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                text.append(text.length() == 0 ? "" : SEP).append(part);
            }
        }
        return text.toString();
    }

    /** Pure: the parts that are not empty, with commas between: a row as TalkBack reads it. */
    @NonNull
    public static String spoken(@Nullable String... parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                text.append(text.length() == 0 ? "" : ", ").append(part);
            }
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- the proxy of "через прокси"

    /** What a source set to go "через прокси" gets now: the mode's proxy, or why it goes direct instead. */
    public enum ProxyState { WORKS, OFF, UNSET, SCANNING, OFFLINE, NONE }

    /**
     * Pure: the state in the proxy {@code mode} ({@link AppProxy#mode()}), with the built-in proxy {@code configured}
     * or not, and the automatic search having a proxy in use ({@code found}) or not, in its {@code phase}. Unlike
     * {@link AppProxy#proxyUsable()} it never starts a search, so a screen may ask at every redraw.
     */
    @NonNull
    static ProxyState proxyState(@NonNull String mode, boolean configured, boolean found,
            @NonNull ProxyFinder.Phase phase) {
        switch (mode) {
            case AppProxy.MODE_BUILTIN:
                return configured ? ProxyState.WORKS : ProxyState.UNSET;
            case AppProxy.MODE_AUTO:
                if (found) {
                    return ProxyState.WORKS;
                }
                return phase == ProxyFinder.Phase.SCANNING ? ProxyState.SCANNING
                        : phase == ProxyFinder.Phase.NO_NETWORK ? ProxyState.OFFLINE : ProxyState.NONE;
            default:
                return ProxyState.OFF;
        }
    }

    /** The state in the mode of now with {@code finder}'s state (a listener's). */
    @NonNull
    public static ProxyState proxyState(@NonNull ProxyFinder.State finder) {
        return proxyState(AppProxy.mode(), AppProxy.configured(), finder.current != null, finder.phase);
    }

    /** The state now. */
    @NonNull
    public static ProxyState proxyState(@NonNull Context context) {
        return proxyState(ProxyFinder.get(context).state());
    }

    /** Why "через прокси" goes direct in {@code state}: "рабочий прокси не найден"; null when the proxy carries it. */
    @Nullable
    public static String proxyReason(@NonNull Context context, @NonNull ProxyState state) {
        switch (state) {
            case OFF:
                return context.getString(R.string.sources_proxy_reason_off);
            case UNSET:
                return context.getString(R.string.sources_proxy_reason_unset);
            case SCANNING:
                return context.getString(R.string.sources_proxy_reason_scanning);
            case OFFLINE:
                return context.getString(R.string.sources_proxy_reason_offline);
            case NONE:
                return context.getString(R.string.sources_proxy_reason_none);
            default:
                return null;
        }
    }

    /** Pure: {@code text} as a sentence, its first letter capital: "рабочий прокси…" → "Рабочий прокси…". */
    @NonNull
    static String sentence(@NonNull String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(RUSSIAN) + text.substring(1);
    }

    // ---------------------------------------------------------------- shared by the screens

    /**
     * True for a preference that changes what the source screens show: the order, the routes, the proxy (its mode and
     * the built-in server and login), the checks, the catalog source, the region; null (the preferences were cleared)
     * too.
     */
    public static boolean watched(@Nullable String key) {
        return key == null || Sources.PREF.equals(key) || Sources.SELECTED.equals(key)
                || AppProxy.PREF_DIRECT.equals(key) || AppProxy.PREF_MODE.equals(key)
                || ProxyConfig.PREF_TYPE.equals(key) || ProxyConfig.PREF_HOST.equals(key)
                || ProxyConfig.PREF_PORT.equals(key) || ProxyConfig.PREF_USER.equals(key)
                || ProxyConfig.PREF_PASSWORD.equals(key)
                || SourceHealth.PREF_RESULTS.equals(key) || SourceHealth.PREF_CHECKED_AT.equals(key)
                || AppRegion.PREF_REGION.equals(key);
    }

    /** The facts of every site that has them; read off the main thread, since the first read opens the assets. */
    @NonNull
    public static Single<Map<Provider, SourceInfo>> infos(@NonNull Context context) {
        Context app = context.getApplicationContext();
        return Single.fromCallable(() -> {
            Map<Provider, SourceInfo> infos = new EnumMap<>(Provider.class);
            for (Provider provider : Provider.values()) {
                SourceInfo info = SourceInfo.of(app, provider);
                if (info != null) {
                    infos.put(provider, info);
                }
            }
            return infos;
        }).subscribeOn(Schedulers.io());
    }

    /** The site's own colour; the screens' accent when its facts have none. */
    @ColorInt
    public static int accent(@NonNull Context context, @Nullable SourceInfo info) {
        return info != null && info.accent != null ? info.accent
                : ContextCompat.getColor(context, R.color.sources_accent);
    }

    /** The first letter of the title: the icon's stand-in until it is drawn, or where it is missing. */
    @NonNull
    public static String letter(@NonNull Provider provider) {
        return provider.title.substring(0, 1).toUpperCase(RUSSIAN);
    }

    /** A filled rounded rectangle in {@code color}. */
    @NonNull
    public static GradientDrawable rounded(@ColorInt int color, float radiusPx) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radiusPx);
        return shape;
    }

    /** Opens the site in a browser; a device without one gets a message instead of a crash. */
    public static void openSite(@NonNull Context context, @NonNull Provider provider) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(provider.server)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.browser_not_found, Toast.LENGTH_SHORT).show();
        }
    }
}
