package com.fanok.audiobooks.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class ProxySourcesTest {

    private static ProxyCandidate http(String host, int port) {
        return new ProxyCandidate(host, port, Proxy.Type.HTTP);
    }

    private static ProxyCandidate socks(String host, int port) {
        return new ProxyCandidate(host, port, Proxy.Type.SOCKS);
    }

    @Test
    public void ipPortLinesKeepPublicIpv4AndIgnoreTrailingFields() {
        String body = "1.2.3.4:8080\r\n"
                + "  5.6.7.8:3128:US  \n"
                + "8.8.8.8:80 elite proxy\n"
                + "# a comment\n"
                + "\n"
                + "junk\n"
                + "9.9.9.9:99999\n"
                + "9.9.9.9:0\n"
                + "300.1.1.1:80\n"
                + "[2001:db8::1]:8080\n"
                + "2001:db8::1:8080\n"
                + "10.0.0.1:80\n"
                + "192.168.1.1:8080\n"
                + "127.0.0.1:1080\n"
                + "proxy.example.com:8080\n"
                + "1.2.3.4:8080\n"
                + "001.002.003.004:8080\n";
        List<ProxyCandidate> parsed = ProxySources.parseIpPort(body, Proxy.Type.SOCKS);
        assertEquals(Arrays.asList(socks("1.2.3.4", 8080), socks("5.6.7.8", 3128), socks("8.8.8.8", 80)), parsed);
    }

    @Test
    public void protocolLinesTakeTheirTypeFromTheLine() {
        String body = "http://1.2.3.4:80\n"
                + "socks5://5.6.7.8:1080\n"
                + "socks4://9.9.9.9:1080\n"
                + "https://11.12.13.14:443\n"
                + "ftp://11.12.13.15:21\n"
                + "socks5://[::1]:1080\n"
                + "http://1.2.3.4:80:RU\n"
                + "1.2.3.4:8080\n"
                + "garbage\n";
        assertEquals(Arrays.asList(http("1.2.3.4", 80), socks("5.6.7.8", 1080), http("11.12.13.14", 443)),
                ProxySources.parseProtoIpPort(body));
    }

    @Test
    public void geonodeRecordsBecomeOneCandidatePerSupportedProtocol() {
        String body = "{\"data\":["
                + "{\"_id\":\"x\",\"ip\":\"1.2.3.4\",\"port\":\"8080\",\"protocols\":[\"http\"],\"country\":\"DE\"},"
                + "{\"ip\":\"5.6.7.8\",\"port\":1080,\"protocols\":[\"socks4\",\"socks5\"],\"anonymityLevel\":\"elite\"},"
                + "{\"ip\":\"2001:db8::1\",\"port\":\"80\",\"protocols\":[\"http\"]},"
                + "{\"ip\":\"9.9.9.9\",\"port\":\"80\",\"protocols\":[\"socks4\"]},"
                + "{\"ip\":\"11.12.13.14\",\"port\":\"3128\",\"protocols\":[\"http\",\"socks5\"],\"nested\":{\"a\":[1,2]}}"
                + "],\"total\":5,\"page\":1}";
        assertEquals(Arrays.asList(http("1.2.3.4", 8080), socks("5.6.7.8", 1080), http("11.12.13.14", 3128),
                socks("11.12.13.14", 3128)), ProxySources.parseGeonode(body));
    }

    @Test
    public void aGeonodeBodyCutAtTheSizeLimitKeepsTheRecordsBeforeTheCut() {
        String body = "{\"data\":[{\"ip\":\"1.2.3.4\",\"port\":\"8080\",\"protocols\":[\"http\"]},"
                + "{\"ip\":\"5.6.7.8\",\"port\":\"1080\",\"protocols\":[\"socks5\"]},{\"ip\":\"9.9.";
        assertEquals(Arrays.asList(http("1.2.3.4", 8080), socks("5.6.7.8", 1080)), ProxySources.parseGeonode(body));
        assertTrue(ProxySources.parseGeonode("<html>blocked</html>").isEmpty());
        assertTrue(ProxySources.parseGeonode("{\"message\":\"rate limited\"}").isEmpty());
    }

    @Test
    public void parseFollowsTheSourceFormat() {
        ProxySources.Source socksList = new ProxySources.Source("s", "https://x/s.txt", ProxySources.Format.IPPORT, Proxy.Type.SOCKS);
        ProxySources.Source mixed = new ProxySources.Source("m", "https://x/m.txt", ProxySources.Format.PROTOIPPORT, null);
        ProxySources.Source untyped = new ProxySources.Source("u", "https://x/u.txt", ProxySources.Format.IPPORT, null);
        assertEquals(Collections.singletonList(socks("1.2.3.4", 1080)), ProxySources.parse(socksList, "1.2.3.4:1080"));
        assertEquals(Collections.singletonList(http("1.2.3.4", 1080)), ProxySources.parse(mixed, "http://1.2.3.4:1080"));
        assertTrue(ProxySources.parse(untyped, "1.2.3.4:1080").isEmpty());
        assertTrue(ProxySources.parse(socksList, null).isEmpty());
        List<String> names = new ArrayList<>();
        for (ProxySources.Source source : ProxySources.ALL) {
            names.add(source.name);
        }
        assertEquals(Arrays.asList("proxyscrape", "zloi-socks5", "zloi-http", "monosans-socks5", "monosans-http",
                "elliottophellia-http", "elliottophellia-socks5", "themiralay-http", "spys-socks5", "spys-http",
                "proxyspace-socks5", "jetkai-socks5", "jetkai-http", "mmpx12-socks5", "mmpx12-http", "im-razvan-socks5",
                "im-razvan-http", "roosterkid-http"), names);
        assertEquals("the yield memory keys on the name", names.size(), new HashSet<>(names).size());
        for (ProxySources.Source source : ProxySources.ALL) {
            assertTrue(source.name, source.url.startsWith("https://"));
            assertTrue(source.name, source.format != ProxySources.Format.IPPORT || source.type != null);
        }
    }

    @Test
    public void theIpPortRegexAcceptsSpysLines() {
        String body = "Proxy list updated at Mon, 03 Nov 26 10:00:01 +0300\n"
                + "IP address:Port Country-Anonymity(Noa/Anm/Hia)-SSL_support(S)-Google_passed(+)\n"
                + "\n"
                + "1.2.3.4:1080 DE-H-S!\n"
                + "5.6.7.8:3128 US-N +\n";
        assertEquals(Arrays.asList(socks("1.2.3.4", 1080), socks("5.6.7.8", 3128)),
                ProxySources.parseIpPort(body, Proxy.Type.SOCKS));
    }

    @Test
    public void selectTakesOneCandidatePerIpAndAtMostThreeIpsPerSubnet() {
        // a farm: ten IPs of one /24 with forty ports each, spread over two lists
        List<ProxyCandidate> farmA = new ArrayList<>();
        List<ProxyCandidate> farmB = new ArrayList<>();
        for (int ip = 1; ip <= 10; ip++) {
            for (int port = 1; port <= 40; port++) {
                (port % 2 == 0 ? farmA : farmB).add(socks("45.74.31." + ip, 10_000 + port));
            }
        }
        List<ProxyCandidate> normal = new ArrayList<>();
        List<ProxyCandidate> other = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            normal.add(http("20." + i + ".1.1", 80));
            other.add(socks("30." + i + ".1.1", 1080));
        }
        other.add(http("20.1.1.1", 8080));
        List<ProxyCandidate> out = ProxySources.select(Collections.emptyList(),
                Arrays.asList(farmA, normal, farmB, other), 400, new Random(7));

        Set<String> hosts = new HashSet<>();
        int farm = 0;
        for (ProxyCandidate candidate : out) {
            assertTrue("one candidate per IP: " + candidate, hosts.add(candidate.host()));
            if (candidate.host().startsWith("45.74.31.")) {
                farm++;
            }
        }
        assertEquals(ProxySources.PER_SUBNET_24, farm);
        assertEquals("every other IP once", ProxySources.PER_SUBNET_24 + 30 + 30, out.size());
    }

    @Test
    public void selectCapsASixteen() {
        List<ProxyCandidate> range = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            range.add(http("147.161." + (240 + i / 2) + "." + (1 + i % 2), 80));
        }
        range.add(http("147.162.1.1", 80));
        List<ProxyCandidate> out = ProxySources.select(Collections.emptyList(), Collections.singletonList(range), 400,
                new Random(2));
        assertEquals(ProxySources.PER_SUBNET_16 + 1, out.size());
        assertTrue(out.contains(http("147.162.1.1", 80)));
    }

    @Test
    public void selectDoesNotLetARejectedEntryUseUpTheTurn() {
        List<ProxyCandidate> a = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            a.add(socks("45.74.31." + (1 + i % 10), 10_000 + i));
        }
        a.add(http("50.1.1.1", 80));
        a.add(http("50.2.1.1", 80));
        List<ProxyCandidate> b = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            b.add(http("60." + i + ".1.1", 80));
        }
        List<ProxyCandidate> out = ProxySources.select(Collections.emptyList(), Arrays.asList(a, b), 10, new Random(5));

        // five rounds: the turn of A passes its 297 rejected farm entries until it admits one
        assertEquals(10, out.size());
        assertTrue(out.contains(http("50.1.1.1", 80)));
        assertTrue(out.contains(http("50.2.1.1", 80)));
        int fromB = 0;
        for (ProxyCandidate candidate : out) {
            if (candidate.host().startsWith("60.")) {
                fromB++;
            }
        }
        assertEquals(5, fromB);
    }

    @Test
    public void selectKeepsTheKnownProxiesFirstAndCountsThem() {
        List<ProxyCandidate> first = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            first.add(http("9.9.9." + i, 80));
        }
        List<ProxyCandidate> list = new ArrayList<>();
        for (int i = 10; i <= 20; i++) {
            list.add(http("9.9.9." + i, 80));
        }
        list.add(socks("70.1.1.1", 1080));
        List<ProxyCandidate> out = ProxySources.select(first, Collections.singletonList(list), 400, new Random(4));
        assertEquals("the known five are never rejected", first, out.subList(0, 5));
        assertEquals("the lists add no sixth of their /24", 6, out.size());
        assertEquals(socks("70.1.1.1", 1080), out.get(5));
    }

    @Test
    public void selectStopsAtTheCapAndHasNoDuplicates() {
        List<ProxyCandidate> first = Arrays.asList(http("4.4.4.4", 80), socks("4.4.4.5", 1080));
        List<ProxyCandidate> list = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            list.add(http("5." + i + ".5.5", 80));
        }
        list.add(http("4.4.4.4", 80));
        List<ProxyCandidate> out = ProxySources.select(first, Collections.singletonList(list), 10, new Random(1));
        assertEquals(10, out.size());
        assertEquals(first, out.subList(0, 2));
        assertEquals(10, new HashSet<>(out).size());
        assertEquals(1, ProxySources.select(first, Collections.singletonList(list), 1, new Random(1)).size());
    }

    @Test
    public void selectGivesEachListItsQuotaPerRound() {
        List<ProxyCandidate> a = new ArrayList<>();
        List<ProxyCandidate> b = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            a.add(http("21." + i + ".1.1", 80));
            b.add(http("22." + i + ".1.1", 80));
        }
        List<ProxyCandidate> c = Arrays.asList(socks("23.1.1.1", 1080), socks("23.2.1.1", 1080), socks("23.3.1.1", 1080));
        List<ProxyCandidate> out = ProxySources.select(Collections.emptyList(), Arrays.asList(a, b, c),
                new int[]{1, 3, 2}, 40, new Random(6));

        // per round 1 of a, 3 of b, 2 of c (until its three are in): nine full rounds and one candidate of the tenth
        assertEquals(40, out.size());
        int[] count = new int[3];
        for (ProxyCandidate candidate : out) {
            count[Integer.parseInt(candidate.host().substring(0, 2)) - 21]++;
        }
        assertTrue(String.valueOf(count[0]), count[0] == 9 || count[0] == 10);
        assertTrue(String.valueOf(count[1]), count[1] == 27 || count[1] == 28);
        assertEquals(3, count[2]);
        int smallInFirstRound = 0;
        for (ProxyCandidate candidate : out.subList(0, 6)) {
            if (c.contains(candidate)) {
                smallInFirstRound++;
            }
        }
        assertEquals("the first round holds two of the small list", 2, smallInFirstRound);
    }

    @Test
    public void prefixCutsAtTheOctet() {
        assertEquals("1.2.3", ProxySources.prefix("1.2.3.4", 3));
        assertEquals("1.2", ProxySources.prefix("1.2.3.4", 2));
        assertEquals("100.200.3", ProxySources.prefix("100.200.3.4", 3));
        assertEquals("1.2", ProxySources.prefix("1.2", 3));
    }

    @Test
    public void listStatsQuotasFollowTheYieldAndHalveEachSearch() {
        ProxySources.ListStats stats = new ProxySources.ListStats();
        assertEquals(ProxySources.ListStats.DEFAULT_QUOTA, stats.quota("unknown"));
        stats.add("good", 7, 12);
        stats.add("fair", 3, 12);
        stats.add("poor", 2, 13);
        stats.add("dead", 0, 20);
        stats.add("lucky", 1, 1);
        assertEquals(4, stats.quota("good"));
        assertEquals(3, stats.quota("fair"));
        assertEquals(2, stats.quota("poor"));
        assertEquals("never 0: every list stays in the sample", 1, stats.quota("dead"));
        assertEquals("too few checks to judge", ProxySources.ListStats.DEFAULT_QUOTA, stats.quota("lucky"));

        // the last search counts as much as all before it
        stats.add("good", 0, 10);
        assertArrayEquals(new int[]{3, 16}, stats.counts.get("good"));
        assertEquals(2, stats.quota("good"));

        ProxySources.ListStats read = ProxySources.ListStats.fromJson(stats.toJson());
        assertEquals(stats.counts.keySet(), read.counts.keySet());
        for (String name : stats.counts.keySet()) {
            assertArrayEquals(name, stats.counts.get(name), read.counts.get(name));
        }
        assertTrue(ProxySources.ListStats.fromJson(null).counts.isEmpty());
        assertTrue(ProxySources.ListStats.fromJson("not json").counts.isEmpty());
        assertTrue(ProxySources.ListStats.fromJson("[1,2]").counts.isEmpty());
        assertEquals("more found than tried is not read", Collections.singleton("ok"),
                ProxySources.ListStats.fromJson("{\"bad\":[5,2],\"ok\":[1,9]}").counts.keySet());
    }

    @Test
    public void onlyPublicIpv4AddressesAreCandidates() {
        assertTrue(ProxySources.isPublicIpv4("1.2.3.4"));
        assertTrue(ProxySources.isPublicIpv4("172.32.0.1"));
        assertTrue(ProxySources.isPublicIpv4("100.128.0.1"));
        assertTrue(ProxySources.isPublicIpv4("198.17.255.255"));
        assertTrue(ProxySources.isPublicIpv4("198.20.0.1"));
        assertTrue(ProxySources.isPublicIpv4("192.0.1.1"));
        assertTrue(ProxySources.isPublicIpv4("223.255.255.255"));
        for (String host : new String[]{"0.0.0.0", "192.0.0.8", "192.0.2.1", "198.18.0.1", "198.19.255.255",
                "198.51.100.7", "203.0.113.9", "240.0.0.1", "0.1.2.3", "10.1.2.3", "127.0.0.1", "169.254.1.1", "172.16.0.1",
                "172.31.255.255", "192.168.0.1", "100.64.0.1", "224.0.0.1", "255.255.255.255", "1.2.3",
                "1.2.3.4.5", "1.2.3.256", "a.b.c.d", "::1", "", "1..2.3"}) {
            assertFalse(host, ProxySources.isPublicIpv4(host));
        }
    }
}
