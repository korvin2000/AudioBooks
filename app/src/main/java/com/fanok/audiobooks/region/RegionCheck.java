package com.fanok.audiobooks.region;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The region of the connection ({@link AppRegion}), from the first way that tells it, one at a time and in this
 * order; a way is tried only when all before it failed:
 * <ol>
 * <li>a service that tells the caller's country ({@link #COUNTRY_SERVICES});</li>
 * <li>the connection's public address learnt without one, in the app's own table ({@link IpRegions}): the device's
 * own public address (IPv6 has no NAT), a STUN server ({@link #STUN_SERVERS}), a service that only echoes the address
 * ({@link #ADDRESS_SERVICES});</li>
 * <li>the device: the mobile network's country (unless roaming), the SIM's, the visited network's, the time zone's.</li>
 * </ol>
 * Requests go straight to the servers, never through the app's proxy ({@link Net}). Each list starts with the servers
 * most likely to answer where the device seems to be (its {@link Device#hint}): Russia slows Cloudflare down, China
 * blocks Google.
 */
public final class RegionCheck {

    public enum Source { SERVICE, IP_TABLE, MOBILE_NETWORK, SIM, TIME_ZONE, NONE }

    /** The region, the country when one was told ({@code ""} otherwise), what told it and through which server. */
    public static final class Result {
        public final Region region;
        public final String country;
        public final Source source;
        public final String via;
        /** A system VPN carried the requests: the region is the VPN's exit. */
        public final boolean vpn;

        public Result(Region region, String country, Source source, String via, boolean vpn) {
            this.region = region;
            this.country = country == null ? "" : country;
            this.source = source;
            this.via = via;
            this.vpn = vpn;
        }

        @Override
        public String toString() {
            return region + (country.isEmpty() ? "" : " (" + country + ")") + " by " + source
                    + (via.isEmpty() ? "" : " " + via) + (vpn ? ", VPN" : "");
        }
    }

    /** What the device knows without asking anyone; ISO codes in any case, "" when unknown. */
    public static final class Device {
        /** The active network can reach the internet. */
        public boolean online;
        public boolean vpn;
        public String networkCountry = "";
        public boolean roaming;
        public String simCountry = "";
        public String zoneCountry = "";
        /** The active network's own addresses: a public one (IPv6) is the connection's. */
        public List<InetAddress> addresses = Collections.emptyList();

        /** Where the device seems to be: the mobile network's, the SIM's or the time zone's country; "" for none. */
        String hint() {
            for (String code : new String[]{networkCountry, simCountry, zoneCountry}) {
                String cc = Region.country(code);
                if (cc != null) {
                    return cc;
                }
            }
            return "";
        }
    }

    /** The network, straight to the servers: no proxy, cookies or redirects. */
    public interface Net {
        /** The start of the body of an HTTPS GET of {@code url}; an error status is an {@link IOException}. */
        String get(String url) throws IOException;

        /** The address the STUN server {@code host}:{@code port} sees. */
        InetAddress stun(String host, int port) throws IOException;
    }

    /** The app's own IP table ({@link IpRegions}): the region of a public address; null for any other. */
    public interface AddressTable {
        Region of(InetAddress address);
    }

    /** A server: its name in logs and its address (a URL, or host:port for STUN). */
    static final class Server {
        final String name;
        final String address;

        Server(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }

    private static final Server IPINFO = new Server("ipinfo.io", "https://ipinfo.io/country");
    private static final Server COUNTRY_IS = new Server("country.is", "https://api.country.is/");
    private static final Server GEOJS = new Server("geojs.io", "https://get.geojs.io/v1/ip/country");
    private static final Server TWO_IP = new Server("2ip.io", "https://api.2ip.io/");

    /**
     * Services that tell the caller's country, keyless and HTTPS (checked 2026-11-01): ipinfo.io (Google Cloud),
     * country.is and geojs.io (both on Cloudflare), 2ip.io (Russian). At most three are asked.
     */
    static final List<Server> COUNTRY_SERVICES = Arrays.asList(IPINFO, COUNTRY_IS, GEOJS);
    private static final List<Server> COUNTRY_SERVICES_RU = Arrays.asList(IPINFO, TWO_IP, COUNTRY_IS);
    private static final List<Server> COUNTRY_SERVICES_CN = Collections.singletonList(IPINFO);

    private static final Server STUN_GOOGLE = new Server("stun.l.google.com", "stun.l.google.com:19302");
    private static final Server STUN_CLOUDFLARE = new Server("stun.cloudflare.com", "stun.cloudflare.com:3478");
    private static final Server STUN_NEXTCLOUD = new Server("stun.nextcloud.com", "stun.nextcloud.com:443");
    private static final Server STUN_SIPNET = new Server("stun.sipnet.ru", "stun.sipnet.ru:3478");
    private static final Server STUN_XIAOMI = new Server("stun.miwifi.com", "stun.miwifi.com:3478");
    private static final Server STUN_BILIBILI = new Server("stun.chat.bilibili.com", "stun.chat.bilibili.com:3478");

    /** STUN servers of different owners and countries; port 443 passes where other UDP ports are shut. */
    static final List<Server> STUN_SERVERS = Arrays.asList(STUN_GOOGLE, STUN_CLOUDFLARE, STUN_NEXTCLOUD);
    private static final List<Server> STUN_SERVERS_RU = Arrays.asList(STUN_SIPNET, STUN_GOOGLE, STUN_NEXTCLOUD);
    private static final List<Server> STUN_SERVERS_CN = Arrays.asList(STUN_XIAOMI, STUN_BILIBILI, STUN_NEXTCLOUD);

    private static final Server AMAZON = new Server("checkip.amazonaws.com", "https://checkip.amazonaws.com/");
    private static final Server IPIFY = new Server("ipify.org", "https://api.ipify.org/");
    private static final Server YANDEX = new Server("yandex.net", "https://ipv4-internet.yandex.net/api/v0/ip");
    private static final Server IPIP = new Server("ipip.net", "https://myip.ipip.net/");
    private static final Server ORAY = new Server("oray.com", "https://ddns.oray.com/checkip");

    /** Services that only echo the caller's address: Amazon, ipify; Yandex in Russia; ipip.net and Oray in China. */
    static final List<Server> ADDRESS_SERVICES = Arrays.asList(AMAZON, IPIFY);
    private static final List<Server> ADDRESS_SERVICES_RU = Arrays.asList(YANDEX, AMAZON);
    private static final List<Server> ADDRESS_SERVICES_CN = Arrays.asList(IPIP, ORAY);

    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![\\d.])");
    private static final Pattern IPV6 = Pattern.compile("(?<![0-9A-Fa-f:])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}"
            + "(?![0-9A-Fa-f:])");

    private RegionCheck() {
    }

    /**
     * The region by the first way that tells it (see the class). {@code tables} gives the app's table, read only when
     * a step needs it (null leaves out the second step). {@code failed} collects "server: reason" of the ways that
     * did not tell.
     */
    public static Result run(Device device, Net net, Supplier<? extends AddressTable> tables, List<String> failed) {
        String hint = device.hint();
        Table table = new Table(tables);
        if (device.online) {
            for (Server server : countryServices(hint)) {
                try {
                    String[] answer = countryAnswer(net.get(server.address));
                    Region region = Region.ofCountry(answer[0]);
                    if (region != null) {
                        return new Result(region, Region.country(answer[0]), Source.SERVICE, server.name, device.vpn);
                    }
                    InetAddress address = address(answer[1]);
                    Region byAddress = address == null ? null : table.of(address);
                    if (byAddress != null) {
                        return new Result(byAddress, "", Source.IP_TABLE, server.name, device.vpn);
                    }
                    failed.add(server.name + ": no country");
                } catch (IOException | RuntimeException e) {
                    failed.add(server.name + ": " + e);
                }
            }
        }
        if (!table.available()) {
            return offline(device);
        }
        for (InetAddress own : device.addresses) {
            Region region = table.of(own);
            if (region != null) {
                return new Result(region, "", Source.IP_TABLE, "device", device.vpn);
            }
        }
        if (device.online) {
            for (Server server : stunServers(hint)) {
                try {
                    int colon = server.address.lastIndexOf(':');
                    Region region = table.of(net.stun(server.address.substring(0, colon),
                            Integer.parseInt(server.address.substring(colon + 1))));
                    if (region != null) {
                        return new Result(region, "", Source.IP_TABLE, server.name, device.vpn);
                    }
                    failed.add(server.name + ": not a public address");
                } catch (IOException | RuntimeException e) {
                    failed.add(server.name + ": " + e);
                }
            }
            for (Server server : addressServices(hint)) {
                try {
                    Region region = table.of(address(net.get(server.address)));
                    if (region != null) {
                        return new Result(region, "", Source.IP_TABLE, server.name, device.vpn);
                    }
                    failed.add(server.name + ": no public address");
                } catch (IOException | RuntimeException e) {
                    failed.add(server.name + ": " + e);
                }
            }
        }
        return offline(device);
    }

    /** The app's table, read on first use: most checks end at the first service and never need it. */
    private static final class Table {
        private final Supplier<? extends AddressTable> source;
        private AddressTable table;
        private boolean read;

        Table(Supplier<? extends AddressTable> source) {
            this.source = source;
        }

        boolean available() {
            if (!read) {
                read = true;
                table = source == null ? null : source.get();
            }
            return table != null;
        }

        Region of(InetAddress address) {
            return available() ? table.of(address) : null;
        }
    }

    /** The third step: the mobile network (unless roaming), the SIM, the visited network, the time zone. */
    static Result offline(Device device) {
        String network = Region.country(device.networkCountry);
        String sim = Region.country(device.simCountry);
        String zone = Region.country(device.zoneCountry);
        if (network != null && !device.roaming) {
            return new Result(Region.ofCountry(network), network, Source.MOBILE_NETWORK, "", device.vpn);
        }
        if (sim != null) {
            // roaming data usually comes out at home
            return new Result(Region.ofCountry(sim), sim, Source.SIM, "", device.vpn);
        }
        if (network != null) {
            return new Result(Region.ofCountry(network), network, Source.MOBILE_NETWORK, "", device.vpn);
        }
        if (zone != null) {
            return new Result(Region.ofCountry(zone), zone, Source.TIME_ZONE, "", device.vpn);
        }
        return new Result(Region.OTHER, "", Source.NONE, "", device.vpn);
    }

    static List<Server> countryServices(String hint) {
        return "RU".equals(hint) ? COUNTRY_SERVICES_RU : "CN".equals(hint) ? COUNTRY_SERVICES_CN : COUNTRY_SERVICES;
    }

    static List<Server> stunServers(String hint) {
        return "RU".equals(hint) ? STUN_SERVERS_RU : "CN".equals(hint) ? STUN_SERVERS_CN : STUN_SERVERS;
    }

    static List<Server> addressServices(String hint) {
        return "RU".equals(hint) ? ADDRESS_SERVICES_RU : "CN".equals(hint) ? ADDRESS_SERVICES_CN : ADDRESS_SERVICES;
    }

    /**
     * The country and the address (each null when absent) in a country service's answer: a bare code ("DE"), JSON
     * ({@code country_code}, {@code countryCode}, {@code code} or {@code country}; {@code ip}; {@code success} false
     * is no answer) or {@code key=value} lines ({@code loc}, {@code ip}).
     */
    static String[] countryAnswer(String body) {
        String text = body.trim();
        if (text.startsWith("{")) {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return new String[2];
            }
            JsonObject o = root.getAsJsonObject();
            if (o.has("success") && !o.get("success").getAsBoolean()) {
                return new String[2];
            }
            String country = null;
            for (String key : new String[]{"country_code", "countryCode", "code", "country"}) {
                country = Region.country(string(o, key));
                if (country != null) {
                    break;
                }
            }
            return new String[]{country, string(o, "ip")};
        }
        if (text.contains("=")) {
            String country = null;
            String ip = null;
            for (String line : text.split("\n")) {
                if (line.startsWith("loc=")) {
                    country = line.substring(4).trim();
                } else if (line.startsWith("ip=")) {
                    ip = line.substring(3).trim();
                }
            }
            return new String[]{country, ip};
        }
        return new String[]{text, null};
    }

    /** The first IP address written in {@code text} (IPv4 first), parsed without DNS; null when there is none. */
    static InetAddress address(String text) {
        if (text == null) {
            return null;
        }
        Matcher v4 = IPV4.matcher(text);
        while (v4.find()) {
            byte[] bytes = new byte[4];
            boolean valid = true;
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(v4.group(i + 1));
                valid &= octet <= 255;
                bytes[i] = (byte) octet;
            }
            if (valid) {
                try {
                    return InetAddress.getByAddress(bytes);
                } catch (UnknownHostException impossible) {
                    return null;
                }
            }
        }
        Matcher v6 = IPV6.matcher(text);
        while (v6.find()) {
            byte[] bytes = ipv6(v6.group());
            if (bytes != null) {
                try {
                    return InetAddress.getByAddress(bytes);
                } catch (UnknownHostException impossible) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * The 16 bytes of an IPv6 literal (hex groups, one "::" at most); null for anything else. Parsed here: Android's
     * InetAddress.getByName looks a string up in DNS when it is no valid literal.
     */
    static byte[] ipv6(String text) {
        int gap = text.indexOf("::");
        if (gap != text.lastIndexOf("::")) {
            return null;
        }
        String[] head = groups(gap < 0 ? text : text.substring(0, gap));
        String[] tail = gap < 0 ? new String[0] : groups(text.substring(gap + 2));
        int count = head.length + tail.length;
        byte[] bytes = new byte[16];
        if (gap < 0 ? count != 8 : count > 7) {
            return null;
        }
        return put(head, bytes, 0) && put(tail, bytes, 16 - 2 * tail.length) ? bytes : null;
    }

    private static String[] groups(String part) {
        return part.isEmpty() ? new String[0] : part.split(":", -1);
    }

    private static boolean put(String[] groups, byte[] bytes, int at) {
        for (String group : groups) {
            if (group.isEmpty() || group.length() > 4) {
                return false;
            }
            int value = Integer.parseInt(group, 16); // hex digits only: the pattern matched them
            bytes[at++] = (byte) (value >> 8);
            bytes[at++] = (byte) value;
        }
        return true;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
