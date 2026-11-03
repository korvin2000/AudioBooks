package com.fanok.audiobooks.net;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the automatic proxy finder takes its candidates from, and how each list is read. {@link #ALL} is a plain
 * table, one line per list: edit it freely. Only IPv4 HTTP and SOCKS5 proxies are kept; SOCKS4 and IPv6 are skipped.
 */
public final class ProxySources {

    /** How a list is written. */
    public enum Format {
        /** One {@code ip:port} per line; anything after the port ({@code :country}, spaces) is ignored. */
        IPPORT,
        /** One {@code http://ip:port}, {@code socks5://ip:port} (or socks4, skipped) per line. */
        PROTOIPPORT,
        /** geonode JSON: {@code {"data":[{"ip":..,"port":..,"protocols":[..]}]}}. No list in {@link #ALL} uses it now. */
        GEONODE
    }

    /** One list. {@code type} is the proxy type of every line of an {@link Format#IPPORT} list; null for mixed lists. */
    public static final class Source {
        public final String name;
        public final String url;
        public final Format format;
        @Nullable
        public final Proxy.Type type;

        public Source(@NonNull String name, @NonNull String url, @NonNull Format format, @Nullable Proxy.Type type) {
            this.name = name;
            this.url = url;
            this.format = format;
            this.type = type;
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }

    private static final Proxy.Type HTTP = Proxy.Type.HTTP;
    private static final Proxy.Type SOCKS5 = Proxy.Type.SOCKS;
    private static final Proxy.Type MIXED = null;

    /**
     * The lists, one line each (name, URL, format, type of an ipport list). Measured 2026-10-28 against
     * https://akniga.org/: the share of entries that answered, and the list size. Small checked lists yield 3-10x
     * more than big dumps; dropped for low yield: TheSpeedX (5-7 %), proxifly all (0.3 %), hookzof (2 %), geonode (13 %),
     * ALIILAPRO socks5 (2026-11-03: 2 of 46, 83 KB, 88 % one farm; it was 26-28 % before the flood). The lists added
     * 2026-11-03 (spys.me, proxyspace, jetkai, mmpx12, im-razvan, roosterkid) are not measured yet: their comments give
     * that day's size and distinct IPs only. spys.me and proxyspace are not on GitHub, so one host down leaves lists.
     */
    public static final List<Source> ALL = Collections.unmodifiableList(Arrays.asList(
            // 23-26 % alive, 55 KB, API; socks4 lines are skipped
            new Source("proxyscrape", "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies&proxy_format=protocolipport&format=text", Format.PROTOIPPORT, MIXED),
            // 39-44 %, 5 KB (2026-11-03: 26 of 45, although one farm floods it); lines are ip:port:Country, the
            // trailing field is ignored
            new Source("zloi-socks5", "https://raw.githubusercontent.com/zloi-user/hideip.me/main/socks5.txt", Format.IPPORT, SOCKS5),
            // 17-19 %, 6 KB
            new Source("zloi-http", "https://raw.githubusercontent.com/zloi-user/hideip.me/main/http.txt", Format.IPPORT, HTTP),
            // 23-27 %, 9 KB
            new Source("monosans-socks5", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt", Format.IPPORT, SOCKS5),
            // 16-19 %, 8 KB
            new Source("monosans-http", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt", Format.IPPORT, HTTP),
            // 22 %, 5 KB
            new Source("elliottophellia-http", "https://raw.githubusercontent.com/elliottophellia/proxylist/master/results/http/global/http_checked.txt", Format.IPPORT, HTTP),
            // 16-20 %, 13 KB
            new Source("elliottophellia-socks5", "https://raw.githubusercontent.com/elliottophellia/proxylist/master/results/socks5/global/socks5_checked.txt", Format.IPPORT, SOCKS5),
            // 25-27 %, 10 KB
            new Source("themiralay-http", "https://raw.githubusercontent.com/themiralay/Proxy-List-World/master/data.txt", Format.IPPORT, HTTP),
            // unmeasured; 11 KB, 393 IPs; lines are "ip:port CC-A-S" under a few header lines, which are skipped
            new Source("spys-socks5", "https://spys.me/socks.txt", Format.IPPORT, SOCKS5),
            // unmeasured; 12 KB, 400 IPs; the same format
            new Source("spys-http", "https://spys.me/proxy.txt", Format.IPPORT, HTTP),
            // unmeasured; 71 KB, 2205 IPs, an aggregator: the subnet caps and the quotas contain it
            new Source("proxyspace-socks5", "https://proxyspace.pro/socks5.txt", Format.IPPORT, SOCKS5),
            // unmeasured; 8 KB, 347 IPs, checked by the publisher ("online")
            new Source("jetkai-socks5", "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt", Format.IPPORT, SOCKS5),
            // unmeasured; 34 KB, 1721 IPs, checked by the publisher
            new Source("jetkai-http", "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-http.txt", Format.IPPORT, HTTP),
            // unmeasured; 5.5 KB, 279 IPs, one port each
            new Source("mmpx12-socks5", "https://raw.githubusercontent.com/mmpx12/proxy-list/master/socks5.txt", Format.IPPORT, SOCKS5),
            // unmeasured; 8 KB, 440 IPs
            new Source("mmpx12-http", "https://raw.githubusercontent.com/mmpx12/proxy-list/master/http.txt", Format.IPPORT, HTTP),
            // unmeasured; 8 KB, 362 IPs
            new Source("im-razvan-socks5", "https://raw.githubusercontent.com/im-razvan/proxy_list/main/socks5.txt", Format.IPPORT, SOCKS5),
            // unmeasured; 5 KB, 221 IPs
            new Source("im-razvan-http", "https://raw.githubusercontent.com/im-razvan/proxy_list/main/http.txt", Format.IPPORT, HTTP),
            // unmeasured; 2.3 KB, 123 IPs in 112 /24s; HTTP proxies that tunnel https
            new Source("roosterkid-http", "https://raw.githubusercontent.com/roosterkid/openproxylist/main/HTTPS_RAW.txt", Format.IPPORT, HTTP)
    ));

    /** At most this many candidates of one /24 over all lists: a farm is one /24 with many IPs and ports. */
    static final int PER_SUBNET_24 = 3;
    /** At most this many of one /16 over all lists: a gateway range (Zscaler 147.161.246-247), an ISP block. */
    static final int PER_SUBNET_16 = 8;

    private static final Pattern IPPORT = Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{1,5})(?:\\D.*)?$");
    private static final Pattern PROTOIPPORT = Pattern.compile(
            "^([A-Za-z0-9]+)://(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{1,5})(?:\\D.*)?$");

    private ProxySources() {
    }

    /** The candidates of one list's text; malformed lines are skipped, never an error. */
    @NonNull
    public static List<ProxyCandidate> parse(@NonNull Source source, @Nullable String body) {
        if (body == null || body.isEmpty()) {
            return Collections.emptyList();
        }
        switch (source.format) {
            case IPPORT:
                return source.type == null ? Collections.emptyList() : parseIpPort(body, source.type);
            case PROTOIPPORT:
                return parseProtoIpPort(body);
            case GEONODE:
                return parseGeonode(body);
            default:
                return Collections.emptyList();
        }
    }

    @NonNull
    static List<ProxyCandidate> parseIpPort(@NonNull String body, @NonNull Proxy.Type type) {
        Set<ProxyCandidate> out = new LinkedHashSet<>();
        for (String raw : body.split("\r?\n|\r")) {
            Matcher m = IPPORT.matcher(raw.trim());
            if (m.matches()) {
                add(out, m.group(1), m.group(2), type);
            }
        }
        return new ArrayList<>(out);
    }

    @NonNull
    static List<ProxyCandidate> parseProtoIpPort(@NonNull String body) {
        Set<ProxyCandidate> out = new LinkedHashSet<>();
        for (String raw : body.split("\r?\n|\r")) {
            Matcher m = PROTOIPPORT.matcher(raw.trim());
            if (m.matches()) {
                Proxy.Type type = ProxyCandidate.typeOf(m.group(1));
                if (type != null) {
                    add(out, m.group(2), m.group(3), type);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Streams the geonode records, so a body cut at the size limit still yields every record read before the cut.
     * A record lists its protocols; it becomes one candidate per supported protocol.
     */
    @NonNull
    static List<ProxyCandidate> parseGeonode(@NonNull String body) {
        Set<ProxyCandidate> out = new LinkedHashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(body))) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                return new ArrayList<>(out);
            }
            reader.beginObject();
            while (reader.hasNext()) {
                if (!"data".equals(reader.nextName()) || reader.peek() != JsonToken.BEGIN_ARRAY) {
                    reader.skipValue();
                    continue;
                }
                reader.beginArray();
                while (reader.hasNext()) {
                    readGeonodeRecord(reader, out);
                }
                reader.endArray();
            }
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            // truncated or malformed: keep what was read
        }
        return new ArrayList<>(out);
    }

    private static void readGeonodeRecord(JsonReader reader, Set<ProxyCandidate> out) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue();
            return;
        }
        String ip = null;
        String port = null;
        List<String> protocols = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            JsonToken token = reader.peek();
            if ("ip".equals(name) && token == JsonToken.STRING) {
                ip = reader.nextString();
            } else if ("port".equals(name) && (token == JsonToken.STRING || token == JsonToken.NUMBER)) {
                port = reader.nextString();
            } else if ("protocols".equals(name) && token == JsonToken.BEGIN_ARRAY) {
                reader.beginArray();
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.STRING) {
                        protocols.add(reader.nextString());
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        for (String protocol : protocols) {
            Proxy.Type type = ProxyCandidate.typeOf(protocol);
            if (type != null && ip != null && port != null) {
                add(out, ip.trim(), port.trim(), type);
            }
        }
    }

    private static void add(Set<ProxyCandidate> out, String host, String port, Proxy.Type type) {
        if (!isPublicIpv4(host)) {
            return;
        }
        int p;
        try {
            p = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return;
        }
        if (p < 1 || p > 65535) {
            return;
        }
        out.add(new ProxyCandidate(canonicalIpv4(host), p, type));
    }

    /**
     * A dotted IPv4 literal of a public address. Names, IPv6 and local addresses (this network, loopback, private,
     * shared, link-local, multicast and reserved) are not: a list must not make the app probe the user's own network.
     * Nor are the other non-global special-purpose ranges (IETF protocol assignments, documentation, benchmarking).
     */
    static boolean isPublicIpv4(@Nullable String host) {
        if (host == null) {
            return false;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int n = 0; n < 4; n++) {
            String part = parts[n];
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') {
                    return false;
                }
            }
            octets[n] = Integer.parseInt(part);
            if (octets[n] > 255) {
                return false;
            }
        }
        int a = octets[0];
        int b = octets[1];
        int c = octets[2];
        return a != 0 && a != 10 && a != 127 && a < 224
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && b == 168)
                && !(a == 192 && b == 0 && (c == 0 || c == 2))
                && !(a == 198 && (b == 18 || b == 19))
                && !(a == 198 && b == 51 && c == 100)
                && !(a == 203 && b == 0 && c == 113);
    }

    /** "010.001.002.003" and "10.1.2.3" are one proxy. */
    private static String canonicalIpv4(String host) {
        String[] parts = host.split("\\.");
        return String.format(Locale.ROOT, "%d.%d.%d.%d", Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    /** {@link #select(List, List, int[], int, Random)} with one admitted candidate of each list per round. */
    @NonNull
    public static List<ProxyCandidate> select(@NonNull List<ProxyCandidate> first,
            @NonNull List<List<ProxyCandidate>> lists, int cap, @NonNull Random random) {
        return select(first, lists, null, cap, random);
    }

    /**
     * One scan's order: {@code first} (the proxies that worked last time) as given, then the lists shuffled each and
     * taken round-robin, {@code quota[i]} admitted candidates of list i per round (1 without a quota). One candidate per
     * IP over all lists (a node with many ports, or a list that repeats another, adds nothing), at most
     * {@link #PER_SUBNET_24} per /24 and {@link #PER_SUBNET_16} per /16, so one farm, one range or one list cannot crowd
     * out the others; a rejected entry does not use up its list's turn. The known proxies count toward the caps but are
     * never rejected by them. Cut at {@code cap}.
     */
    @NonNull
    public static List<ProxyCandidate> select(@NonNull List<ProxyCandidate> first,
            @NonNull List<List<ProxyCandidate>> lists, @Nullable int[] quota, int cap, @NonNull Random random) {
        Spread spread = new Spread();
        List<ProxyCandidate> out = new ArrayList<>();
        for (ProxyCandidate candidate : first) {
            if (out.size() < cap && spread.ips.add(candidate.host())) {
                spread.count(candidate.host());
                out.add(candidate);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < lists.size(); i++) {
            if (lists.get(i) != null && !lists.get(i).isEmpty()) {
                order.add(i);
            }
        }
        Collections.shuffle(order, random);
        List<List<ProxyCandidate>> shuffled = new ArrayList<>();
        int[] turn = new int[order.size()];
        for (int k = 0; k < order.size(); k++) {
            int i = order.get(k);
            List<ProxyCandidate> copy = new ArrayList<>(lists.get(i));
            Collections.shuffle(copy, random);
            shuffled.add(copy);
            turn[k] = quota != null && i < quota.length ? Math.max(1, quota[i]) : 1;
        }
        int[] next = new int[shuffled.size()];
        boolean more = true;
        while (more && out.size() < cap) {
            more = false;
            for (int k = 0; k < shuffled.size() && out.size() < cap; k++) {
                List<ProxyCandidate> list = shuffled.get(k);
                int taken = 0;
                // the turn lasts until the quota is admitted: a rejected entry costs the list nothing
                while (taken < turn[k] && next[k] < list.size() && out.size() < cap) {
                    more = true;
                    ProxyCandidate candidate = list.get(next[k]++);
                    if (spread.admit(candidate)) {
                        out.add(candidate);
                        taken++;
                    }
                }
            }
        }
        return out;
    }

    /** Pure: the first {@code octets} octets of a dotted IPv4: "1.2.3.4" -> "1.2.3" for 3, "1.2" for 2. */
    @NonNull
    static String prefix(@NonNull String ip, int octets) {
        int at = -1;
        for (int n = 0; n < octets; n++) {
            at = ip.indexOf('.', at + 1);
            if (at < 0) {
                return ip;
            }
        }
        return ip.substring(0, at);
    }

    /** The IPs and subnets already in the sample. */
    private static final class Spread {
        final Set<String> ips = new HashSet<>();
        final Map<String, Integer> per24 = new HashMap<>();
        final Map<String, Integer> per16 = new HashMap<>();

        boolean admit(@NonNull ProxyCandidate candidate) {
            String ip = candidate.host();
            if (ips.contains(ip) || per24.getOrDefault(prefix(ip, 3), 0) >= PER_SUBNET_24
                    || per16.getOrDefault(prefix(ip, 2), 0) >= PER_SUBNET_16) {
                return false;
            }
            ips.add(ip);
            count(ip);
            return true;
        }

        void count(@NonNull String ip) {
            per24.merge(prefix(ip, 3), 1, Integer::sum);
            per16.merge(prefix(ip, 2), 1, Integer::sum);
        }
    }

    /**
     * Per-list yield of the last searches, {@code {name: [ok, tried]}} of their control checks (a site's refusal is
     * not the list's), halved before a search adds its own (half-life one search): the quotas of {@link #select}.
     * Learned on the user's own network, where a list that is good from one country may be useless; stored as
     * {@link #PREF}.
     */
    static final class ListStats {
        static final String PREF = "pref_proxy_list_stats";
        /** Below this many checks a list is unknown: no quota from one lucky probe. */
        static final int MIN_TRIED = 8;
        /** The quota of an unknown list. */
        static final int DEFAULT_QUOTA = 2;
        /** A 58 % list gets 2x a 25 % one and 4x a 4 % one; the floor of 1 keeps every list in the sample. */
        static final int MAX_QUOTA = 4;

        final Map<String, int[]> counts = new HashMap<>();

        /** Admitted candidates per round: 58 % -> 4, 25 % -> 3, 15 % -> 2, 4 % -> 1; unknown -> 2; never 0. */
        int quota(@NonNull String name) {
            int[] c = counts.get(name);
            return c == null || c[1] < MIN_TRIED ? DEFAULT_QUOTA
                    : Math.max(1, Math.min(MAX_QUOTA, 1 + Math.round(6f * c[0] / c[1])));
        }

        /** Halves what is known of the list, then adds one search's checks of it. */
        void add(@NonNull String name, int ok, int tried) {
            int[] c = counts.get(name);
            if (c == null) {
                c = new int[2];
                counts.put(name, c);
            }
            c[0] = c[0] / 2 + ok;
            c[1] = c[1] / 2 + tried;
        }

        @NonNull
        String toJson() {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, int[]> entry : counts.entrySet()) {
                JsonArray pair = new JsonArray();
                pair.add(entry.getValue()[0]);
                pair.add(entry.getValue()[1]);
                root.add(entry.getKey(), pair);
            }
            return root.toString();
        }

        /** Anything unreadable is no memory: every list gets the default quota. */
        @NonNull
        static ListStats fromJson(@Nullable String json) {
            ListStats stats = new ListStats();
            if (json == null || json.isEmpty()) {
                return stats;
            }
            try {
                for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(json).getAsJsonObject().entrySet()) {
                    JsonArray pair = entry.getValue().getAsJsonArray();
                    int ok = pair.get(0).getAsInt();
                    int tried = pair.get(1).getAsInt();
                    if (ok >= 0 && tried >= ok) {
                        stats.counts.put(entry.getKey(), new int[]{ok, tried});
                    }
                }
                return stats;
            } catch (RuntimeException e) {
                return new ListStats();
            }
        }
    }
}
