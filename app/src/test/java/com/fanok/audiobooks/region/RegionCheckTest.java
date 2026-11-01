package com.fanok.audiobooks.region;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class RegionCheckTest {

    /** Answers by URL or STUN host; anything else fails. Records every request in order. */
    private static final class FakeNet implements RegionCheck.Net {
        final Map<String, String> bodies = new HashMap<>();
        final Map<String, String> stun = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        @Override
        public String get(String url) throws IOException {
            asked.add(url);
            String body = bodies.get(url);
            if (body == null) {
                throw new IOException("blocked");
            }
            return body;
        }

        @Override
        public InetAddress stun(String host, int port) throws IOException {
            asked.add(host + ":" + port);
            String address = stun.get(host);
            if (address == null) {
                throw new IOException("no answer");
            }
            return InetAddress.getByName(address);
        }
    }

    /** 77.88.0.0/16 and 2a02:6b8::/32 Russian, 176.8.0.0/16 Ukrainian, the rest of public space western. */
    private static final RegionCheck.AddressTable TABLE = address -> {
        if (!IpRegions.isPublic(address)) {
            return null;
        }
        byte[] a = address.getAddress();
        if (a.length == 4 && (a[0] & 0xFF) == 77 && (a[1] & 0xFF) == 88
                || a.length == 16 && (a[0] & 0xFF) == 0x2a && a[1] == 2 && a[2] == 6 && (a[3] & 0xFF) == 0xb8) {
            return Region.RUSSIA;
        }
        return a.length == 4 && (a[0] & 0xFF) == 176 && a[1] == 8 ? Region.UKRAINE : Region.WEST;
    };

    private static final class Tables implements Supplier<RegionCheck.AddressTable> {
        int reads;
        final RegionCheck.AddressTable table;

        Tables(RegionCheck.AddressTable table) {
            this.table = table;
        }

        @Override
        public RegionCheck.AddressTable get() {
            reads++;
            return table;
        }
    }

    private static RegionCheck.Device online() {
        RegionCheck.Device device = new RegionCheck.Device();
        device.online = true;
        return device;
    }

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @Test
    public void theFirstServiceThatTellsEndsTheCheck() {
        FakeNet net = new FakeNet();
        net.bodies.put("https://ipinfo.io/country", "DE\n");
        Tables tables = new Tables(TABLE);
        List<String> failed = new ArrayList<>();
        RegionCheck.Result r = RegionCheck.run(online(), net, tables, failed);
        assertEquals(Region.WEST, r.region);
        assertEquals("DE", r.country);
        assertEquals(RegionCheck.Source.SERVICE, r.source);
        assertEquals("ipinfo.io", r.via);
        assertEquals("one request, nothing else", Collections.singletonList("https://ipinfo.io/country"), net.asked);
        assertEquals("the table is not read", 0, tables.reads);
        assertTrue(failed.isEmpty());
    }

    @Test
    public void servicesAreAskedOneAfterAnother() {
        FakeNet net = new FakeNet();
        net.bodies.put("https://api.country.is/", "{\"ip\":\"176.8.1.2\",\"country\":\"UA\"}");
        List<String> failed = new ArrayList<>();
        RegionCheck.Result r = RegionCheck.run(online(), net, new Tables(TABLE), failed);
        assertEquals(Region.UKRAINE, r.region);
        assertEquals("country.is", r.via);
        assertEquals(2, net.asked.size());
        assertEquals(1, failed.size());
        assertTrue(failed.get(0), failed.get(0).startsWith("ipinfo.io: java.io.IOException"));
    }

    /** A device in Russia asks 2ip.io second and STUN of sipnet.ru first. */
    @Test
    public void theHintOrdersTheServers() {
        RegionCheck.Device device = online();
        device.networkCountry = "ru";
        FakeNet net = new FakeNet();
        net.bodies.put("https://api.2ip.io/", "{\"ip\":\"77.88.8.8\",\"country\":\"Russia\",\"code\":\"RU\"}");
        RegionCheck.Result r = RegionCheck.run(device, net, new Tables(TABLE), new ArrayList<>());
        assertEquals(Region.RUSSIA, r.region);
        assertEquals("2ip.io", r.via);
        assertEquals("https://ipinfo.io/country", net.asked.get(0));

        FakeNet stunOnly = new FakeNet();
        stunOnly.stun.put("stun.sipnet.ru", "77.88.8.8");
        RegionCheck.Result byStun = RegionCheck.run(device, stunOnly, new Tables(TABLE), new ArrayList<>());
        assertEquals(Region.RUSSIA, byStun.region);
        assertEquals(RegionCheck.Source.IP_TABLE, byStun.source);
        assertEquals("stun.sipnet.ru", byStun.via);
        assertEquals("3 services, then the first STUN server", 4, stunOnly.asked.size());

        RegionCheck.Device china = online();
        china.simCountry = "cn";
        FakeNet cn = new FakeNet();
        cn.stun.put("stun.miwifi.com", "39.128.0.7");
        assertEquals("stun.miwifi.com", RegionCheck.run(china, cn, new Tables(TABLE), new ArrayList<>()).via);
        assertEquals("one service in China, then Xiaomi's STUN", 2, cn.asked.size());
    }

    @Test
    public void aServiceWithAnAddressButNoCountryUsesTheTable() {
        FakeNet net = new FakeNet();
        net.bodies.put("https://ipinfo.io/country", "{\"ip\":\"77.88.8.8\",\"country\":null}");
        RegionCheck.Result r = RegionCheck.run(online(), net, new Tables(TABLE), new ArrayList<>());
        assertEquals(Region.RUSSIA, r.region);
        assertEquals(RegionCheck.Source.IP_TABLE, r.source);
        assertEquals("", r.country);
        assertEquals(1, net.asked.size());
    }

    @Test
    public void theDevicesOwnPublicAddressBeforeAnyStunServer() throws Exception {
        RegionCheck.Device device = online();
        device.addresses = java.util.Arrays.asList(ip("192.168.1.5"), ip("fe80::1"), ip("2a02:6b8:0:1::5"));
        FakeNet net = new FakeNet();
        RegionCheck.Result r = RegionCheck.run(device, net, new Tables(TABLE), new ArrayList<>());
        assertEquals(Region.RUSSIA, r.region);
        assertEquals("device", r.via);
        assertEquals("the three services only", 3, net.asked.size());
    }

    @Test
    public void echoServicesAfterStun() {
        FakeNet net = new FakeNet();
        net.bodies.put("https://checkip.amazonaws.com/", "176.8.9.10\n");
        RegionCheck.Result r = RegionCheck.run(online(), net, new Tables(TABLE), new ArrayList<>());
        assertEquals(Region.UKRAINE, r.region);
        assertEquals("checkip.amazonaws.com", r.via);
        assertEquals("3 services, 3 STUN servers, the first echo", 7, net.asked.size());
    }

    @Test
    public void aLoopbackEchoIsNoAnswer() {
        RegionCheck.Device device = online();
        device.networkCountry = "RU";
        device.roaming = false;
        FakeNet net = new FakeNet();
        net.bodies.put("https://ipv4-internet.yandex.net/api/v0/ip", "\"127.0.0.1\"");
        RegionCheck.Result r = RegionCheck.run(device, net, new Tables(TABLE), new ArrayList<>());
        assertEquals("the mobile network decides", RegionCheck.Source.MOBILE_NETWORK, r.source);
        assertEquals(Region.RUSSIA, r.region);
    }

    @Test
    public void offlineNeverAsksTheNetwork() {
        RegionCheck.Device device = new RegionCheck.Device();
        device.networkCountry = "ua";
        device.simCountry = "ru";
        FakeNet net = new FakeNet();
        Tables tables = new Tables(TABLE);
        RegionCheck.Result r = RegionCheck.run(device, net, tables, new ArrayList<>());
        assertEquals(Region.UKRAINE, r.region);
        assertEquals(RegionCheck.Source.MOBILE_NETWORK, r.source);
        assertTrue(net.asked.isEmpty());
        assertEquals("read for the device's own addresses", 1, tables.reads);
    }

    @Test
    public void withoutATableTheSecondStepIsSkipped() {
        RegionCheck.Device device = online();
        device.zoneCountry = "JP";
        FakeNet net = new FakeNet();
        RegionCheck.Result r = RegionCheck.run(device, net, () -> null, new ArrayList<>());
        assertEquals(Region.WEST, r.region);
        assertEquals(RegionCheck.Source.TIME_ZONE, r.source);
        assertEquals("the three services only", 3, net.asked.size());
    }

    @Test
    public void offlineOrder() {
        RegionCheck.Device d = new RegionCheck.Device();
        d.networkCountry = "PL";
        d.roaming = true;
        d.simCountry = "UA";
        d.zoneCountry = "PL";
        assertEquals("roaming: data comes out at home", Region.UKRAINE, RegionCheck.offline(d).region);
        assertEquals(RegionCheck.Source.SIM, RegionCheck.offline(d).source);
        d.simCountry = "";
        assertEquals(RegionCheck.Source.MOBILE_NETWORK, RegionCheck.offline(d).source);
        assertEquals(Region.WEST, RegionCheck.offline(d).region);
        d.networkCountry = "";
        assertEquals(RegionCheck.Source.TIME_ZONE, RegionCheck.offline(d).source);
        d.zoneCountry = "001";
        RegionCheck.Result none = RegionCheck.offline(d);
        assertEquals(Region.OTHER, none.region);
        assertEquals(RegionCheck.Source.NONE, none.source);
        d.vpn = true;
        assertTrue(RegionCheck.offline(d).vpn);
    }

    @Test
    public void countryAnswers() {
        assertArrayEquals(new String[]{"DE", null}, RegionCheck.countryAnswer("DE\n"));
        assertArrayEquals(new String[]{"DE", "84.1.2.3"},
                RegionCheck.countryAnswer("{\"ip\":\"84.1.2.3\",\"country\":\"DE\"}"));
        assertArrayEquals("2ip.io: the code, not the name", new String[]{"DE", "84.1.2.3"},
                RegionCheck.countryAnswer("{\"ip\":\"84.1.2.3\",\"country\":\"Germany\",\"code\":\"DE\"}"));
        assertArrayEquals(new String[]{"UA", "176.8.1.1"},
                RegionCheck.countryAnswer("{\"success\":true,\"ip\":\"176.8.1.1\",\"country_code\":\"UA\"}"));
        assertArrayEquals("success false", new String[2],
                RegionCheck.countryAnswer("{\"success\":false,\"message\":\"limit\"}"));
        assertArrayEquals(new String[]{"RU", "2a02:6b8::1"},
                RegionCheck.countryAnswer("fl=1\nh=www.cloudflare.com\nip=2a02:6b8::1\nloc=RU\ntls=TLSv1.3\n"));
        assertNull("a page", Region.ofCountry(RegionCheck.countryAnswer("<html><body>blocked</body></html>")[0]));
    }

    @Test
    public void addressesInAnswers() throws Exception {
        assertEquals(ip("5.6.7.8"), RegionCheck.address("\"5.6.7.8\""));
        assertEquals(ip("39.128.0.7"), RegionCheck.address("当前 IP：39.128.0.7  来自于：中国 云南"));
        assertEquals(ip("1.2.3.4"), RegionCheck.address("Current IP Address: 1.2.3.4"));
        assertEquals(ip("9.9.9.9"), RegionCheck.address("999.1.1.1 then 9.9.9.9"));
        assertEquals(ip("2a02:6b8::feed:ff"), RegionCheck.address("2a02:6b8::feed:ff\n"));
        assertNull("a time is no address", RegionCheck.address("at 12:30:45"));
        assertNull(RegionCheck.address("nothing here"));
        assertNull(RegionCheck.address(null));
    }

    @Test
    public void ipv6Literals() {
        assertEquals(16, RegionCheck.ipv6("::1").length);
        assertEquals(1, RegionCheck.ipv6("::1")[15]);
        assertEquals((byte) 0x20, RegionCheck.ipv6("2001:db8::")[0]);
        assertEquals(8, RegionCheck.ipv6("1:2:3:4:5:6:7:8")[15]);
        assertNull(RegionCheck.ipv6(":::"));
        assertNull(RegionCheck.ipv6("1::2::3"));
        assertNull(RegionCheck.ipv6("1:2:3"));
        assertNull(RegionCheck.ipv6("1:2:3:4:5:6:7:8:9"));
        assertNull(RegionCheck.ipv6("12345::1"));
        assertFalse(IpRegions.isPublic(null));
    }

    @Test
    public void publicAddresses() throws Exception {
        for (String a : new String[]{"77.88.8.8", "8.8.8.8", "2a02:6b8::1", "2001:4860:4860::8888"}) {
            assertTrue(a, IpRegions.isPublic(ip(a)));
        }
        for (String a : new String[]{"10.0.0.1", "100.64.1.1", "100.127.255.255", "127.0.0.1", "169.254.1.1",
                "172.16.0.1", "192.168.0.1", "192.0.0.8", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.9",
                "224.0.0.1", "240.0.0.1", "0.1.2.3", "::1", "fe80::1", "fd00::1", "ff02::1", "2001:db8::1",
                "2001:0:1::1", "2002:102:304::1", "::ffff:10.0.0.1"}) {
            assertFalse(a, IpRegions.isPublic(ip(a)));
        }
        assertTrue("100.128/9 is public", IpRegions.isPublic(ip("100.128.0.1")));
    }
}
